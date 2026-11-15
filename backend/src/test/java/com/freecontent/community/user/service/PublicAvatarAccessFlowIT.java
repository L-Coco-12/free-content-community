package com.freecontent.community.user.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.media.entity.MediaAsset;
import com.freecontent.community.media.mapper.MediaAssetMapper;
import com.freecontent.community.media.storage.MediaObjectReader;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.jayway.jsonpath.JsonPath;
import io.minio.*;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.io.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "app.media.processing.enabled=false","app.media.upload-cleanup.enabled=false","app.auth.email-challenge.delivery-enabled=false"})
class PublicAvatarAccessFlowIT {
    private static final String PREFIX="test:public-avatar:"+UUID.randomUUID()+":";
    @Value("${local.server.port}") int port;
    @Autowired UserAccountMapper users;
    @Autowired UserRoleMapper roles;
    @Autowired MediaAssetMapper assets;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired RedisSessionStore sessions;
    @Autowired StorageProperties storage;
    @MockitoSpyBean MediaObjectReader reader;
    private final List<String> owners=new ArrayList<>(),keys=new ArrayList<>();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry) {
        registry.add("app.user.avatar-access.redis-key-prefix",()->PREFIX);
        registry.add("app.user.public-query.redis-key-prefix",()->PREFIX);
    }
    @AfterEach void cleanup() throws Exception {
        try(var minio=minio()){for(String key:keys)minio.removeObject(RemoveObjectArgs.builder().bucket(storage.bucket()).object(key).build());}
        var quota=redis.keys(PREFIX+"*"); if(quota!=null && !quota.isEmpty())redis.delete(quota);
        for(String owner:owners){jdbc.update("DELETE FROM media_asset WHERE owner_id=?",owner);jdbc.update("DELETE FROM app_user_role WHERE user_id=?",owner);users.deleteById(owner);}
    }

    @Test void anonymousStableAvatarRedirectsToTheBoundReadyObjectAndItsBytesAreReadable() throws Exception {
        var owner=owner(); var avatar=ready(owner,true);
        var profile=get("/api/v1/users/"+owner.getId(),null);
        assertEquals(200,profile.statusCode(),profile.body());
        String url=JsonPath.read(profile.body(),"$.data.avatarUrl"); assertNotNull(url);
        var response=get(url,null);
        assertEquals(302,response.statusCode(),response.body());
        assertEquals("",response.body()); assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("X-Request-Id").isPresent());
        String signed=response.headers().firstValue("Location").orElseThrow();
        var parsed=HttpUrl.get(signed); assertEquals("300",parsed.queryParameter("X-Amz-Expires"));
        assertTrue(parsed.encodedPath().endsWith(avatar.getReadyObjectKey()));
        assertFalse(parsed.encodedPath().endsWith("/source"));
        var bytes=http.send(HttpRequest.newBuilder(URI.create(signed)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,bytes.statusCode()); assertArrayEquals(png(),bytes.body());
        assertEquals("image/png",bytes.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("no-store",bytes.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test void aReadyButUnboundAvatarIsNotPublic() throws Exception {
        var owner=owner();var avatar=ready(owner,false);
        problem(get(path(owner,avatar),null),404,"RESOURCE_NOT_FOUND");
        verify(reader,never()).verify(any());
        assertNull(JsonPath.read(get("/api/v1/users/"+owner.getId(),null).body(),"$.data.avatarUrl"));
    }
    @Test void anotherOwnersAssetCannotBecomePublicThroughACorruptProfilePointer() throws Exception {
        var target=owner();var other=owner();var avatar=ready(other,true);
        jdbc.update("UPDATE app_user SET avatar_asset_id=? WHERE id=?",avatar.getId(),target.getId());
        problem(get(path(target,avatar),null),404,"RESOURCE_NOT_FOUND");
        verify(reader,never()).verify(any());
        assertNull(JsonPath.read(get("/api/v1/users/"+target.getId(),null).body(),"$.data.avatarUrl"));
    }
    @ParameterizedTest @ValueSource(strings={"CONTENT_IMAGE","CONTENT_VIDEO","REPORT_ATTACHMENT"})
    void otherPurposesAreNotExposedThroughTheAvatarRoute(String scene) throws Exception {
        var owner=owner();var avatar=ready(owner,true);jdbc.update("UPDATE media_asset SET scene=? WHERE id=?",scene,avatar.getId());
        problem(get(path(owner,avatar),null),404,"RESOURCE_NOT_FOUND");
        verify(reader,never()).verify(any());
    }
    @ParameterizedTest @ValueSource(strings={"WAITING_UPLOAD","PROCESSING","FAILED"})
    void nonReadyAvatarFailsLocallyAndTheProfileStillLoads(String state) throws Exception {
        var owner=owner();var avatar=ready(owner,true);jdbc.update("UPDATE media_asset SET state=? WHERE id=?",state,avatar.getId());
        problem(get(path(owner,avatar),null),409,"MEDIA_NOT_READY");
        var profile=get("/api/v1/users/"+owner.getId(),null);assertEquals(200,profile.statusCode());
        assertNull(JsonPath.read(profile.body(),"$.data.avatarUrl"));verify(reader,never()).verify(any());
    }
    @Test void replacementChangesTheStableUrlAndClearingPreventsIssuingNewCredentials() throws Exception {
        var owner=owner();var old=ready(owner,true);
        String oldUrl=JsonPath.read(get("/api/v1/users/"+owner.getId(),null).body(),"$.data.avatarUrl");
        assertEquals(302,get(oldUrl,null).statusCode());
        var current=ready(owner,true);
        String currentUrl=JsonPath.read(get("/api/v1/users/"+owner.getId(),null).body(),"$.data.avatarUrl");
        assertNotEquals(oldUrl,currentUrl);problem(get(oldUrl,null),404,"RESOURCE_NOT_FOUND");
        assertEquals(302,get(currentUrl,null).statusCode());
        jdbc.update("UPDATE app_user SET avatar_asset_id=NULL WHERE id=?",owner.getId());
        problem(get(path(owner,current),null),404,"RESOURCE_NOT_FOUND");
        assertNull(JsonPath.read(get("/api/v1/users/"+owner.getId(),null).body(),"$.data.avatarUrl"));
    }
    @ParameterizedTest @ValueSource(strings={"ACTIVE","WRITE_BLOCKED","DISABLED"})
    void theTargetsAccountRestrictionDoesNotHideItsPublicAvatar(String state) throws Exception {
        var owner=owner();var avatar=ready(owner,true);jdbc.update("UPDATE app_user SET account_status=? WHERE id=?",state,owner.getId());
        assertEquals(302,get(path(owner,avatar),null).statusCode());
    }
    @ParameterizedTest @ValueSource(strings={"MALFORMED","UNKNOWN","DISABLED"})
    void suppliedInvalidIdentityIsRejectedInsteadOfSilentlyBecomingAnonymous(String scenario) throws Exception {
        var target=owner();var avatar=ready(target,true);String token;
        if(scenario.equals("DISABLED")) {
            var viewer=owner();token=sessions.create(viewer.getId(),1,"avatar-test").accessToken();
            jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?",viewer.getId());
        } else token=scenario.equals("MALFORMED")?"bad":"0".repeat(43);
        var response=get(path(target,avatar),token);problem(response,401,"AUTHENTICATION_REQUIRED");
        assertEquals("Bearer",response.headers().firstValue("WWW-Authenticate").orElseThrow());
    }
    @Test void writeBlockedViewerCanReadThePublicAvatar() throws Exception {
        var target=owner();var avatar=ready(target,true);var viewer=owner();
        String token=sessions.create(viewer.getId(),1,"avatar-test").accessToken();
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?",viewer.getId());
        assertEquals(302,get(path(target,avatar),token).statusCode());
    }
    @ParameterizedTest @ValueSource(strings={"SOURCE","VIEWER"})
    void independentQuotasReturn429AndDoNotBlockTheProfile(String scope) throws Exception {
        var target=owner();var avatar=ready(target,true);String token=null;
        if(scope.equals("SOURCE"))redis.opsForValue().set(PREFIX+"{user-avatar-access}:source:"+TokenDigest.sha256("127.0.0.1"),"120",Duration.ofMinutes(1));
        else {
            var viewer=owner();token=sessions.create(viewer.getId(),1,"avatar-test").accessToken();
            redis.opsForValue().set(PREFIX+"{user-avatar-access}:user:"+TokenDigest.sha256(viewer.getId()),"60",Duration.ofMinutes(1));
        }
        var response=get(path(target,avatar),token);problem(response,429,"RATE_LIMITED");
        assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow())>0);
        verify(reader,never()).verify(any());
        assertEquals(200,get("/api/v1/users/"+target.getId(),null).statusCode());
    }
    @Test void aFullProfileQuotaDoesNotConsumeTheIndependentImageQuota() throws Exception {
        var target=owner();var avatar=ready(target,true);
        redis.opsForValue().set(PREFIX+"{user-public-query}:source:"+TokenDigest.sha256("127.0.0.1"),"120",Duration.ofMinutes(1));
        assertEquals(302,get(path(target,avatar),null).statusCode());
    }
    @Test void storageFailureIsLocalToTheImageRequest() throws Exception {
        var target=owner();var avatar=ready(target,true);
        doThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"媒体存储暂不可用")).when(reader).verify(any());
        assertEquals(200,get("/api/v1/users/"+target.getId(),null).statusCode());
        verify(reader,never()).verify(any());
        problem(get(path(target,avatar),null),503,"DEPENDENCY_UNAVAILABLE");
    }
    @Test void missingReadyObjectDoesNotFallBackToAnExistingSourceObject() throws Exception {
        var target=owner();var avatar=ready(target,true);byte[] bytes=png();keys.add(avatar.getStorageKey());
        try(var minio=minio()) {
            minio.removeObject(RemoveObjectArgs.builder().bucket(storage.bucket()).object(avatar.getReadyObjectKey()).build());
            minio.putObject(PutObjectArgs.builder().bucket(storage.bucket()).object(avatar.getStorageKey()).contentType("image/png")
                    .stream(new ByteArrayInputStream(bytes),(long)bytes.length,-1L).build());
        }
        problem(get(path(target,avatar),null),503,"DEPENDENCY_UNAVAILABLE");
        assertEquals(200,get("/api/v1/users/"+target.getId(),null).statusCode());
    }
    @Test void changedReadyBytesCannotBeSignedUsingTheOldObjectProof() throws Exception {
        var target=owner();var avatar=ready(target,true);byte[] changed=png();changed[changed.length/2]^=1;
        try(var minio=minio()) { minio.putObject(PutObjectArgs.builder().bucket(storage.bucket()).object(avatar.getReadyObjectKey()).contentType("image/png")
                .stream(new ByteArrayInputStream(changed),(long)changed.length,-1L).build()); }
        problem(get(path(target,avatar),null),503,"DEPENDENCY_UNAVAILABLE");
    }
    @ParameterizedTest @ValueSource(strings={"TYPE","SIZE","KEY","STORAGE"})
    void invalidReadyMetadataNeverProducesALocationOrAStorageRequest(String scenario) throws Exception {
        var target=owner();var avatar=ready(target,true);
        switch(scenario) {
            case "TYPE"->jdbc.update("UPDATE media_asset SET actual_content_type='text/html' WHERE id=?",avatar.getId());
            case "SIZE"->jdbc.update("UPDATE media_asset SET actual_size_bytes=0 WHERE id=?",avatar.getId());
            case "KEY"->jdbc.update("UPDATE media_asset SET ready_object_key=storage_key WHERE id=?",avatar.getId());
            default->jdbc.update("UPDATE media_asset SET ready_storage_id=? WHERE id=?","0".repeat(64),avatar.getId());
        }
        problem(get(path(target,avatar),null),503,"DEPENDENCY_UNAVAILABLE");verify(reader,never()).verify(any());
        assertEquals(200,get("/api/v1/users/"+target.getId(),null).statusCode());
    }
    @Test void bindingIsRecheckedAfterHeadAndStorageIoHasNoDatabaseTransaction() throws Exception {
        var target=owner();var avatar=ready(target,true);
        doAnswer(invocation->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            invocation.callRealMethod();jdbc.update("UPDATE app_user SET avatar_asset_id=NULL WHERE id=?",target.getId());return null;
        }).when(reader).verify(any());
        problem(get(path(target,avatar),null),404,"RESOURCE_NOT_FOUND");
    }
    @Test void changedObjectBindingDuringHeadReturns503WithoutIssuingACrossBindingUrl() throws Exception {
        var target=owner();var avatar=ready(target,true);
        doAnswer(invocation->{invocation.callRealMethod();jdbc.update("UPDATE media_asset SET ready_object_etag='changed' WHERE id=?",avatar.getId());return null;}).when(reader).verify(any());
        problem(get(path(target,avatar),null),503,"DEPENDENCY_UNAVAILABLE");
    }
    @Test void unrelatedProfileChangesDoNotInvalidateTheUnchangedAvatarBinding() throws Exception {
        var target=owner();var avatar=ready(target,true);
        doAnswer(invocation->{invocation.callRealMethod();jdbc.update("UPDATE app_user SET bio='updated',version=version+1 WHERE id=?",target.getId());return null;}).when(reader).verify(any());
        assertEquals(302,get(path(target,avatar),null).statusCode());
    }
    @ParameterizedTest @ValueSource(strings={"MISSING","DUPLICATE","EXTRA","BAD_ASSET","BAD_USER"})
    void malformedOrInvalidParametersNeverReachStorage(String scenario) throws Exception {
        var target=owner();var avatar=ready(target,true);String base="/api/v1/users/"+target.getId()+"/avatar";
        String request=switch(scenario){
            case "MISSING"->base;
            case "DUPLICATE"->path(target,avatar)+"&assetId="+avatar.getId();
            case "EXTRA"->path(target,avatar)+"&ownerId=someone";
            case "BAD_ASSET"->base+"?assetId=bad!";
            default->"/api/v1/users/bad!/avatar?assetId="+avatar.getId();
        };
        boolean invalid=scenario.startsWith("BAD");problem(get(request,null),invalid?422:400,invalid?"VALIDATION_ERROR":"MALFORMED_REQUEST");
        verify(reader,never()).verify(any());
    }
    @Test void generatedOpenApiDocumentsTheRedirectAndOptionalAuthentication() throws Exception {
        var response=get("/v3/api-docs",null);assertEquals(200,response.statusCode());
        Map<String,Object> operation=JsonPath.read(response.body(),"$.paths['/api/v1/users/{userId}/avatar'].get");
        assertEquals("getPublicUserAvatar",operation.get("operationId"));
        assertEquals(List.of(Map.of(),Map.of("bearerAuth",List.of())),operation.get("security"));
        Map<String,Object> responses=(Map<String,Object>)operation.get("responses");
        assertEquals(Set.of("302","400","401","404","409","422","429","500","503"),responses.keySet());
        var success=(Map<String,Object>)responses.get("302");
        assertTrue(((Map<?,?>)success.get("headers")).keySet().containsAll(Set.of("Location","Cache-Control","X-Request-Id")));
    }
    private void problem(HttpResponse<String> response,int status,String code) {
        assertEquals(status,response.statusCode(),response.body());assertEquals(code,JsonPath.read(response.body(),"$.code"));
        assertTrue(response.headers().firstValue("Location").isEmpty());
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
    }

    private UserAccount owner() {
        var owner=LoginTestSupport.account(); owner.setId(id()); owner.setEmail("avatar-read-"+owner.getId()+"@example.test");
        owner.setEmailNormalized(owner.getEmail()); owner.setAvatarAssetId(null); owners.add(owner.getId());
        users.insert(owner);roles.insertDefaultRole(owner.getId(),owner.getCreatedAt());return owner;
    }
    private MediaAsset ready(UserAccount owner,boolean bind) throws Exception {
        byte[] png=png(); var asset=new MediaAsset(); asset.setId(id());asset.setOwnerId(owner.getId());
        asset.setScene("AVATAR");asset.setState("READY");asset.setFileName("avatar.png");asset.setDeclaredContentType("image/png");
        asset.setDeclaredSizeBytes((long)png.length);asset.setStorageKey("private/"+owner.getId()+"/"+asset.getId()+"/source");
        asset.setActualContentType("image/png");asset.setActualSizeBytes((long)png.length);asset.setWidth(2);asset.setHeight(3);
        asset.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        asset.setReadyObjectKey("private/"+owner.getId()+"/"+asset.getId()+"/ready/"+id());keys.add(asset.getReadyObjectKey());
        try(var minio=minio()) {
            var result=minio.putObject(PutObjectArgs.builder().bucket(storage.bucket()).object(asset.getReadyObjectKey()).contentType("image/png")
                    .stream(new ByteArrayInputStream(png),(long)png.length,-1L).build());asset.setReadyObjectEtag(result.etag());
        }
        asset.setReadyStorageId(storage.identity());assets.insert(asset);
        if(bind)jdbc.update("UPDATE app_user SET avatar_asset_id=? WHERE id=?",asset.getId(),owner.getId());return asset;
    }
    private byte[] png() throws Exception {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,3,BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}
    private MinioClient minio(){return MinioClient.builder().endpoint(storage.endpoint()).region(storage.region()).credentials(storage.accessKey(),storage.secretKey()).build();}
    private HttpResponse<String> get(String path,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(path.startsWith("http")?path:"http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(path.equals("/v3/api-docs")?45:15)).GET();
        if(token!=null)request.header("Authorization","Bearer "+token);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String path(UserAccount owner,MediaAsset avatar){return "/api/v1/users/"+owner.getId()+"/avatar?assetId="+avatar.getId();}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
}
