package com.freecontent.community.user.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.config.ProfileUpdateProperties;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.freecontent.community.user.ratelimit.ProfileUpdateRateLimiter;
import com.freecontent.community.media.entity.MediaAsset;
import com.freecontent.community.media.mapper.MediaAssetMapper;
import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.media.job.MediaProcessingWorker;
import com.freecontent.community.media.storage.UploadObjectCleaner;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.auth.email-challenge.delivery-enabled=false", "app.media.processing.enabled=false",
                "app.media.upload-cleanup.enabled=false"})
class ProfileUpdateFlowIT {
    private static final String PREFIX = "test:profile-update-it:" + UUID.randomUUID() + ":";
    private static final String PASSWORD = " ProfileUpdatePassword! ";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private PasswordHasher passwords;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisSessionStore sessions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private MediaAssetMapper assets;
    @Autowired private MediaProcessingWorker processing;
    @Autowired private StorageProperties storage;
    @Autowired private UploadObjectCleaner cleaner;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    private final List<String> fixtureIds = new ArrayList<>();
    private final List<String> objectKeys = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void prefixes(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.user.profile-update.redis-key-prefix", () -> PREFIX);
        registry.add("app.media.upload-creation.redis-key-prefix", () -> PREFIX);
        registry.add("app.media.upload-completion.redis-key-prefix", () -> PREFIX);
        registry.add("app.user.public-query.redis-key-prefix", () -> PREFIX);
        registry.add("app.user.avatar-access.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() throws Exception {
        for(String owner:fixtureIds) objectKeys.addAll(jdbc.queryForList(
                "SELECT o.object_key FROM media_processing_output o JOIN media_processing_task t ON t.id=o.task_id WHERE t.owner_id=?",String.class,owner));
        for(String key:new HashSet<>(objectKeys)) cleaner.deleteObject(key);
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String id : fixtureIds) {
            for(String table:List.of("media_upload_completion_request","media_processing_task","media_upload_cleanup",
                    "media_upload_request","media_upload_session","media_asset"))
                jdbc.update("DELETE FROM "+table+" WHERE owner_id=?",id);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", id);
            users.deleteById(id);
        }
    }

    @Test
    void ownReadyAvatarIsPersistedAndReturnedWithTheNewProfileVersion() throws Exception {
        UserAccount account=fixture(); String token=login(account);
        MediaAsset avatar=avatar(account.getId(),"AVATAR","READY");
        var before=sessions.find(token);
        var response=patch(token,body("expectedVersion",1,"avatarAssetId",avatar.getId(),"displayName","头像已保存"));
        assertEquals(200,response.statusCode(),response.body());
        assertEquals(avatar.getId(),JsonPath.read(response.body(),"$.data.avatarAssetId"));
        assertEquals(2,(Integer)JsonPath.read(response.body(),"$.data.version"));
        var saved=users.selectById(account.getId());
        assertEquals(avatar.getId(),saved.getAvatarAssetId()); assertEquals("头像已保存",saved.getDisplayName());
        assertEquals("原简介",saved.getBio()); assertEquals(1L,saved.getAuthVersion());
        assertEquals(before.expiresAt(),sessions.find(token).expiresAt());
        var reread=request("GET","/api/v1/me",token,null);
        assertEquals(avatar.getId(),JsonPath.read(reread.body(),"$.data.avatarAssetId"));
    }

