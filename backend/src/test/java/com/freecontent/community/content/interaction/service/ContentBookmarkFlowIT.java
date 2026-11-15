package com.freecontent.community.content.interaction.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.interaction.mapper.ContentBookmarkMapper;
import com.freecontent.community.content.mapper.ContentBookmarkReadMapper;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.TransientDataAccessResourceException;
import org.apache.ibatis.session.SqlSession;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "app.auth.email-challenge.delivery-enabled=false","app.media.processing.enabled=false","app.media.upload-cleanup.enabled=false"})
class ContentBookmarkFlowIT {
    private static final String PREFIX="test:content-bookmark:"+UUID.randomUUID()+":";
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountMapper users;
    @Autowired UserRoleMapper roles;
    @Autowired RedisSessionStore sessions;
    @Autowired StringRedisTemplate redis;
    @Autowired SqlSession sqlSession;
    @Autowired com.freecontent.community.content.service.read.ContentBookmarkReader bookmarkReader;
    @MockitoSpyBean ContentBookmarkMapper writes;
    @MockitoSpyBean ContentBookmarkReadMapper reads;
    @MockitoSpyBean SessionAuthenticationService authentication;
    private final List<String> owners=new ArrayList<>();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry) {
        for(String group:List.of("content.bookmark","content.like","content.public-query","user.public-query"))registry.add("app."+group+".redis-key-prefix",()->PREFIX);
    }
    @AfterEach void cleanup() {
        var keys=redis.keys(PREFIX+"*");if(keys!=null && !keys.isEmpty())redis.delete(keys);
        for(String owner:owners){ContentTestSupport.clearReviews(jdbc,owner);ContentTestSupport.clearWorkspace(jdbc,owner);}
        for(String owner:owners){jdbc.update("DELETE FROM community_tag WHERE created_by=?",owner);jdbc.update("DELETE FROM media_asset WHERE owner_id=?",owner);jdbc.update("DELETE FROM app_user_role WHERE user_id=?",owner);users.deleteById(owner);}
    }
    @Test void putCreatesOneStableBookmarkAndTheCurrentViewerCanReadItsFlag() throws Exception {
        var author=owner();var viewer=owner();var content=published(author);String token=token(viewer);
        var before=jdbc.queryForMap("SELECT version,updated_at FROM community_content WHERE id=?",content.id());
        var first=bookmark(content.id(),token);assertEquals(204,first.statusCode(),first.body());assertEquals("",first.body());
        assertEquals("no-store",first.headers().firstValue("Cache-Control").orElseThrow());
        var saved=jdbc.queryForMap("SELECT * FROM content_bookmark WHERE user_id=? AND content_id=?",viewer.getId(),content.id());
        assertTrue(((String)saved.get("id")).matches("[A-Za-z0-9_-]{1,64}"));
        for(int i=0;i<2;i++)assertEquals(204,bookmark(content.id(),token).statusCode());
        assertEquals(saved,jdbc.queryForMap("SELECT * FROM content_bookmark WHERE user_id=? AND content_id=?",viewer.getId(),content.id()));
        assertEquals(before,jdbc.queryForMap("SELECT version,updated_at FROM community_content WHERE id=?",content.id()));
        var detail=get("/api/v1/contents/"+content.id(),token);assertEquals(200,detail.statusCode(),detail.body());
        assertTrue(JsonPath.<Boolean>read(detail.body(),"$.data.viewerHasBookmarked"));
        var anonymous=get("/api/v1/contents/"+content.id(),null);assertFalse(JsonPath.<Boolean>read(anonymous.body(),"$.data.viewerHasBookmarked"));
        assertEquals("3",redis.opsForValue().get(PREFIX+"{content-bookmark}:user:"+TokenDigest.sha256(viewer.getId())));
    }

    @Test void missingOrInvalidIdentityCannotBookmark() throws Exception {
        var content=published(owner());problem(bookmark(content.id(),null),401,"AUTHENTICATION_REQUIRED");
        problem(bookmark(content.id(),"bad"),401,"AUTHENTICATION_REQUIRED");problem(bookmark(content.id(),"0".repeat(43)),401,"AUTHENTICATION_REQUIRED");empty(content);
    }
    @ParameterizedTest @ValueSource(strings={"WRITE_BLOCKED","DISABLED"})
    void restrictedAccountsCannotWriteBookmarks(String state) throws Exception {
        var content=published(owner());var actor=owner();String token=token(actor);
        jdbc.update("UPDATE app_user SET account_status=? WHERE id=?",state,actor.getId());
        problem(bookmark(content.id(),token),403,"ACCOUNT_RESTRICTED");empty(content);
    }
    @ParameterizedTest @ValueSource(strings={"USER","MODERATOR","ADMIN"})
    void activeUsersCanBookmarkTheirOwnPublicWorksWithoutCanCreate(String role) throws Exception {
        var actor=owner();var content=published(actor);jdbc.update("DELETE FROM app_user_role WHERE user_id=?",actor.getId());
        jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,?,UTC_TIMESTAMP(6))",actor.getId(),role);
        String token=token(actor);if(!role.equals("USER"))assertFalse(JsonPath.<Boolean>read(get("/api/v1/me",token).body(),"$.data.canCreate"));
        assertEquals(204,bookmark(content.id(),token).statusCode());assertEquals(1,count(content));
    }
    @ParameterizedTest @ValueSource(strings={"auth_version=auth_version+1","account_status='WRITE_BLOCKED'","email_verified=0"})
    void currentAccountIsRecheckedAfterAuthentication(String change) throws Exception {
        var content=published(owner());var actor=owner();String token=token(actor);
        doAnswer(call->{var principal=call.callRealMethod();jdbc.update("UPDATE app_user SET "+change+" WHERE id=?",actor.getId());return principal;}).when(authentication).authenticate(token);
        String code=change.startsWith("auth_version")?"AUTHENTICATION_REQUIRED":change.startsWith("account_status")?"ACCOUNT_RESTRICTED":"PERMISSION_DENIED";
        problem(bookmark(content.id(),token),code.equals("AUTHENTICATION_REQUIRED")?401:403,code);empty(content);
    }
    @ParameterizedTest @CsvSource({"ACTIVE,UNPUBLISHED","ACTIVE,WITHDRAWN","ACTIVE,TAKEN_DOWN","DELETED,PUBLISHED"})
    void hiddenWorksCannotBeNewlyBookmarked(String lifecycle,String publication) throws Exception {
        var content=published(owner());jdbc.update("UPDATE community_content SET lifecycle=?,publication_status=? WHERE id=?",lifecycle,publication,content.id());
        problem(bookmark(content.id(),token(owner())),404,"RESOURCE_NOT_FOUND");empty(content);
    }
    @Test void repeatedBookmarkStillChecksVisibilityAndDoesNotRemoveTheSavedFact() throws Exception {
        var content=published(owner());var actor=owner();String token=token(actor);assertEquals(204,bookmark(content.id(),token).statusCode());
        var saved=jdbc.queryForMap("SELECT * FROM content_bookmark WHERE user_id=? AND content_id=?",actor.getId(),content.id());
        jdbc.update("UPDATE community_content SET publication_status='WITHDRAWN' WHERE id=?",content.id());
        problem(bookmark(content.id(),token),404,"RESOURCE_NOT_FOUND");
        assertEquals(saved,jdbc.queryForMap("SELECT * FROM content_bookmark WHERE user_id=? AND content_id=?",actor.getId(),content.id()));
    }
    @Test void missingContentIs404() throws Exception {problem(bookmark(id(),token(owner())),404,"RESOURCE_NOT_FOUND");}
    @Test void anotherDraftUnderReviewDoesNotBlockBookmarkingTheApprovedPublication() throws Exception {
        var content=published(owner());String working=id();
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,2,'IN_REVIEW','审核中新稿','私有正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",working,content.id());
        String review=ContentTestSupport.pending(jdbc,content.id(),working);jdbc.update("UPDATE content_review SET purpose='UPDATE' WHERE id=?",review);
        jdbc.update("UPDATE community_content SET working_revision_id=?,active_review_id=? WHERE id=?",working,review,content.id());
        assertEquals(204,bookmark(content.id(),token(owner())).statusCode());assertEquals(1,count(content));
    }
    @ParameterizedTest @ValueSource(strings={"PROCESSING","OTHER_OWNER","WRONG_SCENE"})
    void invalidPublishedMediaMakesTheTargetUnavailable(String scenario) throws Exception {
        var author=owner();var viewer=owner();var content=published(author);String asset=id();String owner=scenario.equals("OTHER_OWNER")?viewer.getId():author.getId();
        jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) VALUES(?,?,?,'image.png','image/png',1,?,?,UTC_TIMESTAMP(6))",
                asset,owner,scenario.equals("WRONG_SCENE")?"AVATAR":"CONTENT_IMAGE","private/"+owner+"/"+asset+"/source",scenario.equals("PROCESSING")?"PROCESSING":"READY");
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'IMAGE',0)",content.revision(),asset);
        problem(bookmark(content.id(),token(viewer)),404,"RESOURCE_NOT_FOUND");empty(content);
    }
    @Test void disabledPublishedTagsAndRestrictedAuthorsRemainPublic() throws Exception {
        var author=owner();var content=published(author);String tag=tag(author,List.of(content));
        jdbc.update("UPDATE community_tag SET status='DISABLED' WHERE id=?",tag);jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?",author.getId());
        assertEquals(204,bookmark(content.id(),token(owner())).statusCode());assertEquals(1,count(content));
    }
    @Test void bookmarksBelongToTheContentAndSurviveANewPublishedRevision() throws Exception {
        var author=owner();var content=published(author);var actor=owner();String token=token(actor);assertEquals(204,bookmark(content.id(),token).statusCode());
        var saved=jdbc.queryForMap("SELECT * FROM content_bookmark WHERE user_id=? AND content_id=?",actor.getId(),content.id());
        String revision=id();jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,2,'APPROVED','新公开稿','新正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",revision,content.id());
        jdbc.update("UPDATE community_content SET published_revision_id=?,published_at=UTC_TIMESTAMP(6),version=version+1 WHERE id=?",revision,content.id());
        assertEquals(204,bookmark(content.id(),token).statusCode());
        assertEquals(saved,jdbc.queryForMap("SELECT * FROM content_bookmark WHERE user_id=? AND content_id=?",actor.getId(),content.id()));
        assertTrue(JsonPath.<Boolean>read(get("/api/v1/contents/"+content.id(),token).body(),"$.data.viewerHasBookmarked"));
    }
    @Test void simultaneousPutsProduceOneRelationship() throws Exception {
        var content=published(owner());String token=token(owner());var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);
        try{
            var futures=new ArrayList<Future<Integer>>();for(int i=0;i<12;i++)futures.add(pool.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));return bookmark(content.id(),token).statusCode();}));
            start.countDown();for(var result:futures)assertEquals(204,result.get(20,TimeUnit.SECONDS));
        }finally{start.countDown();pool.shutdownNow();}
        assertEquals(1,count(content));
    }
    @Test void differentUsersMaySaveTheSameContent() throws Exception {
        var content=published(owner());String first=token(owner()),second=token(owner());
        assertEquals(204,bookmark(content.id(),first).statusCode());assertEquals(204,bookmark(content.id(),second).statusCode());assertEquals(2,count(content));
    }
    @Test void failureAfterARealInsertRollsBackTheBookmark() throws Exception {
        var content=published(owner());String token=token(owner());
        doAnswer(call->{realInsert(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3));throw new TransientDataAccessResourceException("private SQL detail");})
                .when(writes).insert(anyString(),anyString(),anyString(),any());
        var response=bookmark(content.id(),token);problem(response,503,"DEPENDENCY_UNAVAILABLE");assertFalse(response.body().contains("private SQL"));empty(content);
        doAnswer(call->realInsert(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3))).when(writes).insert(anyString(),anyString(),anyString(),any());
        assertEquals(204,bookmark(content.id(),token).statusCode());assertEquals(1,count(content));
    }
    @Test void anUnrelatedPrimaryKeyCollisionIsNotMistakenForAnExistingBookmark() throws Exception {
        var author=owner();var first=published(author);var second=published(author);var actor=owner();String token=token(actor);
        assertEquals(204,bookmark(first.id(),token).statusCode());String used=jdbc.queryForObject("SELECT id FROM content_bookmark WHERE user_id=? AND content_id=?",String.class,actor.getId(),first.id());
        doAnswer(call->realInsert(used,call.getArgument(1),call.getArgument(2),call.getArgument(3))).when(writes).insert(anyString(),anyString(),anyString(),any());
        problem(bookmark(second.id(),token),503,"DEPENDENCY_UNAVAILABLE");assertEquals(1,count(first));empty(second);
    }
    @Test void contentLockOrdersBookmarkBeforeAConcurrentWithdrawal() throws Exception {
        var content=published(owner());String token=token(owner());var pool=Executors.newSingleThreadExecutor();var entered=new CountDownLatch(1);
        var withdrawal=new java.util.concurrent.atomic.AtomicReference<Future<Integer>>();
        doAnswer(call->{
            withdrawal.set(pool.submit(()->{entered.countDown();return jdbc.update("UPDATE community_content SET publication_status='WITHDRAWN' WHERE id=?",content.id());}));
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->withdrawal.get().get(200,TimeUnit.MILLISECONDS));
            return realInsert(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3));
        }).when(writes).insert(anyString(),anyString(),anyString(),any());
        try{assertEquals(204,bookmark(content.id(),token).statusCode());assertEquals(1,withdrawal.get().get(5,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
        assertEquals(1,count(content));problem(bookmark(content.id(),token),404,"RESOURCE_NOT_FOUND");
    }
    @ParameterizedTest @ValueSource(strings={"SOURCE","USER"})
    void eitherIndependentQuotaCanRejectBeforeWriting(String scope) throws Exception {
        var content=published(owner());var actor=owner();String token=token(actor);
        String key=PREFIX+"{content-bookmark}:"+(scope.equals("USER")?"user:"+TokenDigest.sha256(actor.getId()):"source:"+TokenDigest.sha256("127.0.0.1"));
        redis.opsForValue().set(key,scope.equals("USER")?"60":"120",Duration.ofMinutes(1));
        var response=bookmark(content.id(),token);problem(response,429,"RATE_LIMITED");assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow())>0);empty(content);
        assertEquals(200,get("/api/v1/contents/"+content.id(),token).statusCode());
    }
    @Test void likeAndBookmarkQuotasAreIndependent() throws Exception {
        var content=published(owner());var actor=owner();String token=token(actor);
        redis.opsForValue().set(PREFIX+"{content-like}:source:"+TokenDigest.sha256("127.0.0.1"),"120",Duration.ofMinutes(1));
        assertEquals(204,bookmark(content.id(),token).statusCode());
        redis.delete(PREFIX+"{content-like}:source:"+TokenDigest.sha256("127.0.0.1"));
        redis.opsForValue().set(PREFIX+"{content-bookmark}:source:"+TokenDigest.sha256("127.0.0.1"),"120",Duration.ofMinutes(1));
        assertEquals(204,send("PUT","/api/v1/contents/"+content.id()+"/likes/me",token).statusCode());
    }
    @Test void listsBatchOnlyTheCurrentViewersBookmarksAndGuestsDoNotQueryThem() throws Exception {
        var author=owner();var first=published(author);var second=published(author);String tag=tag(author,List.of(first,second));
        var a=owner();var b=owner();String at=token(a),bt=token(b);assertEquals(204,bookmark(first.id(),at).statusCode());assertEquals(204,bookmark(second.id(),bt).statusCode());
        for(String path:List.of("/api/v1/contents?tagId="+tag+"&limit=10","/api/v1/users/"+author.getId()+"/contents?limit=10")){
            clearInvocations(reads);var response=get(path,at);assertEquals(200,response.statusCode(),response.body());
            List<Map<String,Object>> items=JsonPath.read(response.body(),"$.data.items");assertEquals(2,items.size());
            for(var item:items)assertEquals(item.get("id").equals(first.id()),item.get("viewerHasBookmarked"));
            verify(reads,times(1)).find(argThat(ids->new HashSet<>(ids).equals(Set.of(first.id(),second.id()))),eq(a.getId()));
            clearInvocations(reads);var anonymous=get(path,null);assertEquals(200,anonymous.statusCode());
            assertEquals(List.of(false,false),JsonPath.<List<Boolean>>read(anonymous.body(),"$.data.items[*].viewerHasBookmarked"));verify(reads,never()).find(anyList(),anyString());
        }
    }
    @Test void bookmarkAndLikeFlagsRemainIndependent() throws Exception {
        var content=published(owner());String token=token(owner());assertEquals(204,bookmark(content.id(),token).statusCode());
        var initial=get("/api/v1/contents/"+content.id(),token);assertTrue(JsonPath.<Boolean>read(initial.body(),"$.data.viewerHasBookmarked"));assertFalse(JsonPath.<Boolean>read(initial.body(),"$.data.viewerHasLiked"));
        assertEquals(204,send("PUT","/api/v1/contents/"+content.id()+"/likes/me",token).statusCode());
        assertEquals(204,send("DELETE","/api/v1/contents/"+content.id()+"/likes/me",token).statusCode());
        var after=get("/api/v1/contents/"+content.id(),token);assertTrue(JsonPath.<Boolean>read(after.body(),"$.data.viewerHasBookmarked"));assertFalse(JsonPath.<Boolean>read(after.body(),"$.data.viewerHasLiked"));assertEquals(1,count(content));
    }
    @Test void bookmarkReadFailureIs503InsteadOfFalseOrAnEmptyPage() throws Exception {
        var author=owner();var content=published(author);String token=token(owner());
        doThrow(new TransientDataAccessResourceException("private bookmark read")).when(reads).find(anyList(),anyString());
        problem(get("/api/v1/contents/"+content.id(),token),503,"DEPENDENCY_UNAVAILABLE");
        problem(get("/api/v1/users/"+author.getId()+"/contents",token),503,"DEPENDENCY_UNAVAILABLE");
        assertEquals(200,get("/api/v1/contents/"+content.id(),null).statusCode());
    }
    @Test void invalidInputIsRejectedWithoutWriting() throws Exception {
        var content=published(owner());String token=token(owner());problem(bookmark("bad!",token),422,"VALIDATION_ERROR");
        problem(send("PUT","/api/v1/me/bookmarks/"+content.id()+"?userId=other",token),400,"MALFORMED_REQUEST");
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me/bookmarks/"+content.id())).header("Authorization","Bearer "+token).header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString("{}"));
        problem(http.send(request.build(),HttpResponse.BodyHandlers.ofString()),400,"MALFORMED_REQUEST");empty(content);
    }
    @Test void sharedBookmarkReadsRequireTheCallersTransaction(){
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->bookmarkReader.readAll(List.of("content"),null));
    }
    private int realInsert(String id,String user,String content,java.time.LocalDateTime now){return sqlSession.insert(ContentBookmarkMapper.class.getName()+".insert",Map.of("id",id,"user",user,"content",content,"now",now));}
    private int count(Seed content){return jdbc.queryForObject("SELECT COUNT(*) FROM content_bookmark WHERE content_id=?",Integer.class,content.id());}
    private void empty(Seed content){assertEquals(0,count(content));}
    private void problem(HttpResponse<String> response,int status,String code){assertEquals(status,response.statusCode(),response.body());assertEquals(code,JsonPath.read(response.body(),"$.code"));assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());}
    private String tag(UserAccount author,List<Seed> content){String tag=id();jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) VALUES(?,?,'收藏范围','ACTIVE',1,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",tag,"bookmark_"+tag.substring(0,20),author.getId());for(var value:content)jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,0)",value.revision(),tag);return tag;}

    private UserAccount owner() {
        var owner=LoginTestSupport.account();owner.setId(id());owner.setEmail("bookmark-"+owner.getId()+"@example.test");owner.setEmailNormalized(owner.getEmail());
        owner.setDisplayName("收藏用户");owner.setBio("");owner.setAvatarAssetId(null);owners.add(owner.getId());
        users.insert(owner);roles.insertDefaultRole(owner.getId(),owner.getCreatedAt());return owner;
    }
    private Seed published(UserAccount author) {
        String content=id(),revision=id();
        jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) VALUES(?,?,'ARTICLE','ACTIVE','UNPUBLISHED',3,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",content,author.getId());
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,1,'APPROVED','收藏测试','公开正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",revision,content);
        jdbc.update("UPDATE community_content SET published_revision_id=?,publication_status='PUBLISHED',first_published_at='2026-11-03 08:00:00',published_at='2026-11-03 08:00:00' WHERE id=?",revision,content);
        return new Seed(content,revision);
    }
    private String token(UserAccount owner){return sessions.create(owner.getId(),owner.getAuthVersion(),"bookmark-it").accessToken();}
    private HttpResponse<String> bookmark(String content,String token) throws Exception{return send("PUT","/api/v1/me/bookmarks/"+content,token);}
    private HttpResponse<String> get(String path,String token) throws Exception{return send("GET",path,token);}
    private HttpResponse<String> send(String method,String path,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).method(method,HttpRequest.BodyPublishers.noBody());
        if(token!=null)request.header("Authorization","Bearer "+token);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
    private record Seed(String id,String revision){}
}
