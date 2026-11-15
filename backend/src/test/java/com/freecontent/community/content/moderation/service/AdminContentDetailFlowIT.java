package com.freecontent.community.content.moderation.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.mapper.ContentReadMapper;
import com.freecontent.community.content.moderation.mapper.AdminContentQueryMapper;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.mybatis.spring.SqlSessionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class AdminContentDetailFlowIT {
    private static final String PREFIX = "test:admin-content-detail:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private StringRedisTemplate redis;
    @MockitoSpyBean private AdminContentQueryMapper queries;
    @MockitoSpyBean private ContentReadMapper reads;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @Autowired private SqlSessionTemplate sqlSession;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) {
        for (String scope : List.of("auth.login", "content.admin-query", "content.admin-review-query", "content.query", "content.draft-save"))
            registry.add("app." + scope + ".redis-key-prefix", () -> PREFIX);
    }
    @AfterEach
    void cleanup() {
        reset(redis, queries, reads, authentication);
        var keys = redis.keys(PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) { ContentTestSupport.clearReviews(jdbc, owner); ContentTestSupport.clearWorkspace(jdbc, owner); }
        for (String owner : owners) {
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?", owner); jdbc.update("DELETE FROM community_tag WHERE created_by=?", owner);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner); users.deleteById(owner);
        }
    }
    @Test
    void moderatorReadsAnotherAuthorsCurrentWorkspaceByContentId() throws Exception {
        var moderator = account("MODERATOR"); var author = account(null); var seed = content(author, "ACTIVE", "PUBLISHED");
        var response = detail(seed.content(), token(moderator)); assertEquals(200, response.statusCode(), response.body());
        assertEquals(seed.content(), JsonPath.read(response.body(), "$.data.id")); assertEquals(author.getId(), JsonPath.read(response.body(), "$.data.authorId"));
        assertEquals(seed.revision(), JsonPath.read(response.body(), "$.data.publishedRevision.id"));
        assertEquals("APPROVED", JsonPath.read(response.body(), "$.data.publishedRevision.status")); assertEquals(7, JsonPath.<Integer>read(response.body(), "$.data.version"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }
    @ParameterizedTest
    @CsvSource({"ACTIVE,UNPUBLISHED", "ACTIVE,PUBLISHED", "ACTIVE,WITHDRAWN", "ACTIVE,TAKEN_DOWN", "DELETED,UNPUBLISHED", "DELETED,PUBLISHED", "DELETED,WITHDRAWN", "DELETED,TAKEN_DOWN"})
    void moderatorReadsEveryLifecycleAndPublicationState(String lifecycle, String publication) throws Exception {
        var moderator = account("MODERATOR"); var author = account(null); var seed = content(author, lifecycle, publication);
        var response = detail(seed.content(), token(moderator)); assertEquals(200, response.statusCode(), response.body());
        assertEquals(lifecycle, JsonPath.read(response.body(), "$.data.lifecycle")); assertEquals(publication, JsonPath.read(response.body(), "$.data.publicationStatus"));
        if (publication.equals("UNPUBLISHED")) assertEquals(seed.revision(), JsonPath.read(response.body(), "$.data.workingRevision.id"));
        else assertEquals(seed.revision(), JsonPath.read(response.body(), "$.data.publishedRevision.id"));
    }
    @Test
    void ownEmptyWorkspaceRemainsReadableWithoutAnActiveDraftOrPublication() throws Exception {
        var moderator = account("ADMIN"); var seed = content(moderator, "ACTIVE", "UNPUBLISHED");
        jdbc.update("UPDATE community_content SET working_revision_id=NULL WHERE id=?", seed.content());
        jdbc.update("UPDATE content_revision SET status='CANCELLED' WHERE id=?", seed.revision());
        var response = detail(seed.content(), token(moderator)); assertEquals(200, response.statusCode(), response.body());
        assertNull(JsonPath.read(response.body(), "$.data.workingRevision")); assertNull(JsonPath.read(response.body(), "$.data.publishedRevision"));
    }
    @Test
    void writeBlockedModeratorWithoutUserRoleCanReadADisabledAuthorsDeletedContent() throws Exception {
        var moderator = account("MODERATOR"); var author = account(null); var seed = content(author, "DELETED", "TAKEN_DOWN");
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", moderator.getId());
        jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='USER'", moderator.getId());
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", author.getId());
        var response = detail(seed.content(), token(moderator)); assertEquals(200, response.statusCode(), response.body());
        assertEquals(author.getId(), JsonPath.read(response.body(), "$.data.authorId"));
    }
    @Test
    void detailAndListShareQuotaIncludingRepeatedReadsAndValid404s() throws Exception {
        var moderator = account("MODERATOR"); var seed = content(account(null), "ACTIVE", "PUBLISHED"); String token = token(moderator);
        assertEquals(200, list("?authorId=" + moderator.getId(), token).statusCode());
        assertEquals(200, detail(seed.content(), token).statusCode()); assertEquals(200, detail(seed.content(), token).statusCode());
        problem(detail(id(), token), 404, "RESOURCE_NOT_FOUND");
        assertEquals("4", redis.opsForValue().get(sourceKey())); assertEquals("4", redis.opsForValue().get(userKey(moderator.getId())));
        assertNull(redis.opsForValue().get(PREFIX + "{content-admin-review-query}:user:" + TokenDigest.sha256(moderator.getId())));
    }
    @ParameterizedTest @ValueSource(strings = {"SOURCE", "USER"})
    void exhaustedDetailQuotaReturns429WithoutReadingContent(String scope) throws Exception {
        var moderator = account("MODERATOR"); var seed = content(account(null), "ACTIVE", "PUBLISHED");
        redis.opsForValue().set(scope.equals("SOURCE") ? sourceKey() : userKey(moderator.getId()), scope.equals("SOURCE") ? "120" : "60", Duration.ofMinutes(1));
        var response = detail(seed.content(), token(moderator)); problem(response, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0); verifyNoInteractions(reads, queries);
    }
    @ParameterizedTest @ValueSource(strings = {"AUTH_VERSION", "UNVERIFIED", "DISABLED", "NO_MODERATION_ROLE"})
    void detailRechecksPrivilegesAfterTheAuthenticationFilter(String condition) throws Exception {
        var moderator = account("MODERATOR"); var seed = content(account(null), "ACTIVE", "PUBLISHED"); String token = token(moderator);
        doAnswer(call -> {
            var actor = (SessionPrincipal) call.callRealMethod();
            switch (condition) {
                case "AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", moderator.getId());
                case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=false WHERE id=?", moderator.getId());
                case "DISABLED" -> jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", moderator.getId());
                default -> jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code IN ('MODERATOR','ADMIN')", moderator.getId());
            }
            return actor;
        }).when(authentication).authenticate(token);
        problem(detail(seed.content(), token), condition.equals("AUTH_VERSION") ? 401 : 403,
                condition.equals("AUTH_VERSION") ? "AUTHENTICATION_REQUIRED" : condition.equals("DISABLED") ? "ACCOUNT_RESTRICTED" : "PERMISSION_DENIED");
        verifyNoInteractions(reads);
    }
    @Test
    void redisFailureDoesNotReadAnyContent() throws Exception {
        var moderator = account("MODERATOR"); var seed = content(account(null), "ACTIVE", "PUBLISHED"); String token = token(moderator);
        doThrow(new RedisConnectionFailureException("sensitive Redis detail")).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(), any());
        var response = detail(seed.content(), token); problem(response, 503, "DEPENDENCY_UNAVAILABLE"); assertFalse(response.body().contains("sensitive")); verifyNoInteractions(reads);
    }
    @ParameterizedTest @ValueSource(strings = {"CONTENT", "REVISION", "REFERENCES"})
    void databaseFailuresAre503AndNeverFakeANotFoundOrEmptyWorkspace(String point) throws Exception {
        var moderator = account("MODERATOR"); var seed = content(account(null), "ACTIVE", "PUBLISHED");
        var failure = new TransientDataAccessResourceException("sensitive SQL detail");
        switch (point) {
            case "CONTENT" -> doThrow(failure).when(reads).findById(anyString());
            case "REVISION" -> doThrow(failure).when(reads).findRevision(anyString(), anyString());
            default -> doThrow(failure).when(reads).findAssets(anyString());
        }
        var response = detail(seed.content(), token(moderator)); problem(response, 503, "DEPENDENCY_UNAVAILABLE"); assertFalse(response.body().contains("sensitive"));
    }
    @ParameterizedTest @ValueSource(strings = {"MISSING_PUBLICATION", "WORKING_APPROVED", "PUBLISHED_REJECTED", "MISSING_REVISION"})
    void corruptRevisionBindingsFailClosed(String issue) throws Exception {
        var moderator = account("ADMIN"); var seed = content(account(null), "ACTIVE", "PUBLISHED");
        switch (issue) {
            case "MISSING_PUBLICATION" -> jdbc.update("UPDATE community_content SET published_revision_id=NULL WHERE id=?", seed.content());
            case "WORKING_APPROVED" -> jdbc.update("UPDATE community_content SET working_revision_id=? WHERE id=?", seed.revision(), seed.content());
            case "PUBLISHED_REJECTED" -> jdbc.update("UPDATE content_revision SET status='REJECTED' WHERE id=?", seed.revision());
            default -> doReturn(null).when(reads).findRevision(seed.revision(), seed.content());
        }
        problem(detail(seed.content(), token(moderator)), 503, "DEPENDENCY_UNAVAILABLE");
    }
    @Test
    void currentWorkspaceKeepsItsOwnPointersAndReferencesInsteadOfAnOldReviewSnapshot() throws Exception {
        var moderator = account("MODERATOR"); var author = account(null); var seed = content(author, "ACTIVE", "WITHDRAWN");
        String rejected = id(), working = id(), oldReview = id(), token = token(moderator);
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) "
                + "VALUES(?,?,2,'REJECTED','旧审核标题','旧审核正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),(?,?,3,'IN_REVIEW','当前标题','当前正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", rejected, seed.content(), working, seed.content());
        jdbc.update("INSERT INTO content_review(id,content_id,revision_id,purpose,state,version,submitted_at,decided_at,reviewer_id,reason) "
                + "VALUES(?,?,?,'UPDATE','REJECTED',2,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?,'驳回说明')", oldReview, seed.content(), rejected, moderator.getId());
        String pending = ContentTestSupport.pending(jdbc, seed.content(), working);
        jdbc.update("UPDATE content_review SET purpose='UPDATE' WHERE id=?", pending);
        jdbc.update("UPDATE community_content SET working_revision_id=?,active_review_id=? WHERE id=?", working, pending, seed.content());
        String image1 = media(author), image2 = media(author), tag = id();
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'IMAGE',1),(?,?,'IMAGE',0),(?,?,'COVER',0)", working,image1,working,image2,seed.revision(),image1);
        jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) VALUES(?,?,'说明','DISABLED',1,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", tag,"tag_" + tag.substring(0,16),author.getId());
        jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,0)", working,tag);
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        var revisions = jdbc.queryForList("SELECT * FROM content_revision WHERE content_id=? ORDER BY revision_number", seed.content());
        var response = detail(seed.content(),token); assertEquals(200,response.statusCode(),response.body());
        assertEquals(working,JsonPath.read(response.body(),"$.data.workingRevision.id")); assertEquals("当前正文",JsonPath.read(response.body(),"$.data.workingRevision.bodyMarkdown"));
        assertEquals(List.of(image2,image1),JsonPath.read(response.body(),"$.data.workingRevision.imageAssetIds")); assertEquals(List.of(tag),JsonPath.read(response.body(),"$.data.workingRevision.tagIds"));
        assertEquals(image1,JsonPath.read(response.body(),"$.data.publishedRevision.coverAssetId")); assertEquals(pending,JsonPath.read(response.body(),"$.data.activeReviewId"));
        var review = getPath("/api/v1/admin/reviews/" + oldReview,token); assertEquals(200,review.statusCode(),review.body());
        assertEquals(rejected,JsonPath.read(review.body(),"$.data.revision.id"));
        assertEquals(JsonPath.<Map<String,Object>>read(response.body(),"$.data"),JsonPath.read(review.body(),"$.data.content"));
        assertEquals(before,jdbc.queryForMap("SELECT * FROM community_content WHERE id=?",seed.content()));
        assertEquals(revisions,jdbc.queryForList("SELECT * FROM content_revision WHERE content_id=? ORDER BY revision_number",seed.content()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_lifecycle_outbox WHERE content_id=?",Integer.class,seed.content()));
    }
    @Test
    void detailUsesOneSnapshotWhileTheAuthorSavesANewerDraftVersion() throws Exception {
        var moderator = account("MODERATOR"); var author = account(null); var seed = content(author,"ACTIVE","UNPUBLISHED");
        String token = token(moderator), authorToken = token(author);
        var contentRead = new CountDownLatch(1); var proceed = new CountDownLatch(1); var pool = Executors.newSingleThreadExecutor();
        var pauseFirstRead = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> {
            if (pauseFirstRead.compareAndSet(true,false)) { contentRead.countDown(); assertTrue(proceed.await(10,TimeUnit.SECONDS)); }
            return sqlSession.selectOne("com.freecontent.community.content.mapper.ContentReadMapper.findRevision",Map.<String,Object>of("id",call.getArgument(0),"contentId",call.getArgument(1)));
        }).when(reads).findRevision(seed.revision(),seed.content());
        try {
            var reading = pool.submit(() -> detail(seed.content(),token)); assertTrue(contentRead.await(10,TimeUnit.SECONDS));
            var saved = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/creator/contents/" + seed.content() + "/draft"))
                    .timeout(Duration.ofSeconds(20)).header("Authorization","Bearer " + authorToken).header("Content-Type","application/json")
                    .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"expectedVersion\":7,\"title\":\"并发新标题\",\"bodyMarkdown\":\"并发新正文\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,saved.statusCode(),saved.body()); proceed.countDown();
            var response = reading.get(30,TimeUnit.SECONDS); assertEquals(200,response.statusCode(),response.body());
            assertEquals(7,JsonPath.<Integer>read(response.body(),"$.data.version")); assertEquals("后台标题",JsonPath.read(response.body(),"$.data.workingRevision.title"));
            assertEquals(8,jdbc.queryForObject("SELECT version FROM community_content WHERE id=?",Integer.class,seed.content()));
            assertEquals("并发新标题",jdbc.queryForObject("SELECT title FROM content_revision WHERE id=?",String.class,seed.revision()));
        } finally { proceed.countDown(); pool.shutdownNow(); }
    }
    @Test
    void openApiDeclaresOnlyThePathIdAndTheWorkspaceResult() throws Exception {
        var response = getPath("/v3/api-docs",null); assertEquals(200,response.statusCode(),response.body());
        Map<String,Object> operation = JsonPath.read(response.body(),"$.paths['/api/v1/admin/contents/{contentId}'].get");
        assertEquals("getAdminContent",operation.get("operationId")); assertEquals(List.of(Map.of("bearerAuth",List.of())),operation.get("security")); assertFalse(operation.containsKey("requestBody"));
        List<Map<String,Object>> parameters = JsonPath.read(response.body(),"$.paths['/api/v1/admin/contents/{contentId}'].get.parameters");
        assertEquals(List.of("contentId"),parameters.stream().map(value -> value.get("name")).toList());
        assertNotNull(JsonPath.read(response.body(),"$.paths['/api/v1/admin/contents/{contentId}'].get.responses['200'].content['application/json'].schema['$ref']"));
    }
    private HttpResponse<String> getPath(String path,String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(45)).GET();
        if (token != null) request.header("Authorization","Bearer " + token); return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String media(UserAccount author) {
        String asset = id(); jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) "
                + "VALUES(?,?,'CONTENT_IMAGE','image.png','image/png',1,?,'FAILED',UTC_TIMESTAMP(6))",asset,author.getId(),"admin-detail/" + asset); return asset;
    }
    private String sourceKey() { return PREFIX + "{content-admin-query}:source:" + TokenDigest.sha256("127.0.0.1"); }
    private String userKey(String actor) { return PREFIX + "{content-admin-query}:user:" + TokenDigest.sha256(actor); }
    private HttpResponse<String> detail(String content, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/contents/" + content)).timeout(Duration.ofSeconds(45)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private Seed content(UserAccount author, String lifecycle, String publication) {
        String content = id(), revision = id();
        jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) "
                + "VALUES(?,?,'ARTICLE',?,'UNPUBLISHED',7,UTC_TIMESTAMP(6),'2035-01-01 08:00:00')", content, author.getId(), lifecycle);
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) "
                + "VALUES(?,?,1,?,'后台标题','后台正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", revision, content, publication.equals("UNPUBLISHED") ? "DRAFT" : "APPROVED");
        if (publication.equals("UNPUBLISHED")) jdbc.update("UPDATE community_content SET working_revision_id=? WHERE id=?", revision, content);
        else jdbc.update("UPDATE community_content SET publication_status=?,published_revision_id=?,first_published_at='2026-11-03 08:00:00',published_at='2026-11-04 08:00:00' WHERE id=?", publication, revision, content);
        if (lifecycle.equals("DELETED")) jdbc.update("UPDATE community_content SET deleted_at=UTC_TIMESTAMP(6) WHERE id=?", content);
        return new Seed(content, revision);
    }
    private UserAccount account(String role) {
        var actor = LoginTestSupport.account(); actor.setId(id()); actor.setEmail("admin-content-" + actor.getId() + "@example.test"); actor.setEmailNormalized(actor.getEmail());
        users.insert(actor); owners.add(actor.getId()); roles.insertDefaultRole(actor.getId(), actor.getCreatedAt());
        if (role != null) jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,?,UTC_TIMESTAMP(6))", actor.getId(), role);
        return actor;
    }
    private String token(UserAccount actor) { return sessions.create(actor.getId(), 1, "admin-content-test").accessToken(); }
    private HttpResponse<String> list(String query, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/contents" + query)).timeout(Duration.ofSeconds(45)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertFalse(JsonPath.<Map<String,Object>>read(response.body(), "$").containsKey("data"));
    }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private record Seed(String content, String revision) { }
}