    @Test
    void dynamicNicknameAndBioUpdatesKeepOmittedFieldsAndSessionsUnchanged() throws Exception {
        UserAccount account = fixture();
        UserAccount other = fixture();
        String token = login(account);
        var beforeSession = sessions.find(token);
        HttpResponse<String> first = patch(token, body("expectedVersion", 1, "displayName", "新昵称"));
        assertEquals(200, first.statusCode());
        assertEquals("新昵称", JsonPath.read(first.body(), "$.data.displayName"));
        assertEquals("原简介", JsonPath.read(first.body(), "$.data.bio"));
        assertEquals("avatar_before", JsonPath.read(first.body(), "$.data.avatarAssetId"));
        assertEquals(2, (Integer) JsonPath.read(first.body(), "$.data.version"));
        assertEquals("no-store", first.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(first.headers().firstValue("Set-Cookie").isEmpty());
        HttpResponse<String> second = patch(token, body("expectedVersion", 2, "bio", ""));
        assertEquals(200, second.statusCode());
        UserAccount after = users.selectById(account.getId());
        assertEquals("新昵称", after.getDisplayName());
        assertEquals("", after.getBio());
        assertEquals("avatar_before", after.getAvatarAssetId());
        assertEquals(3, after.getVersion());
        assertEquals(1L, after.getAuthVersion());
        assertEquals(account.getPasswordHash(), after.getPasswordHash());
        assertEquals(other.getDisplayName(), users.selectById(other.getId()).getDisplayName());
        assertEquals(1, users.selectById(other.getId()).getVersion());
        assertEquals(beforeSession.expiresAt(), sessions.find(token).expiresAt());
        HttpResponse<String> reread = request("GET", "/api/v1/me", token, null);
        assertEquals(200, reread.statusCode());
        assertEquals("新昵称", JsonPath.read(reread.body(), "$.data.displayName"));
        assertFalse(first.body().contains("passwordHash"));
        assertFalse(first.body().contains("tokenDigest"));
        assertFalse(first.body().contains("authVersion"));
    }

    @Test
    void explicitNullClearsAvatarWhileOmittedAvatarRemainsUnchanged() throws Exception {
        UserAccount account = fixture();
        String token = login(account);
        HttpResponse<String> response = patch(token, body("expectedVersion", 1, "avatarAssetId", null));
        assertEquals(200, response.statusCode());
        Map<String, Object> data = JsonPath.read(response.body(), "$.data");
        assertTrue(data.containsKey("avatarAssetId"));
        assertNull(data.get("avatarAssetId"));
        assertNull(users.selectById(account.getId()).getAvatarAssetId());
        assertEquals("原昵称", users.selectById(account.getId()).getDisplayName());
        assertEquals("原简介", users.selectById(account.getId()).getBio());
        assertEquals(2, users.selectById(account.getId()).getVersion());
    }

    @Test
    void sameValuesReturn200WithoutChangingVersionOrUpdatedTime() throws Exception {
        UserAccount account = fixture();
        String token = login(account);
        var before = users.selectById(account.getId());
        HttpResponse<String> response = patch(token, body("expectedVersion", 1, "displayName", "原昵称", "bio", "原简介"));
        assertEquals(200, response.statusCode());
        assertEquals(1, (Integer) JsonPath.read(response.body(), "$.data.version"));
        assertEquals(before.getUpdatedAt(), users.selectById(account.getId()).getUpdatedAt());
        assertEquals(1, users.selectById(account.getId()).getVersion());
        assertEquals(1L, users.selectById(account.getId()).getAuthVersion());
    }

    @Test
    void staleVersionReturnsCurrentVersionEvenWhenValuesAlreadyMatch() throws Exception {
        UserAccount account = fixture();
        String token = login(account);
        assertEquals(200, patch(token, body("expectedVersion", 1, "displayName", "新昵称")).statusCode());
        HttpResponse<String> stale = patch(token, body("expectedVersion", 1, "displayName", "新昵称"));
        assertEquals(409, stale.statusCode());
        assertEquals("RESOURCE_VERSION_CONFLICT", JsonPath.read(stale.body(), "$.code"));
        assertEquals(2, (Integer) JsonPath.read(stale.body(), "$.currentVersion"));
        assertEquals(2, users.selectById(account.getId()).getVersion());
    }

    @Test
    void missingAvatarFailsWholeUpdateWithoutChangingOtherFields() throws Exception {
        UserAccount account = fixture();
        String token = login(account);
        HttpResponse<String> response = patch(token, body("expectedVersion", 1, "displayName", "不能部分保存", "avatarAssetId", "new_avatar"));
        assertEquals(404, response.statusCode());
        assertEquals("RESOURCE_NOT_FOUND", JsonPath.read(response.body(), "$.code"));
        UserAccount after = users.selectById(account.getId());
        assertEquals(account.getDisplayName(), after.getDisplayName());
        assertEquals(account.getAvatarAssetId(), after.getAvatarAssetId());
        assertEquals(1, after.getVersion());
        assertEquals(account.getUpdatedAt(), after.getUpdatedAt());
    }

    @Test
    void anotherUsersReadyAvatarReturns404AndDoesNotChangeAnyProfileField() throws Exception {
        var account=fixture(); var other=fixture(); String token=login(account);
        var foreign=avatar(other.getId(),"AVATAR","READY");
        var response=patch(token,body("expectedVersion",1,"avatarAssetId",foreign.getId(),"displayName","不能部分保存"));
        assertEquals(404,response.statusCode(),response.body());
        assertEquals("RESOURCE_NOT_FOUND",JsonPath.read(response.body(),"$.code"));
        assertProfileUnchanged(account);
        assertFalse(response.body().contains(other.getId()));
    }

    @ParameterizedTest @ValueSource(strings={"CONTENT_IMAGE","CONTENT_VIDEO","REPORT_ATTACHMENT"})
    void otherMediaScenesReturn422WithTheAvatarFieldViolation(String scene) throws Exception {
        var account=fixture(); String token=login(account); var wrong=avatar(account.getId(),scene,"READY");
        var response=patch(token,body("expectedVersion",1,"avatarAssetId",wrong.getId(),"bio","不能部分保存"));
        assertEquals(422,response.statusCode(),response.body());
        assertEquals("VALIDATION_ERROR",JsonPath.read(response.body(),"$.code"));
        assertEquals("avatarAssetId",JsonPath.read(response.body(),"$.violations[0].path"));
        assertEquals("body",JsonPath.read(response.body(),"$.violations[0].location"));
        assertProfileUnchanged(account);
    }

    @ParameterizedTest @ValueSource(strings={"WAITING_UPLOAD","PROCESSING","FAILED"})
    void nonReadyAvatarsReturn409WithoutPartiallySavingTheProfile(String state) throws Exception {
        var account=fixture(); String token=login(account); var unready=avatar(account.getId(),"AVATAR",state);
        var response=patch(token,body("expectedVersion",1,"avatarAssetId",unready.getId(),"displayName","不能部分保存"));
        assertEquals(409,response.statusCode(),response.body());
        assertEquals("MEDIA_NOT_READY",JsonPath.read(response.body(),"$.code"));
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        assertProfileUnchanged(account);
    }

    @Test
    void savingTheSameReadyAvatarDoesNotIncrementVersionButStillRevalidatesTheReference() throws Exception {
        var account=fixture(); String token=login(account); var ready=avatar(account.getId(),"AVATAR","READY");
        assertEquals(200,patch(token,body("expectedVersion",1,"avatarAssetId",ready.getId())).statusCode());
        var before=users.selectById(account.getId());
        var repeated=patch(token,body("expectedVersion",2,"avatarAssetId",ready.getId()));
        assertEquals(200,repeated.statusCode(),repeated.body());
        assertEquals(2,(Integer)JsonPath.read(repeated.body(),"$.data.version"));
        assertEquals(before.getUpdatedAt(),users.selectById(account.getId()).getUpdatedAt());
        jdbc.update("UPDATE media_asset SET state='FAILED' WHERE id=?",ready.getId());
        var invalid=patch(token,body("expectedVersion",2,"avatarAssetId",ready.getId()));
        assertEquals(409,invalid.statusCode()); assertEquals("MEDIA_NOT_READY",JsonPath.read(invalid.body(),"$.code"));
        assertEquals(2,users.selectById(account.getId()).getVersion());
    }

    @Test
    void staleVersionIsRejectedBeforeLookingUpTheRequestedAvatar() throws Exception {
        var account=fixture(); String token=login(account);
        assertEquals(200,patch(token,body("expectedVersion",1,"bio","已修改")).statusCode());
        var response=patch(token,body("expectedVersion",1,"avatarAssetId","nonexistent_avatar"));
        assertEquals(409,response.statusCode(),response.body());
        assertEquals("RESOURCE_VERSION_CONFLICT",JsonPath.read(response.body(),"$.code"));
        assertEquals(2,(Integer)JsonPath.read(response.body(),"$.currentVersion"));
        assertEquals("avatar_before",users.selectById(account.getId()).getAvatarAssetId());
    }

    @Test
    void replacingAndClearingTheAvatarKeepOldMediaAvailableForReuse() throws Exception {
        var account=fixture(); String token=login(account);
        var first=avatar(account.getId(),"AVATAR","READY"); var second=avatar(account.getId(),"AVATAR","READY");
        assertEquals(200,patch(token,body("expectedVersion",1,"avatarAssetId",first.getId())).statusCode());
        assertEquals(200,patch(token,body("expectedVersion",2,"avatarAssetId",second.getId())).statusCode());
        assertEquals("READY",assets.selectById(first.getId()).getState());
        var cleared=patch(token,body("expectedVersion",3,"avatarAssetId",null));
        assertEquals(200,cleared.statusCode(),cleared.body());
        assertNull(JsonPath.read(cleared.body(),"$.data.avatarAssetId"));
        assertEquals("READY",assets.selectById(second.getId()).getState());
        var reused=patch(token,body("expectedVersion",4,"avatarAssetId",first.getId()));
        assertEquals(200,reused.statusCode(),reused.body());
        assertEquals(first.getId(),users.selectById(account.getId()).getAvatarAssetId());
        assertEquals(5,users.selectById(account.getId()).getVersion());
    }

    @Test
    void concurrentAvatarChangesWithTheSameVersionHaveExactlyOneWinner() throws Exception {
        var account=fixture(); String token=login(account);
        var first=avatar(account.getId(),"AVATAR","READY"); var second=avatar(account.getId(),"AVATAR","READY");
        var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));return patch(token,body("expectedVersion",1,"avatarAssetId",first.getId()));});
            var b=pool.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));return patch(token,body("expectedVersion",1,"avatarAssetId",second.getId()));});
            start.countDown(); var ar=a.get(15,TimeUnit.SECONDS); var br=b.get(15,TimeUnit.SECONDS);
            assertEquals(List.of(200,409),List.of(ar.statusCode(),br.statusCode()).stream().sorted().toList());
            var winner=ar.statusCode()==200?ar:br;
            assertEquals((String)JsonPath.read(winner.body(),"$.data.avatarAssetId"),users.selectById(account.getId()).getAvatarAssetId());
            assertEquals(2,users.selectById(account.getId()).getVersion());
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test
    void realUploadValidationAndProfileBindingCompleteTheAvatarSavePipeline() throws Exception {
        var account=fixture(); String token=login(account);
        var out=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,3,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);
        byte[] bytes=out.toByteArray();
        var created=request("POST","/api/v1/media/uploads",token,body("scene","AVATAR","fileName","avatar.png","contentType","image/png","sizeBytes",bytes.length));
        assertEquals(201,created.statusCode(),created.body());
        String uploadId=JsonPath.read(created.body(),"$.data.id"), assetId=JsonPath.read(created.body(),"$.data.assetId");
        objectKeys.add(jdbc.queryForObject("SELECT storage_key FROM media_upload_session WHERE id=?",String.class,uploadId));
        Map<String,Object> target=JsonPath.read(created.body(),"$.data.singleUploadTarget");
        var put=HttpRequest.newBuilder(URI.create((String)target.get("url"))).timeout(Duration.ofSeconds(15));
        ((Map<String,String>)target.get("headers")).forEach((name,value)->{if(!name.equalsIgnoreCase("Content-Length"))put.header(name,value);});
        var uploaded=client.send(put.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,uploaded.statusCode(),uploaded.body());
        var accepted=request("POST","/api/v1/media/uploads/"+uploadId+"/completion",token,body("expectedVersion",1));
        assertEquals(202,accepted.statusCode(),accepted.body());
        String taskId=JsonPath.read(accepted.body(),"$.data.taskId"); processing.processTask(taskId);
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT state FROM media_processing_task WHERE id=?",String.class,taskId));
        var ready=assets.selectById(assetId); assertEquals("READY",ready.getState());
        assertEquals(storage.identity(),ready.getReadyStorageId()); assertEquals(2,ready.getWidth()); assertEquals(3,ready.getHeight());
        var saved=patch(token,body("expectedVersion",1,"avatarAssetId",assetId));
        assertEquals(200,saved.statusCode(),saved.body()); assertEquals(assetId,JsonPath.read(saved.body(),"$.data.avatarAssetId"));
        assertEquals(assetId,users.selectById(account.getId()).getAvatarAssetId());
        assertEquals("READY",assets.selectById(assetId).getState());
        var profile=request("GET","/api/v1/users/"+account.getId(),null,null);
        assertEquals(200,profile.statusCode(),profile.body());
        String avatarUrl=JsonPath.read(profile.body(),"$.data.avatarUrl");assertNotNull(avatarUrl);
        var redirected=client.send(HttpRequest.newBuilder(URI.create(avatarUrl)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(302,redirected.statusCode(),redirected.body());
        var visible=client.send(HttpRequest.newBuilder(URI.create(redirected.headers().firstValue("Location").orElseThrow())).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,visible.statusCode());assertArrayEquals(bytes,visible.body());
    }

    private void assertProfileUnchanged(UserAccount before) {
        var after=users.selectById(before.getId());
        assertEquals(before.getDisplayName(),after.getDisplayName()); assertEquals(before.getBio(),after.getBio());
        assertEquals(before.getAvatarAssetId(),after.getAvatarAssetId()); assertEquals(before.getVersion(),after.getVersion());
        assertEquals(before.getUpdatedAt(),after.getUpdatedAt()); assertEquals(before.getAuthVersion(),after.getAuthVersion());
    }

    @Test
    void realDatabaseAcceptsMaximumUnicodeLengthsAndBindsSqlValues() throws Exception {
        UserAccount account = fixture();
        String token = login(account);
        String name = "😀".repeat(40);
        String bio = "😀".repeat(500);
        assertEquals(200, patch(token, body("expectedVersion", 1, "displayName", name, "bio", bio)).statusCode());
        assertEquals(name, users.selectById(account.getId()).getDisplayName());
        assertEquals(bio, users.selectById(account.getId()).getBio());
        String sqlText = "x', account_status='DISABLED";
        assertEquals(200, patch(token, body("expectedVersion", 2, "displayName", sqlText)).statusCode());
        assertEquals(sqlText, users.selectById(account.getId()).getDisplayName());
        assertEquals(UserAccount.Status.ACTIVE, users.selectById(account.getId()).getAccountStatus());
    }

    @Test
    void accountRestrictionChangedAfterAuthenticationStillBlocksWriting() throws Exception {
        UserAccount account = fixture();
        String token = login(account);
        doAnswer(invocation -> {
            SessionPrincipal actor = (SessionPrincipal) invocation.callRealMethod();
            jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", account.getId());
            return actor;
        }).when(authentication).authenticate(token);
        assertEquals(403, patch(token, body("expectedVersion", 1, "bio", "不应写入")).statusCode());
        assertEquals("原简介", users.selectById(account.getId()).getBio());
        assertEquals(1, users.selectById(account.getId()).getVersion());
    }

    @Test
    void changedCredentialVersionAfterAuthenticationCannotUpdateProfile() throws Exception {
        UserAccount account = fixture();
        String token = login(account);
        doAnswer(invocation -> {
            SessionPrincipal actor = (SessionPrincipal) invocation.callRealMethod();
            jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", account.getId());
            return actor;
        }).when(authentication).authenticate(token);
        HttpResponse<String> response = patch(token, body("expectedVersion", 1, "displayName", "不应写入"));
        assertEquals(401, response.statusCode());
        assertEquals("AUTHENTICATION_REQUIRED", JsonPath.read(response.body(), "$.code"));
        assertEquals("原昵称", users.selectById(account.getId()).getDisplayName());
        assertEquals(1, users.selectById(account.getId()).getVersion());
        assertEquals(2L, users.selectById(account.getId()).getAuthVersion());
    }

    @Test
    void concurrentEditsWithOneVersionCannotOverwriteEachOther() throws Exception {
        UserAccount account = fixture();
        String token = login(account);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return patch(token, body("expectedVersion", 1, "displayName", "并发昵称")).statusCode();
            });
            Future<Integer> second = pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return patch(token, body("expectedVersion", 1, "bio", "并发简介")).statusCode();
            });
            start.countDown();
            List<Integer> results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, Collections.frequency(results, 200));
            assertEquals(1, Collections.frequency(results, 409));
            UserAccount after = users.selectById(account.getId());
            assertEquals(2, after.getVersion());
            assertTrue((after.getDisplayName().equals("并发昵称") && after.getBio().equals("原简介"))
                    || (after.getDisplayName().equals("原昵称") && after.getBio().equals("并发简介")));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void profileQuotaIsIndependentAndReturnsRetryAfter() {
        String prefix = PREFIX + "quota:";
        ProfileUpdateRateLimiter limiter = new ProfileUpdateRateLimiter(redis, new ProfileUpdateProperties(30, 20, prefix));
        for (int i = 0; i < 20; i++) limiter.acquire("same-user", "user-source");
        BusinessException user = assertThrows(BusinessException.class, () -> limiter.acquire("same-user", "other-source"));
        assertEquals(ErrorCode.RATE_LIMITED, user.errorCode());
        assertTrue(user.retryAfterSeconds() > 0 && user.retryAfterSeconds() <= 3600);
        for (int i = 0; i < 30; i++) limiter.acquire("user" + i, "source-limit");
        BusinessException source = assertThrows(BusinessException.class, () -> limiter.acquire("new-user", "source-limit"));
        assertEquals(ErrorCode.RATE_LIMITED, source.errorCode());
        assertTrue(source.retryAfterSeconds() > 0 && source.retryAfterSeconds() <= 60);
        assertFalse(redis.hasKey(prefix + "{profile-update}:user:" + TokenDigest.sha256("new-user")));
    }

    @Test
    void swaggerDeclaresOnlyFourInputPropertiesAndNullableAvatar() throws Exception {
        HttpResponse<String> docs = request("GET", "/v3/api-docs", null, null);
        assertEquals(200, docs.statusCode());
        Map<String, Object> operation = JsonPath.read(docs.body(), "$.paths['/api/v1/me'].patch");
        assertEquals("updateCurrentUser", operation.get("operationId"));
        assertTrue(((List<?>) operation.get("security")).stream().anyMatch(value -> ((Map<?, ?>) value).containsKey("bearerAuth")));
        Map<String, Object> schema = JsonPath.read(docs.body(), "$.components.schemas.UpdateProfileRequest");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertEquals(Set.of("expectedVersion", "displayName", "bio", "avatarAssetId"), properties.keySet());
        assertEquals(List.of("expectedVersion"), schema.get("required"));
        assertEquals(2, schema.get("minProperties"));
        assertEquals(false, schema.get("additionalProperties"));
        assertTrue(properties.get("avatarAssetId").toString().contains("null"));
    }

    private UserAccount fixture() {
        UserAccount account = LoginTestSupport.account();
        account.setId(UUID.randomUUID().toString().replace("-", ""));
        account.setEmail("profile-update-it-" + account.getId() + "@example.test");
        account.setEmailNormalized(account.getEmail());
        account.setDisplayName("原昵称");
        account.setBio("原简介");
        account.setAvatarAssetId("avatar_before");
        account.setPasswordHash(passwords.encode(PASSWORD));
        fixtureIds.add(account.getId());
        users.insert(account);
        roles.insertDefaultRole(account.getId(), account.getCreatedAt());
        return account;
    }

    private MediaAsset avatar(String owner,String scene,String state) {
        var asset=new MediaAsset(); asset.setId(UUID.randomUUID().toString().replace("-","")); asset.setOwnerId(owner);
        asset.setScene(scene); asset.setState(state); asset.setFileName("avatar.png");
        asset.setDeclaredContentType("image/png"); asset.setDeclaredSizeBytes(67L);
        asset.setStorageKey("private/"+owner+"/"+asset.getId()+"/source");
        asset.setCreatedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        if("READY".equals(state)) {
            asset.setActualContentType("image/png"); asset.setActualSizeBytes(67L); asset.setWidth(1); asset.setHeight(1);
            asset.setReadyObjectKey("private/"+owner+"/"+asset.getId()+"/ready/fixture");
            asset.setReadyObjectEtag("fixture-etag"); asset.setReadyStorageId("a".repeat(64));
        }
        assets.insert(asset); return asset;
    }

    private String login(UserAccount account) throws Exception {
        HttpResponse<String> response = request("POST", "/api/v1/auth/sessions", null,
                body("email", account.getEmail(), "password", PASSWORD));
        assertEquals(201, response.statusCode());
        return JsonPath.read(response.body(), "$.data.accessToken");
    }

    private HttpResponse<String> patch(String token, Map<String, Object> body) throws Exception {
        return request("PATCH", "/api/v1/me", token, body);
    }

    private HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (method.equals("POST") && path.startsWith("/api/v1/media/uploads")) builder.header("Idempotency-Key",UUID.randomUUID().toString());
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> body(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
