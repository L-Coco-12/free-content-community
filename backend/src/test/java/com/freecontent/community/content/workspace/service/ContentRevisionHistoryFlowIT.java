package com.freecontent.community.content.workspace.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.mapper.ContentReadMapper;
import org.apache.ibatis.session.SqlSession;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.math.BigInteger;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.TransientDataAccessResourceException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "app.auth.email-challenge.delivery-enabled=false","app.media.upload-cleanup.enabled=false","app.media.processing.enabled=false"})
class ContentRevisionHistoryFlowIT {
    private static final String PREFIX="test:revision-history:"+UUID.randomUUID()+":";
    private static final LocalDateTime BASE=LocalDateTime.of(2026,11,3,8,0);
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountMapper users;
    @Autowired UserRoleMapper roles;
    @Autowired RedisSessionStore sessions;
    @Autowired StringRedisTemplate redis;
    @Autowired SqlSession sql;
    @MockitoSpyBean ContentReadMapper contents;
    @MockitoSpyBean SessionAuthenticationService authentication;
    private final List<String> owners=new ArrayList<>();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry){for(String group:List.of("auth.login","content.query"))registry.add("app."+group+".redis-key-prefix",()->PREFIX);}
    @AfterEach void cleanup(){
        reset(contents,authentication);
        var keys=redis.keys(PREFIX+"*");if(keys!=null && !keys.isEmpty())redis.delete(keys);
        for(String owner:owners)ContentTestSupport.clearWorkspace(jdbc,owner);
        for(String owner:owners){jdbc.update("DELETE FROM community_tag WHERE created_by=?",owner);jdbc.update("DELETE FROM media_asset WHERE owner_id=?",owner);jdbc.update("DELETE FROM app_user_role WHERE user_id=?",owner);users.deleteById(owner);}
    }
    @Test void paginatesRevisionHistoryByNumberAndReturnsCompleteSnapshots() throws Exception {
        var owner=owner();String content=content(owner),token=token(owner);List<String> revisions=new ArrayList<>();
        var states=List.of("APPROVED","REJECTED","CANCELLED","APPROVED","DRAFT");
        for(int i=0;i<states.size();i++)revisions.add(revision(content,i+1,states.get(i)));
        revision(content(owner),1,"DRAFT");revision(content(owner()),1,"DRAFT");
        String cover=asset(owner),imageOne=asset(owner),imageTwo=asset(owner),tagOne=tag(owner),tagTwo=tag(owner);
        String body="正文3\n\n![one](asset:"+imageOne+")\n![two](asset:"+imageTwo+")";
        jdbc.update("UPDATE content_revision SET body_markdown=? WHERE id=?",body,revisions.get(2));
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0),(?,?,'IMAGE',2),(?,?,'IMAGE',0)",revisions.get(2),cover,revisions.get(2),imageTwo,revisions.get(2),imageOne);
        jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,1),(?,?,0)",revisions.get(2),tagTwo,revisions.get(2),tagOne);
        var first=get(content,"pageSize=2",token);ok(first);assertEquals(List.of(revisions.get(4),revisions.get(3)),ids(first));
        assertEquals(5,JsonPath.<Integer>read(first.body(),"$.data.total"));assertEquals(1,JsonPath.<Integer>read(first.body(),"$.data.page"));
        assertEquals(2,JsonPath.<Integer>read(first.body(),"$.data.pageSize"));
        var second=get(content,"page=2&pageSize=2",token);ok(second);assertEquals(List.of(revisions.get(2),revisions.get(1)),ids(second));
        Map<String,Object> item=JsonPath.read(second.body(),"$.data.items[0]");
        assertEquals(Set.of("id","contentId","revisionNumber","status","title","bodyMarkdown","coverAssetId","imageAssetIds","videoAssetId","tagIds","createdAt","updatedAt"),item.keySet());
        assertEquals(content,item.get("contentId"));assertEquals("CANCELLED",item.get("status"));assertEquals("标题3",item.get("title"));assertEquals(body,item.get("bodyMarkdown"));
        assertEquals(cover,item.get("coverAssetId"));assertEquals(List.of(imageOne,imageTwo),item.get("imageAssetIds"));assertEquals(List.of(tagOne,tagTwo),item.get("tagIds"));assertNull(item.get("videoAssetId"));
        var last=get(content,"page=3&pageSize=2",token);ok(last);assertEquals(List.of(revisions.get(0)),ids(last));
    }
    @Test
    void detailReturnsTheRequestedHistoricalRevisionAndItsOwnReferences() throws Exception {
        var owner = owner();
        String content = content(owner);
        String historical = revision(content, 1, "REJECTED");
        String draft = revision(content, 2, "DRAFT");
        String cover = asset(owner);
        String image = asset(owner);
        String label = tag(owner);
        String markdown = "历史正文\n\n![配图](asset:" + image + ")";
        jdbc.update("UPDATE community_content SET working_revision_id=? WHERE id=?", draft, content);
        jdbc.update("UPDATE content_revision SET body_markdown=? WHERE id=?", markdown, historical);
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0),(?,?,'IMAGE',0)",
                historical, cover, historical, image);
        jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,0)", historical, label);

        var response = getRevision(content, historical, "", token(owner));

        ok(response);
        Map<String, Object> data = JsonPath.read(response.body(), "$.data");
        assertEquals(historical, data.get("id"));
        assertEquals(content, data.get("contentId"));
        assertEquals(1, data.get("revisionNumber"));
        assertEquals("REJECTED", data.get("status"));
        assertEquals("标题1", data.get("title"));
        assertEquals(markdown, data.get("bodyMarkdown"));
        assertEquals(cover, data.get("coverAssetId"));
        assertEquals(List.of(image), data.get("imageAssetIds"));
        assertEquals(List.of(label), data.get("tagIds"));
        assertTrue(data.containsKey("videoAssetId"));
        assertNull(data.get("videoAssetId"));
        assertEquals(BASE.minusSeconds(1).toInstant(ZoneOffset.UTC).toString(), data.get("createdAt"));
        assertEquals(BASE.toInstant(ZoneOffset.UTC).toString(), data.get("updatedAt"));
    }

    @Test
    void detailReturns404ForUnknownForeignOrMismatchedRevisions() throws Exception {
        var owner = owner();
        String content = content(owner);
        String revision = revision(content, 1, "DRAFT");
        String otherContent = content(owner);
        var otherUser = owner();
        jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,'ADMIN',UTC_TIMESTAMP(6))", otherUser.getId());
        String ownerToken = token(owner);

        problem(getRevision(content, id(), "", ownerToken), 404, "RESOURCE_NOT_FOUND");
        problem(getRevision(otherContent, revision, "", ownerToken), 404, "RESOURCE_NOT_FOUND");
        clearInvocations(contents);
        problem(getRevision(content, revision, "", token(otherUser)), 404, "RESOURCE_NOT_FOUND");
        verify(contents, never()).findRevision(anyString(), anyString());
        verify(contents, never()).findAssets(anyString());
    }

    @Test
    void detailReturnsTheDraftInTheOwnersRecycleBin() throws Exception {
        var owner = owner();
        String content = content(owner);
        String revision = revision(content, 1, "DRAFT");
        jdbc.update("UPDATE community_content SET lifecycle='DELETED',deleted_at=UTC_TIMESTAMP(6) WHERE id=?", content);

        var response = getRevision(content, revision, "", token(owner));

        ok(response);
        assertEquals("DRAFT", JsonPath.read(response.body(), "$.data.status"));
        assertEquals(revision, JsonPath.read(response.body(), "$.data.id"));
    }

    @Test
    void detailRechecksTheAuthenticationVersionBeforeReadingTheRevision() throws Exception {
        var owner = owner();
        String content = content(owner);
        String revision = revision(content, 1, "DRAFT");
        String token = token(owner);
        doAnswer(call -> {
            var principal = call.callRealMethod();
            jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", owner.getId());
            return principal;
        }).when(authentication).authenticate(token);

        problem(getRevision(content, revision, "", token), 401, "AUTHENTICATION_REQUIRED");
        verify(contents, never()).findOwned(anyString(), anyString());
    }

    @Test
    void detailRejectsExtraInputBeforeConsumingQuota() throws Exception {
        String token = token(owner());
        problem(getRevision("content", "revision", "", null), 401, "AUTHENTICATION_REQUIRED");
        problem(getRevision("bad!", "revision", "", token), 422, "VALIDATION_ERROR");
        var invalidRevision = getRevision("content", "bad!", "", token);
        problem(invalidRevision, 422, "VALIDATION_ERROR");
        assertEquals("revisionId", JsonPath.read(invalidRevision.body(), "$.violations[0].path"));
        problem(getRevision("content", "revision", "?page=1", token), 400, "MALFORMED_REQUEST");
        var request = HttpRequest.newBuilder(revisionUri("content", "revision", ""))
                .header("Authorization", "Bearer " + token)
                .method("GET", HttpRequest.BodyPublishers.ofString("{}")).build();
        problem(http.send(request, HttpResponse.BodyHandlers.ofString()), 400, "MALFORMED_REQUEST");
        assertNull(redis.opsForValue().get(sourceKey()));
    }

    @Test
    void detailUsesTheSharedContentQueryBudget() throws Exception {
        var owner = owner();
        String content = content(owner);
        String revision = revision(content, 1, "DRAFT");
        String token = token(owner);
        redis.opsForValue().set(userKey(owner), "59", Duration.ofSeconds(40));

        ok(get(content, "", token));
        var limited = getRevision(content, revision, "", token);

        problem(limited, 429, "RATE_LIMITED");
        assertTrue(Integer.parseInt(limited.headers().firstValue("Retry-After").orElseThrow()) > 0);
        verify(contents, never()).findRevision(anyString(), anyString());
    }

    @Test
    void detailReturns503WhenRevisionReferencesCannotBeRead() throws Exception {
        var owner = owner();
        String content = content(owner);
        String revision = revision(content, 1, "DRAFT");
        doThrow(new TransientDataAccessResourceException("private SQL detail")).when(contents).findTagIds(revision);

        var response = getRevision(content, revision, "", token(owner));

        problem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("private SQL detail"));
    }

    @ParameterizedTest @ValueSource(strings={"DRAFT","IN_REVIEW","APPROVED","REJECTED","CANCELLED"})
    void everyRevisionStateIsReadableAndDraftTextCanBeEmpty(String state) throws Exception {
        var owner=owner();String content=content(owner),revision=revision(content,1,state);
        if(state.equals("DRAFT"))jdbc.update("UPDATE content_revision SET title='',body_markdown='' WHERE id=?",revision);
        var response=get(content,"",token(owner));ok(response);assertEquals(List.of(revision),ids(response));assertEquals(state,JsonPath.read(response.body(),"$.data.items[0].status"));
        assertNull(JsonPath.read(response.body(),"$.data.items[0].coverAssetId"));assertNull(JsonPath.read(response.body(),"$.data.items[0].videoAssetId"));
        assertEquals(List.of(),JsonPath.read(response.body(),"$.data.items[0].imageAssetIds"));assertEquals(List.of(),JsonPath.read(response.body(),"$.data.items[0].tagIds"));
        if(state.equals("DRAFT")){assertEquals("",JsonPath.read(response.body(),"$.data.items[0].title"));assertEquals("",JsonPath.read(response.body(),"$.data.items[0].bodyMarkdown"));}
    }
    @ParameterizedTest @ValueSource(strings={"DELETED","WITHDRAWN","TAKEN_DOWN"})
    void retainedHistoryRemainsReadableRegardlessOfPublicationOrLifecycle(String state) throws Exception {
        var owner=owner();String content=content(owner),revision=revision(content,1,"REJECTED");
        if(state.equals("DELETED"))jdbc.update("UPDATE community_content SET lifecycle='DELETED',deleted_at=UTC_TIMESTAMP(6) WHERE id=?",content);
        else jdbc.update("UPDATE community_content SET publication_status=? WHERE id=?",state,content);
        var response=get(content,"",token(owner));ok(response);assertEquals(List.of(revision),ids(response));
    }
    @ParameterizedTest @ValueSource(strings={"USER","ADMIN","MODERATOR"})
    void rolesNeverExpandTheOwnContentBoundary(String role) throws Exception {
        var owner=owner();String content=content(owner),revision=revision(content,1,"DRAFT");var viewer=owner();
        if(!role.equals("USER"))jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,?,UTC_TIMESTAMP(6))",viewer.getId(),role);
        String token=token(viewer);var foreign=get(content,"",token);problem(foreign,404,"RESOURCE_NOT_FOUND");assertFalse(foreign.body().contains(revision));
        problem(get(id(),"",token),404,"RESOURCE_NOT_FOUND");verify(contents,never()).countRevisions(anyString());verify(contents,never()).pageRevisions(anyString(),anyLong(),anyInt());
    }
    @ParameterizedTest @CsvSource({"AUTH_VERSION,401,AUTHENTICATION_REQUIRED","DISABLED,403,ACCOUNT_RESTRICTED","UNVERIFIED,403,PERMISSION_DENIED","NO_USER,403,PERMISSION_DENIED"})
    void accountAndCreatorEligibilityAreRecheckedAfterAuthentication(String change,int status,String code) throws Exception {
        var owner=owner();String content=content(owner);revision(content,1,"DRAFT");String token=token(owner);
        doAnswer(call->{var principal=call.callRealMethod();switch(change){
            case "AUTH_VERSION"->jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?",owner.getId());
            case "DISABLED"->jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?",owner.getId());
            case "UNVERIFIED"->jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?",owner.getId());
            default->jdbc.update("DELETE FROM app_user_role WHERE user_id=?",owner.getId());}
            return principal;}).when(authentication).authenticate(token);
        problem(get(content,"",token),status,code);verify(contents,never()).findOwned(anyString(),anyString());
    }
    @Test void writeBlockedAuthorsKeepReadAccessAndMissingAuthenticationIs401() throws Exception {
        var owner=owner();String content=content(owner),revision=revision(content,1,"DRAFT"),token=token(owner);
        problem(get(content,"",null),401,"AUTHENTICATION_REQUIRED");problem(get(content,"","invalid"),401,"AUTHENTICATION_REQUIRED");
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?",owner.getId());
        var response=get(content,"",token);ok(response);assertEquals(List.of(revision),ids(response));
    }
    @ParameterizedTest @CsvSource({"page=0,422,VALIDATION_ERROR","page=-1,422,VALIDATION_ERROR","pageSize=0,422,VALIDATION_ERROR","pageSize=101,422,VALIDATION_ERROR",
            "pageSize=999999999999999999999,422,VALIDATION_ERROR","page=one,400,MALFORMED_REQUEST","page=1.5,400,MALFORMED_REQUEST","page=,400,MALFORMED_REQUEST",
            "pageSize=,400,MALFORMED_REQUEST","page=1&page=2,400,MALFORMED_REQUEST","pageSize=1&pageSize=2,400,MALFORMED_REQUEST",
            "status=DRAFT,400,MALFORMED_REQUEST","userId=other,400,MALFORMED_REQUEST","cursor=x,400,MALFORMED_REQUEST"})
    void invalidQueryIsRejectedBeforeQuota(String query,int status,String code) throws Exception {
        problem(get("content",query,token(owner())),status,code);assertNull(redis.opsForValue().get(sourceKey()));
    }
    @Test void invalidIdsAndRequestBodyAreRejectedBeforeQuota() throws Exception {
        String token=token(owner());for(String invalid:List.of("bad!","a".repeat(65)))problem(get(invalid,"",token),422,"VALIDATION_ERROR");
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/creator/contents/content/revisions"))
                .header("Authorization","Bearer "+token).method("GET",HttpRequest.BodyPublishers.ofString("{}"));
        problem(http.send(request.build(),HttpResponse.BodyHandlers.ofString()),400,"MALFORMED_REQUEST");assertNull(redis.opsForValue().get(sourceKey()));
    }
    @Test void defaultsMaximumHugePagesAndZeroHistoryAreHandledWithoutOverflow() throws Exception {
        var owner=owner();String content=content(owner),empty=content(owner),token=token(owner);for(int i=1;i<=101;i++)revision(content,i,"APPROVED");
        var defaults=get(content,"",token);ok(defaults);assertEquals(20,ids(defaults).size());assertEquals(20,JsonPath.<Integer>read(defaults.body(),"$.data.pageSize"));
        var max=get(content,"pageSize=100",token);ok(max);assertEquals(100,ids(max).size());
        var last=get(content,"page=2&pageSize=100",token);ok(last);assertEquals(1,ids(last).size());assertEquals(101,JsonPath.<Integer>read(last.body(),"$.data.total"));
        clearInvocations(contents);String huge="99999999999999999999999999999999999999";
        var beyond=get(content,"page="+huge+"&pageSize=100",token);ok(beyond);assertTrue(ids(beyond).isEmpty());
        assertEquals(new BigInteger(huge),new BigInteger(JsonPath.read(beyond.body(),"$.data.page").toString()));
        var zero=get(empty,"",token);ok(zero);assertTrue(ids(zero).isEmpty());assertEquals(0,JsonPath.<Integer>read(zero.body(),"$.data.total"));
        verify(contents,never()).pageRevisions(anyString(),anyLong(),anyInt());verify(contents,never()).findAssetsForRevisions(anyList());verify(contents,never()).findTagsForRevisions(anyList());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void exhaustedSourceOrUserSharedQuotaIs429(boolean user) throws Exception {
        var owner=owner();String content=content(owner),token=token(owner);redis.opsForValue().set(user?userKey(owner):sourceKey(),user?"60":"120",Duration.ofSeconds(40));
        var response=get(content,"",token);problem(response,429,"RATE_LIMITED");assertTrue(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow())>0);
        verify(contents,never()).findOwned(anyString(),anyString());
    }
    @Test void historyWorkspaceAndCreatorListShareOneReadBudget() throws Exception {
        var owner=owner();String content=content(owner),token=token(owner);revision(content,1,"REJECTED");
        ok(get(content,"",token));
        for(String path:List.of("/api/v1/creator/contents/"+content,"/api/v1/creator/contents")){
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token).GET();
            assertEquals(200,http.send(request.build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertEquals("3",redis.opsForValue().get(sourceKey()));assertEquals("3",redis.opsForValue().get(userKey(owner)));
    }
    @Test void referenceReadsAreBatchedForOnlyTheCurrentPage() throws Exception {
        var owner=owner();String content=content(owner);for(int i=1;i<=12;i++)revision(content,i,"APPROVED");clearInvocations(contents);
        var response=get(content,"pageSize=10",token(owner));ok(response);assertEquals(10,ids(response).size());
        verify(contents,times(1)).findOwned(content,owner.getId());verify(contents,times(1)).countRevisions(content);verify(contents,times(1)).pageRevisions(content,0,10);
        verify(contents,times(1)).findAssetsForRevisions(argThat(ids->ids.size()==10));verify(contents,times(1)).findTagsForRevisions(argThat(ids->ids.size()==10));
        verify(contents,never()).findRevision(anyString(),anyString());verify(contents,never()).findRevisions(anyList(),anyList());verify(contents,never()).findAssets(anyString());verify(contents,never()).findTagIds(anyString());
    }
    @ParameterizedTest @ValueSource(strings={"OWNER","COUNT","PAGE","ASSETS","TAGS"})
    void failedReadDependenciesAre503RatherThanAnEmptyPage(String stage) throws Exception {
        var owner=owner();String content=content(owner);revision(content,1,"APPROVED");var failure=new TransientDataAccessResourceException("private SQL detail");
        switch(stage){
            case "OWNER"->doThrow(failure).when(contents).findOwned(anyString(),anyString());
            case "COUNT"->doThrow(failure).when(contents).countRevisions(anyString());
            case "PAGE"->doThrow(failure).when(contents).pageRevisions(anyString(),anyLong(),anyInt());
            case "ASSETS"->doThrow(failure).when(contents).findAssetsForRevisions(anyList());
            default->doThrow(failure).when(contents).findTagsForRevisions(anyList());
        }
        var response=get(content,"",token(owner));problem(response,503,"DEPENDENCY_UNAVAILABLE");assertFalse(response.body().contains("private SQL"));
    }
    @Test void corruptedScalarReferencesDoNotReturnAnIncompleteSnapshot() throws Exception {
        var owner=owner();String content=content(owner),revision=revision(content,1,"REJECTED"),first=asset(owner),second=asset(owner);
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0),(?,?,'COVER',1)",revision,first,revision,second);
        problem(get(content,"",token(owner)),503,"DEPENDENCY_UNAVAILABLE");
    }
    @Test void aPageAndItsTotalUseTheSameDatabaseSnapshotDuringNewRevisionCreation() throws Exception {
        var owner=owner();String content=content(owner),first=revision(content,1,"APPROVED"),token=token(owner);var once=new AtomicBoolean();var pool=Executors.newSingleThreadExecutor();
        try{
            doAnswer(call->{Long total=sql.selectOne(ContentReadMapper.class.getName()+".countRevisions",Map.of("content",call.getArgument(0)));
                if(once.compareAndSet(false,true))pool.submit(()->revision(content,2,"APPROVED")).get(5,TimeUnit.SECONDS);return total;
            }).when(contents).countRevisions(content);
            var before=get(content,"",token);ok(before);assertEquals(1,JsonPath.<Integer>read(before.body(),"$.data.total"));assertEquals(List.of(first),ids(before));
            var after=get(content,"",token);ok(after);assertEquals(2,JsonPath.<Integer>read(after.body(),"$.data.total"));assertEquals(2,ids(after).size());
        }finally{pool.shutdownNow();}
    }
    @Test void historyDoesNotDependOnCurrentWorkspacePointersAndSupportsVideoReferences() throws Exception {
        var owner=owner();String content=content(owner),revision=revision(content,1,"APPROVED"),video=asset(owner);
        jdbc.update("UPDATE community_content SET kind='VIDEO',publication_status='PUBLISHED' WHERE id=?",content);
        jdbc.update("UPDATE media_asset SET scene='CONTENT_VIDEO' WHERE id=?",video);
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'VIDEO',0)",revision,video);
        var response=get(content,"",token(owner));ok(response);assertEquals(video,JsonPath.read(response.body(),"$.data.items[0].videoAssetId"));
    }
    @Test void generatedRevisionPageSchemaAndAuthenticationMatchTheContract() throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v3/api-docs")).timeout(Duration.ofSeconds(45)).GET();
        var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());
        String operation="$.paths['/api/v1/creator/contents/{contentId}/revisions'].get";
        assertEquals("listMyRevisions",JsonPath.read(response.body(),operation+".operationId"));assertEquals(List.of(Map.of("bearerAuth",List.of())),JsonPath.read(response.body(),operation+".security"));
        var fields=Set.of("items","page","pageSize","total");
        assertEquals(fields,JsonPath.<Map<String,Object>>read(response.body(),"$.components.schemas.RevisionPage.properties").keySet());
        assertEquals(fields,new HashSet<>(JsonPath.<List<String>>read(response.body(),"$.components.schemas.RevisionPage.required")));
        assertEquals("#/components/schemas/Revision",JsonPath.read(response.body(),"$.components.schemas.RevisionPage.properties.items.items['$ref']"));
    }
    private String sourceKey(){return PREFIX+"{content-query}:source:"+TokenDigest.sha256("127.0.0.1");}
    private String userKey(UserAccount owner){return PREFIX+"{content-query}:user:"+TokenDigest.sha256(owner.getId());}
    private void problem(HttpResponse<String> response,int status,String code){assertEquals(status,response.statusCode(),response.body());assertEquals(code,JsonPath.read(response.body(),"$.code"));assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertTrue(response.headers().firstValue("X-Request-Id").isPresent());}
    private UserAccount owner(){var user=LoginTestSupport.account();user.setId(id());user.setEmail("revision-history-"+user.getId()+"@example.test");user.setEmailNormalized(user.getEmail());
        owners.add(user.getId());users.insert(user);roles.insertDefaultRole(user.getId(),user.getCreatedAt());return user;}
    private String content(UserAccount owner){String id=id();jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) VALUES(?,?,'ARTICLE','ACTIVE','UNPUBLISHED',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",id,owner.getId());return id;}
    private String revision(String content,int number,String status){String revision=id();jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",revision,content,number,status,"标题"+number,"正文"+number,BASE.minusSeconds(number),BASE);return revision;}
    private String asset(UserAccount owner){String asset=id();jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) VALUES(?,?,'CONTENT_IMAGE','old.png','image/png',1,?,'FAILED',UTC_TIMESTAMP(6))",asset,owner.getId(),"private/"+asset);return asset;}
    private String tag(UserAccount owner){String tag=id();jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) VALUES(?,?,'历史标签','DISABLED',1,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",tag,"history_"+tag.substring(0,20),owner.getId());return tag;}
    private String token(UserAccount owner){return sessions.create(owner.getId(),1,"revision-history").accessToken();}
    private HttpResponse<String> get(String content,String query,String token) throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/creator/contents/"+content+"/revisions"+(query.isEmpty()?"":"?"+query))).timeout(Duration.ofSeconds(20)).GET();
        if(token!=null)request.header("Authorization","Bearer "+token);return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private List<String> ids(HttpResponse<String> response){return JsonPath.read(response.body(),"$.data.items[*].id");}
    private HttpResponse<String> getRevision(String content, String revision, String query, String token) throws Exception {
        var request = HttpRequest.newBuilder(revisionUri(content, revision, query)).timeout(Duration.ofSeconds(20)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI revisionUri(String content, String revision, String query) {
        return URI.create("http://127.0.0.1:" + port + "/api/v1/creator/contents/" + content + "/revisions/" + revision + query);
    }

    private void ok(HttpResponse<String> response){assertEquals(200,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertTrue(response.headers().firstValue("X-Request-Id").isPresent());}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
}
