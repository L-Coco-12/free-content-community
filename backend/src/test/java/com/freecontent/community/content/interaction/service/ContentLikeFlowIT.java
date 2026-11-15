package com.freecontent.community.content.interaction.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.interaction.mapper.ContentLikeMapper;
import com.freecontent.community.content.mapper.ContentLikeReadMapper;
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
class ContentLikeFlowIT {
    private static final String PREFIX="test:content-like:"+UUID.randomUUID()+":";
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountMapper users;
    @Autowired UserRoleMapper roles;
    @Autowired RedisSessionStore sessions;
    @Autowired StringRedisTemplate redis;
    @Autowired SqlSession sqlSession;
    @Autowired com.freecontent.community.content.service.read.ContentLikeReader likeReader;
    @MockitoSpyBean ContentLikeMapper likes;
    @MockitoSpyBean ContentLikeReadMapper likeReads;
    @MockitoSpyBean SessionAuthenticationService authentication;
    private final List<String> owners=new ArrayList<>();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry) {
        for(String group:List.of("content.like","content.public-query","user.public-query"))registry.add("app."+group+".redis-key-prefix",()->PREFIX);
    }
    @AfterEach void cleanup() {
        var keys=redis.keys(PREFIX+"*");if(keys!=null && !keys.isEmpty())redis.delete(keys);
        for(String owner:owners){ContentTestSupport.clearReviews(jdbc,owner);ContentTestSupport.clearWorkspace(jdbc,owner);}
        for(String owner:owners){jdbc.update("DELETE FROM community_tag WHERE created_by=?",owner);jdbc.update("DELETE FROM media_asset WHERE owner_id=?",owner);jdbc.update("DELETE FROM app_user_role WHERE user_id=?",owner);users.deleteById(owner);}
    }
    @Test void firstLikeAndRepeatedPutReturn204ButCreateOnlyOneRelationshipAndCount() throws Exception {
        var author=owner();var viewer=owner();var content=published(author);String token=token(viewer);
        var before=jdbc.queryForMap("SELECT version,updated_at FROM community_content WHERE id=?",content.id());
        for(int i=0;i<3;i++) {
            var response=like(content.id(),token);assertEquals(204,response.statusCode(),response.body());assertEquals("",response.body());
            assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_like WHERE user_id=? AND content_id=?",Integer.class,viewer.getId(),content.id()));
        assertEquals(1L,jdbc.queryForObject("SELECT like_count FROM content_like_stat WHERE content_id=?",Long.class,content.id()));
        assertEquals(before,jdbc.queryForMap("SELECT version,updated_at FROM community_content WHERE id=?",content.id()));
        var detail=get("/api/v1/contents/"+content.id(),token);assertEquals(200,detail.statusCode(),detail.body());
        assertEquals(1,JsonPath.<Integer>read(detail.body(),"$.data.likeCount"));assertTrue(JsonPath.<Boolean>read(detail.body(),"$.data.viewerHasLiked"));
        var anonymous=get("/api/v1/contents/"+content.id(),null);
        assertEquals(1,JsonPath.<Integer>read(anonymous.body(),"$.data.likeCount"));assertFalse(JsonPath.<Boolean>read(anonymous.body(),"$.data.viewerHasLiked"));
        assertEquals("3",redis.opsForValue().get(PREFIX+"{content-like}:user:"+TokenDigest.sha256(viewer.getId())));
    }

    @Test void unlikeAndRepeatedDeletesReturn204ButDecrementOnlyOnce() throws Exception {
        var content=published(owner());var viewer=owner();String token=token(viewer);
        var before=jdbc.queryForMap("SELECT version,updated_at FROM community_content WHERE id=?",content.id());
        assertEquals(204,like(content.id(),token).statusCode());
        var first=unlike(content.id(),token);assertEquals(204,first.statusCode(),first.body());assertEquals("",first.body());
        assertEquals("no-store",first.headers().firstValue("Cache-Control").orElseThrow());
        var stat=jdbc.queryForMap("SELECT like_count,created_at,updated_at FROM content_like_stat WHERE content_id=?",content.id());
        assertEquals(204,unlike(content.id(),token).statusCode());assertEquals(204,unlike(content.id(),token).statusCode());
        noLikes(content);assertEquals(stat,jdbc.queryForMap("SELECT like_count,created_at,updated_at FROM content_like_stat WHERE content_id=?",content.id()));
        assertEquals(before,jdbc.queryForMap("SELECT version,updated_at FROM community_content WHERE id=?",content.id()));
        var detail=get("/api/v1/contents/"+content.id(),token);assertEquals(200,detail.statusCode(),detail.body());
        assertEquals(0,JsonPath.<Integer>read(detail.body(),"$.data.likeCount"));assertFalse(JsonPath.<Boolean>read(detail.body(),"$.data.viewerHasLiked"));
        assertEquals("4",redis.opsForValue().get(PREFIX+"{content-like}:user:"+TokenDigest.sha256(viewer.getId())));
    }

    @Test void cancellingAbsentRelationshipsAndUnknownTargetsIs204WithoutCreatingStatistics() throws Exception {
        var content=published(owner());String token=token(owner());
        assertEquals(204,unlike(content.id(),token).statusCode());
        assertEquals(204,unlike(id(),token).statusCode());noLikes(content);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_like_stat WHERE content_id=?",Integer.class,content.id()));
    }
    @Test void cancellingOnlyRemovesTheCallersRelationship() throws Exception {
        var content=published(owner());var a=owner();var b=owner();String at=token(a),bt=token(b);
        assertEquals(204,like(content.id(),at).statusCode());assertEquals(204,like(content.id(),bt).statusCode());
        assertEquals(204,unlike(content.id(),token(owner())).statusCode());assertEquals(2L,count(content));
        assertEquals(204,unlike(content.id(),at).statusCode());assertEquals(1L,count(content));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_like WHERE user_id=? AND content_id=?",Integer.class,a.getId(),content.id()));
        assertTrue(JsonPath.<Boolean>read(get("/api/v1/contents/"+content.id(),bt).body(),"$.data.viewerHasLiked"));
        assertFalse(JsonPath.<Boolean>read(get("/api/v1/contents/"+content.id(),at).body(),"$.data.viewerHasLiked"));
    }
    @ParameterizedTest @CsvSource({"ACTIVE,WITHDRAWN","ACTIVE,TAKEN_DOWN","DELETED,PUBLISHED","ACTIVE,UNPUBLISHED"})
    void existingLikesCanBeRemovedAfterTheContentBecomesHidden(String lifecycle,String publication) throws Exception {
        var content=published(owner());String token=token(owner());assertEquals(204,like(content.id(),token).statusCode());
        jdbc.update("UPDATE community_content SET lifecycle=?,publication_status=? WHERE id=?",lifecycle,publication,content.id());
        var response=unlike(content.id(),token);assertEquals(204,response.statusCode(),response.body());assertEquals("",response.body());noLikes(content);
    }
    @Test void cleanupDoesNotDependOnThePublicationRevisionOrMediaBeingReadable() throws Exception {
        var content=published(owner());String token=token(owner());assertEquals(204,like(content.id(),token).statusCode());
        jdbc.update("UPDATE community_content SET published_revision_id=NULL WHERE id=?",content.id());
        assertEquals(204,unlike(content.id(),token).statusCode());noLikes(content);
    }
    @ParameterizedTest @ValueSource(strings={"WRITE_BLOCKED","DISABLED"})
    void restrictedActorsCannotCancelLikes(String state) throws Exception {
        var content=published(owner());var actor=owner();String token=token(actor);assertEquals(204,like(content.id(),token).statusCode());
        jdbc.update("UPDATE app_user SET account_status=? WHERE id=?",state,actor.getId());
        problem(unlike(content.id(),token),403,"ACCOUNT_RESTRICTED");assertEquals(1L,count(content));
    }
    @ParameterizedTest @ValueSource(strings={"auth_version=auth_version+1","account_status='WRITE_BLOCKED'","email_verified=0"})
    void cancelAuthorizationIsRecheckedAfterAuthentication(String change) throws Exception {
        var content=published(owner());var actor=owner();String token=token(actor);assertEquals(204,like(content.id(),token).statusCode());
        doAnswer(call->{var result=call.callRealMethod();jdbc.update("UPDATE app_user SET "+change+" WHERE id=?",actor.getId());return result;}).when(authentication).authenticate(token);
        String code=change.startsWith("auth_version")?"AUTHENTICATION_REQUIRED":change.startsWith("account_status")?"ACCOUNT_RESTRICTED":"PERMISSION_DENIED";
        problem(unlike(content.id(),token),code.equals("AUTHENTICATION_REQUIRED")?401:403,code);assertEquals(1L,count(content));
    }
    @Test void anonymousOrInvalidCancellationDoesNotChangeTheLike() throws Exception {
        var content=published(owner());String token=token(owner());assertEquals(204,like(content.id(),token).statusCode());
        problem(unlike(content.id(),null),401,"AUTHENTICATION_REQUIRED");problem(unlike(content.id(),"bad"),401,"AUTHENTICATION_REQUIRED");
        problem(unlike("bad!",token),422,"VALIDATION_ERROR");
        problem(send("DELETE","/api/v1/contents/"+content.id()+"/likes/me?userId=someone",token),400,"MALFORMED_REQUEST");
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/contents/"+content.id()+"/likes/me"))
                .header("Authorization","Bearer "+token).method("DELETE",HttpRequest.BodyPublishers.ofString("{}"));
        problem(http.send(request.build(),HttpResponse.BodyHandlers.ofString()),400,"MALFORMED_REQUEST");assertEquals(1L,count(content));
    }
    @Test void cancellingAndLikingShareTheSameUserBudgetInBothDirections() throws Exception {
        var content=published(owner());var actor=owner();String token=token(actor);
        String key=PREFIX+"{content-like}:user:"+TokenDigest.sha256(actor.getId());
        redis.opsForValue().set(key,"59",Duration.ofMinutes(1));
        assertEquals(204,like(content.id(),token).statusCode());problem(unlike(content.id(),token),429,"RATE_LIMITED");assertEquals(1L,count(content));
        redis.opsForValue().set(key,"59",Duration.ofMinutes(1));
        assertEquals(204,unlike(content.id(),token).statusCode());problem(like(content.id(),token),429,"RATE_LIMITED");noLikes(content);
    }
    @Test void cancellingAlsoConsumesTheSharedSourceBudget() throws Exception {
        var content=published(owner());String token=token(owner());assertEquals(204,like(content.id(),token).statusCode());
        redis.opsForValue().set(PREFIX+"{content-like}:source:"+TokenDigest.sha256("127.0.0.1"),"119",Duration.ofMinutes(1));
        assertEquals(204,unlike(content.id(),token).statusCode());
        problem(like(content.id(),token(owner())),429,"RATE_LIMITED");noLikes(content);
    }
    @Test void simultaneousRepeatedDeletesDoNotMakeTheCounterNegative() throws Exception {
        var content=published(owner());String token=token(owner());assertEquals(204,like(content.id(),token).statusCode());
        var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);
        try{
            var futures=new ArrayList<Future<Integer>>();for(int i=0;i<12;i++)futures.add(pool.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));return unlike(content.id(),token).statusCode();}));
            start.countDown();for(var future:futures)assertEquals(204,future.get(20,TimeUnit.SECONDS));
        }finally{start.countDown();pool.shutdownNow();}
        noLikes(content);
    }
    @Test void differentUsersCanCancelFromTheSameCounterWithoutLostUpdates() throws Exception {
        var content=published(owner());var tokens=new ArrayList<String>();for(int i=0;i<8;i++){String token=token(owner());tokens.add(token);assertEquals(204,like(content.id(),token).statusCode());}
        assertEquals(8L,count(content));var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);
        try{
            var futures=new ArrayList<Future<Integer>>();for(String token:tokens)futures.add(pool.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));return unlike(content.id(),token).statusCode();}));
            start.countDown();for(var future:futures)assertEquals(204,future.get(20,TimeUnit.SECONDS));
        }finally{start.countDown();pool.shutdownNow();}
        noLikes(content);
    }
    @Test void deleteWaitsForAnInFlightLikeAndThenRemovesIt() throws Exception {
        var content=published(owner());String token=token(owner());var pool=Executors.newSingleThreadExecutor();
        var entered=new CountDownLatch(1);var removal=new java.util.concurrent.atomic.AtomicReference<Future<Integer>>();
        doAnswer(call->{
            int inserted=sqlSession.insert(ContentLikeMapper.class.getName()+".insert",Map.of("user",call.getArgument(0),"content",call.getArgument(1),"now",call.getArgument(2)));
            removal.set(pool.submit(()->{entered.countDown();return unlike(content.id(),token).statusCode();}));
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->removal.get().get(200,TimeUnit.MILLISECONDS));return inserted;
        }).when(likes).insert(anyString(),anyString(),any());
        try{assertEquals(204,like(content.id(),token).statusCode());assertEquals(204,removal.get().get(5,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
        noLikes(content);
    }
    @Test void aNewLikeWaitsForAnInFlightDeleteAndThenReestablishesTheRelationship() throws Exception {
        var content=published(owner());String token=token(owner());assertEquals(204,like(content.id(),token).statusCode());
        var pool=Executors.newSingleThreadExecutor();var entered=new CountDownLatch(1);var relike=new java.util.concurrent.atomic.AtomicReference<Future<Integer>>();
        doAnswer(call->{
            int deleted=sqlSession.delete(ContentLikeMapper.class.getName()+".delete",Map.of("user",call.getArgument(0),"content",call.getArgument(1)));
            relike.set(pool.submit(()->{entered.countDown();return like(content.id(),token).statusCode();}));
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->relike.get().get(200,TimeUnit.MILLISECONDS));return deleted;
        }).when(likes).delete(anyString(),anyString());
        try{assertEquals(204,unlike(content.id(),token).statusCode());assertEquals(204,relike.get().get(5,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
        assertEquals(1L,count(content));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_like WHERE content_id=?",Integer.class,content.id()));
    }
    @Test void decrementFailureRollsBackBothTheRealDeleteAndCounterUpdate() throws Exception {
        var content=published(owner());var actor=owner();String token=token(actor);assertEquals(204,like(content.id(),token).statusCode());
        doAnswer(call->{sqlSession.update(ContentLikeMapper.class.getName()+".decrement",Map.of("content",call.getArgument(0),"now",call.getArgument(1)));throw new TransientDataAccessResourceException("private decrement detail");})
                .when(likes).decrement(anyString(),any());
        var response=unlike(content.id(),token);problem(response,503,"DEPENDENCY_UNAVAILABLE");assertFalse(response.body().contains("private decrement"));
        assertEquals(1L,count(content));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_like WHERE user_id=? AND content_id=?",Integer.class,actor.getId(),content.id()));
        doAnswer(call->sqlSession.update(ContentLikeMapper.class.getName()+".decrement",Map.of("content",call.getArgument(0),"now",call.getArgument(1)))).when(likes).decrement(anyString(),any());
        assertEquals(204,unlike(content.id(),token).statusCode());noLikes(content);
    }
    @ParameterizedTest @ValueSource(strings={"MISSING","ZERO"})
    void inconsistentStatisticsDoNotSilentlyDiscardTheRelationship(String mode) throws Exception {
        var content=published(owner());String token=token(owner());assertEquals(204,like(content.id(),token).statusCode());
        if(mode.equals("MISSING"))jdbc.update("DELETE FROM content_like_stat WHERE content_id=?",content.id());
        else jdbc.update("UPDATE content_like_stat SET like_count=0 WHERE content_id=?",content.id());
        problem(unlike(content.id(),token),503,"DEPENDENCY_UNAVAILABLE");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_like WHERE content_id=?",Integer.class,content.id()));assertEquals(0L,count(content));
    }
    @Test void globalAndAuthorListsReflectCancellationForTheCurrentViewer() throws Exception {
        var author=owner();var content=published(author);String tag=tag(author,List.of(content));String token=token(owner());
        assertEquals(204,like(content.id(),token).statusCode());assertEquals(204,unlike(content.id(),token).statusCode());
        for(String path:List.of("/api/v1/contents?tagId="+tag,"/api/v1/users/"+author.getId()+"/contents")){
            var response=get(path,token);assertEquals(200,response.statusCode(),response.body());
            assertEquals(0,JsonPath.<Integer>read(response.body(),"$.data.items[0].likeCount"));assertFalse(JsonPath.<Boolean>read(response.body(),"$.data.items[0].viewerHasLiked"));
        }
    }

    @Test void missingOrMalformedIdentityIs401WithoutCreatingRelationships() throws Exception {
        var content=published(owner());
        problem(like(content.id(),null),401,"AUTHENTICATION_REQUIRED");
        problem(like(content.id(),"bad"),401,"AUTHENTICATION_REQUIRED");
        problem(like(content.id(),"0".repeat(43)),401,"AUTHENTICATION_REQUIRED");noLikes(content);
    }
    @ParameterizedTest @ValueSource(strings={"WRITE_BLOCKED","DISABLED"})
    void restrictedActorCannotLike(String state) throws Exception {
        var content=published(owner());var viewer=owner();String token=token(viewer);
        jdbc.update("UPDATE app_user SET account_status=? WHERE id=?",state,viewer.getId());
        problem(like(content.id(),token),403,"ACCOUNT_RESTRICTED");noLikes(content);
    }
    @ParameterizedTest @ValueSource(strings={"USER","MODERATOR","ADMIN"})
    void activeUsersCanLikeTheirOwnPublicContentWithoutACreatorRoleGate(String role) throws Exception {
        var owner=owner();var content=published(owner);
        jdbc.update("DELETE FROM app_user_role WHERE user_id=?",owner.getId());
        jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,?,UTC_TIMESTAMP(6))",owner.getId(),role);
        String token=token(owner);
        if(!role.equals("USER"))assertFalse(JsonPath.<Boolean>read(get("/api/v1/me",token).body(),"$.data.canCreate"));
        assertEquals(204,like(content.id(),token).statusCode());assertEquals(1L,count(content));
    }
    @ParameterizedTest @ValueSource(strings={"auth_version=auth_version+1","account_status='WRITE_BLOCKED'","email_verified=0"})
    void authorizationIsRecheckedInsideTheTransaction(String change) throws Exception {
        var content=published(owner());var viewer=owner();String token=token(viewer);
        doAnswer(call->{var actor=call.callRealMethod();jdbc.update("UPDATE app_user SET "+change+" WHERE id=?",viewer.getId());return actor;}).when(authentication).authenticate(token);
        String code=change.startsWith("auth_version")?"AUTHENTICATION_REQUIRED":change.startsWith("account_status")?"ACCOUNT_RESTRICTED":"PERMISSION_DENIED";
        problem(like(content.id(),token),code.equals("AUTHENTICATION_REQUIRED")?401:403,code);noLikes(content);
    }
    @ParameterizedTest @CsvSource({"ACTIVE,UNPUBLISHED","ACTIVE,WITHDRAWN","ACTIVE,TAKEN_DOWN","DELETED,PUBLISHED"})
    void currentlyHiddenContentIs404(String lifecycle,String publication) throws Exception {
        var content=published(owner());String token=token(owner());
        jdbc.update("UPDATE community_content SET lifecycle=?,publication_status=? WHERE id=?",lifecycle,publication,content.id());
        problem(like(content.id(),token),404,"RESOURCE_NOT_FOUND");noLikes(content);
    }
    @Test void repeatedLikeStillRequiresTheContentToBeCurrentlyVisible() throws Exception {
        var content=published(owner());String token=token(owner());assertEquals(204,like(content.id(),token).statusCode());
        jdbc.update("UPDATE community_content SET publication_status='WITHDRAWN' WHERE id=?",content.id());
        problem(like(content.id(),token),404,"RESOURCE_NOT_FOUND");assertEquals(1L,count(content));
    }
    @Test void nonexistentContentIs404() throws Exception {
        String missing=id();problem(like(missing,token(owner())),404,"RESOURCE_NOT_FOUND");
    }
    @Test void aNewRevisionUnderReviewDoesNotHideTheApprovedPublication() throws Exception {
        var author=owner();var content=published(author);String working=id();
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,2,'IN_REVIEW','审核中新稿','私有正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",working,content.id());
        String review=ContentTestSupport.pending(jdbc,content.id(),working);jdbc.update("UPDATE content_review SET purpose='UPDATE' WHERE id=?",review);
        jdbc.update("UPDATE community_content SET working_revision_id=?,active_review_id=? WHERE id=?",working,review,content.id());
        assertEquals(204,like(content.id(),token(owner())).statusCode());assertEquals(1L,count(content));
        assertEquals("PENDING",jdbc.queryForObject("SELECT state FROM content_review WHERE id=?",String.class,review));
    }
    @ParameterizedTest @ValueSource(strings={"PROCESSING","OTHER_OWNER","WRONG_SCENE"})
    void invalidPublishedMediaMakesTheContentUnavailable(String scenario) throws Exception {
        var author=owner();var viewer=owner();var content=published(author);String asset=id();
        String assetOwner=scenario.equals("OTHER_OWNER")?viewer.getId():author.getId();
        jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) VALUES(?,?,?,'image.png','image/png',1,?,?,UTC_TIMESTAMP(6))",
                asset,assetOwner,scenario.equals("WRONG_SCENE")?"AVATAR":"CONTENT_IMAGE","private/"+assetOwner+"/"+asset+"/source",scenario.equals("PROCESSING")?"PROCESSING":"READY");
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'IMAGE',0)",content.revision(),asset);
        problem(like(content.id(),token(viewer)),404,"RESOURCE_NOT_FOUND");noLikes(content);
    }
    @Test void disabledPublishedTagsAndRestrictedAuthorsRemainVisible() throws Exception {
        var author=owner();var content=published(author);String tag=tag(author,List.of(content));
        jdbc.update("UPDATE community_tag SET status='DISABLED' WHERE id=?",tag);
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?",author.getId());
        assertEquals(204,like(content.id(),token(owner())).statusCode());assertEquals(1L,count(content));
    }
    @Test void publishingAnotherApprovedRevisionPreservesTheContentsLikes() throws Exception {
        var author=owner();var content=published(author);String token=token(owner());assertEquals(204,like(content.id(),token).statusCode());
        String revision=id();jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,2,'APPROVED','新公开稿','新正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",revision,content.id());
        jdbc.update("UPDATE community_content SET published_revision_id=?,published_at=UTC_TIMESTAMP(6),version=version+1 WHERE id=?",revision,content.id());
        var response=get("/api/v1/contents/"+content.id(),token);assertEquals(200,response.statusCode(),response.body());
        assertEquals(revision,JsonPath.read(response.body(),"$.data.publishedRevisionId"));assertEquals(1,JsonPath.<Integer>read(response.body(),"$.data.likeCount"));
        assertTrue(JsonPath.<Boolean>read(response.body(),"$.data.viewerHasLiked"));assertEquals(204,like(content.id(),token).statusCode());assertEquals(1L,count(content));
    }
    @Test void simultaneousDuplicatePutsCreateOnlyOneLike() throws Exception {
        var content=published(owner());String token=token(owner());var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(8);
        try {
            var futures=new ArrayList<Future<Integer>>();for(int i=0;i<12;i++)futures.add(pool.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));return like(content.id(),token).statusCode();}));
            start.countDown();for(var future:futures)assertEquals(204,future.get(20,TimeUnit.SECONDS));
        }finally{start.countDown();pool.shutdownNow();}
        assertEquals(1L,count(content));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_like WHERE content_id=?",Integer.class,content.id()));
    }
    @Test void differentUsersIncrementTheSameCounterWithoutLostUpdates() throws Exception {
        var content=published(owner());var tokens=new ArrayList<String>();for(int i=0;i<10;i++)tokens.add(token(owner()));
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(10);
        try {
            var futures=new ArrayList<Future<Integer>>();for(String token:tokens)futures.add(pool.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));return like(content.id(),token).statusCode();}));
            start.countDown();for(var future:futures)assertEquals(204,future.get(20,TimeUnit.SECONDS));
        }finally{start.countDown();pool.shutdownNow();}
        assertEquals(10L,count(content));assertEquals(10,jdbc.queryForObject("SELECT COUNT(*) FROM content_like WHERE content_id=?",Integer.class,content.id()));
    }
    @Test void counterFailureRollsBackTheRealRelationshipAndCounterUpdate() throws Exception {
        var content=published(owner());String token=token(owner());
        doAnswer(call->{sqlSession.insert(ContentLikeMapper.class.getName()+".increment",Map.of("content",call.getArgument(0),"now",call.getArgument(1)));throw new TransientDataAccessResourceException("injected private SQL failure");})
                .when(likes).increment(anyString(),any());
        var response=like(content.id(),token);problem(response,503,"DEPENDENCY_UNAVAILABLE");assertFalse(response.body().contains("private SQL"));noLikes(content);
        doAnswer(call->sqlSession.insert(ContentLikeMapper.class.getName()+".increment",Map.of("content",call.getArgument(0),"now",call.getArgument(1)))).when(likes).increment(anyString(),any());
        assertEquals(204,like(content.id(),token).statusCode());assertEquals(1L,count(content));
    }
    @Test void contentLockSerializesTheLikeBeforeAConcurrentWithdrawal() throws Exception {
        var content=published(owner());String token=token(owner());var pool=Executors.newSingleThreadExecutor();
        var entered=new CountDownLatch(1);var withdrawal=new java.util.concurrent.atomic.AtomicReference<Future<Integer>>();
        doAnswer(call->{
            withdrawal.set(pool.submit(()->{entered.countDown();return jdbc.update("UPDATE community_content SET publication_status='WITHDRAWN' WHERE id=?",content.id());}));
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->withdrawal.get().get(200,TimeUnit.MILLISECONDS));
            return sqlSession.insert(ContentLikeMapper.class.getName()+".insert",Map.of("user",call.getArgument(0),"content",call.getArgument(1),"now",call.getArgument(2)));
        }).when(likes).insert(anyString(),anyString(),any());
        try{assertEquals(204,like(content.id(),token).statusCode());assertEquals(1,withdrawal.get().get(5,TimeUnit.SECONDS));}
        finally{pool.shutdownNow();}
        assertEquals(1L,count(content));problem(like(content.id(),token),404,"RESOURCE_NOT_FOUND");
    }
    @ParameterizedTest @ValueSource(strings={"SOURCE","USER"})
    void eitherIndependentBudgetReturns429WithoutChangingLikes(String scope) throws Exception {
        var content=published(owner());var viewer=owner();String token=token(viewer);
        String key=PREFIX+"{content-like}:"+(scope.equals("USER")?"user:"+TokenDigest.sha256(viewer.getId()):"source:"+TokenDigest.sha256("127.0.0.1"));
        redis.opsForValue().set(key,scope.equals("USER")?"60":"120",Duration.ofMinutes(1));
        var response=like(content.id(),token);problem(response,429,"RATE_LIMITED");assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow())>0);
        noLikes(content);assertEquals(200,get("/api/v1/contents/"+content.id(),token).statusCode());
    }
    @Test void listsBatchRealCountsAndUseOnlyTheCurrentViewersRelationships() throws Exception {
        var author=owner();var first=published(author);var second=published(author);String scopeTag=tag(author,List.of(first,second));
        var a=owner();var b=owner();String at=token(a),bt=token(b);
        assertEquals(204,like(first.id(),at).statusCode());assertEquals(204,like(second.id(),bt).statusCode());
        for(String path:List.of("/api/v1/contents?tagId="+scopeTag+"&limit=10","/api/v1/users/"+author.getId()+"/contents?limit=10")) {
            clearInvocations(likeReads);var result=get(path,at);assertEquals(200,result.statusCode(),result.body());
            List<Map<String,Object>> items=JsonPath.read(result.body(),"$.data.items");assertEquals(2,items.size());
            for(var item:items){assertEquals(1,((Number)item.get("likeCount")).intValue());assertEquals(item.get("id").equals(first.id()),item.get("viewerHasLiked"));}
            verify(likeReads,times(1)).find(argThat(ids->new HashSet<>(ids).equals(Set.of(first.id(),second.id()))),eq(a.getId()));
            var anonymous=get(path,null);assertEquals(200,anonymous.statusCode());
            List<Boolean> flags=JsonPath.read(anonymous.body(),"$.data.items[*].viewerHasLiked");assertEquals(List.of(false,false),flags);
        }
    }
    @Test void statisticsReadFailureIs503InsteadOfAnEmptySuccessfulList() throws Exception {
        var author=owner();var content=published(author);String token=token(owner());
        doThrow(new TransientDataAccessResourceException("private statistics detail")).when(likeReads).find(anyList(),nullable(String.class));
        problem(get("/api/v1/contents/"+content.id(),token),503,"DEPENDENCY_UNAVAILABLE");
        problem(get("/api/v1/users/"+author.getId()+"/contents",token),503,"DEPENDENCY_UNAVAILABLE");
    }
    @Test void unsupportedInputIsRejectedWithoutWriting() throws Exception {
        var content=published(owner());String token=token(owner());
        problem(like("bad!",token),422,"VALIDATION_ERROR");
        problem(send("PUT","/api/v1/contents/"+content.id()+"/likes/me?userId=other",token),400,"MALFORMED_REQUEST");
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/contents/"+content.id()+"/likes/me"))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString("{}"));
        problem(http.send(request.build(),HttpResponse.BodyHandlers.ofString()),400,"MALFORMED_REQUEST");noLikes(content);
    }
    @Test void sharedStatisticsReaderRequiresTheCallersSnapshotTransaction() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->likeReader.readAll(List.of("content"),null));
    }

    private long count(Seed content){return jdbc.queryForObject("SELECT COALESCE((SELECT like_count FROM content_like_stat WHERE content_id=?),0)",Long.class,content.id());}
    private void noLikes(Seed content){assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_like WHERE content_id=?",Integer.class,content.id()));assertEquals(0L,count(content));}
    private void problem(HttpResponse<String> response,int status,String code){assertEquals(status,response.statusCode(),response.body());assertEquals(code,JsonPath.read(response.body(),"$.code"));assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());}
    private String tag(UserAccount author,List<Seed> content){String tag=id();jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) VALUES(?,?,'点赞筛选范围','ACTIVE',1,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",tag,"like_"+tag.substring(0,20),author.getId());for(var value:content)jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,0)",value.revision(),tag);return tag;}

    private UserAccount owner() {
        var owner=LoginTestSupport.account();owner.setId(id());owner.setEmail("like-"+owner.getId()+"@example.test");owner.setEmailNormalized(owner.getEmail());
        owner.setDisplayName("点赞用户");owner.setBio("");owner.setAvatarAssetId(null);owners.add(owner.getId());
        users.insert(owner);roles.insertDefaultRole(owner.getId(),owner.getCreatedAt());return owner;
    }
    private Seed published(UserAccount author) {
        String content=id(),revision=id();
        jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) VALUES(?,?,'ARTICLE','ACTIVE','UNPUBLISHED',3,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",content,author.getId());
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,1,'APPROVED','点赞测试','公开正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",revision,content);
        jdbc.update("UPDATE community_content SET published_revision_id=?,publication_status='PUBLISHED',first_published_at='2026-11-03 08:00:00',published_at='2026-11-03 08:00:00' WHERE id=?",revision,content);
        return new Seed(content,revision);
    }
    private String token(UserAccount owner){return sessions.create(owner.getId(),owner.getAuthVersion(),"content-like-it").accessToken();}
    private HttpResponse<String> like(String id,String token) throws Exception {return send("PUT","/api/v1/contents/"+id+"/likes/me",token);}
    private HttpResponse<String> unlike(String id,String token) throws Exception {return send("DELETE","/api/v1/contents/"+id+"/likes/me",token);}
    private HttpResponse<String> get(String path,String token) throws Exception {return send("GET",path,token);}
    private HttpResponse<String> send(String method,String path,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).method(method,HttpRequest.BodyPublishers.noBody());
        if(token!=null)request.header("Authorization","Bearer "+token);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
    private record Seed(String id,String revision) { }
}
