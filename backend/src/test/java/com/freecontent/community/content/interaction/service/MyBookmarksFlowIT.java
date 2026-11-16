package com.freecontent.community.content.interaction.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.browse.config.PublicContentCursorProperties;
import com.freecontent.community.content.browse.cursor.PublicContentCursor;
import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import com.freecontent.community.content.interaction.cursor.BookmarkCursor;
import com.freecontent.community.content.interaction.mapper.BookmarkQueryMapper;
import com.freecontent.community.content.mapper.ContentLikeReadMapper;
import com.freecontent.community.content.mapper.ContentBookmarkReadMapper;
import org.apache.ibatis.session.SqlSession;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.TransientDataAccessResourceException;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "app.auth.email-challenge.delivery-enabled=false","app.media.processing.enabled=false","app.media.upload-cleanup.enabled=false"})
class MyBookmarksFlowIT {
    private static final String PREFIX="test:my-bookmarks:"+UUID.randomUUID()+":";
    private static final LocalDateTime BASE=LocalDateTime.of(2026,11,3,8,0);
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountMapper users;
    @Autowired UserRoleMapper roles;
    @Autowired RedisSessionStore sessions;
    @Autowired StringRedisTemplate redis;
    @Autowired SqlSession sql;
    @Autowired PublicContentCursorProperties cursorProperties;
    @Autowired JsonMapper json;
    @MockitoSpyBean BookmarkQueryMapper bookmarks;
    @MockitoSpyBean PublicContentMapper contents;
    @MockitoSpyBean PublicUserMapper profiles;
    @MockitoSpyBean ContentLikeReadMapper likes;
    @MockitoSpyBean ContentBookmarkReadMapper bookmarkStates;
    @MockitoSpyBean SessionAuthenticationService authentication;
    private final List<String> owners=new ArrayList<>();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry) {
        for(String group:List.of("content.bookmark-query","content.bookmark","content.public-query"))registry.add("app."+group+".redis-key-prefix",()->PREFIX);
    }
    @AfterEach void cleanup() {
        reset(bookmarks,contents,profiles,likes,bookmarkStates,authentication);
        var keys=redis.keys(PREFIX+"*");if(keys!=null && !keys.isEmpty())redis.delete(keys);
        for(String owner:owners)ContentTestSupport.clearWorkspace(jdbc,owner);
        for(String owner:owners){jdbc.update("DELETE FROM community_tag WHERE created_by=?",owner);jdbc.update("DELETE FROM media_asset WHERE owner_id=?",owner);jdbc.update("DELETE FROM app_user_role WHERE user_id=?",owner);users.deleteById(owner);}
    }
    @Test void onlyMyBookmarksAreReturnedInRelationshipTimeAndIdOrder() throws Exception {
        var author=owner();var viewer=owner();var other=owner();
        var first=published(author);var second=published(author);var third=published(author);var theirs=published(author);
        bookmark("bm_a",viewer,first,BASE);bookmark("bm_b",viewer,second,BASE);bookmark("bm_C",viewer,third,BASE.minusSeconds(1));
        bookmark("bm_z",other,theirs,BASE.plusDays(1));
        jdbc.update("UPDATE community_content SET first_published_at=?,published_at=? WHERE id=?",BASE.plusHours(1),BASE.plusHours(1),third.id());
        var response=get("",token(viewer));ok(response);
        assertEquals(List.of(second.id(),first.id(),third.id()),ids(response));
        assertFalse(JsonPath.<Boolean>read(response.body(),"$.data.hasMore"));assertNull(JsonPath.read(response.body(),"$.data.nextCursor"));
        assertNotNull(JsonPath.read(response.body(),"$.data.snapshotAt"));
        Map<String,Object> card=JsonPath.read(response.body(),"$.data.items[0]");assertEquals(14,card.size());
        assertTrue((Boolean)card.get("viewerHasBookmarked"));assertFalse(card.containsKey("bodyMarkdown"));
        assertFalse(card.containsKey("bookmarkId"));assertEquals(4,JsonPath.<Integer>read(response.body(),"$.data.items[0].author.publishedContentCount"));
    }
    @Test void pagesKeepTheBookmarkSnapshotWhenBookmarksAreRemovedOrRecreated() throws Exception {
        var author=owner();var viewer=owner();String token=token(viewer);
        var first=published(author);var second=published(author);var third=published(author);var fourth=published(author);
        bookmark("seek_z",viewer,first,BASE.plusSeconds(3));bookmark("seek_y",viewer,second,BASE.plusSeconds(2));
        bookmark("seek_b",viewer,third,BASE);bookmark("seek_a",viewer,fourth,BASE);
        var page=get("limit=1",token);ok(page);assertEquals(List.of(first.id()),ids(page));assertTrue(JsonPath.<Boolean>read(page.body(),"$.data.hasMore"));
        String cursor=JsonPath.read(page.body(),"$.data.nextCursor"),snapshot=JsonPath.read(page.body(),"$.data.snapshotAt");
        jdbc.update("DELETE FROM content_bookmark WHERE user_id=? AND content_id=?",viewer.getId(),second.id());
        bookmark("seek_new",viewer,second,LocalDateTime.ofInstant(Instant.parse(snapshot).plusSeconds(1),ZoneOffset.UTC));
        var next=get("limit=1&cursor="+cursor,token);ok(next);assertEquals(List.of(third.id()),ids(next));
        assertEquals(snapshot,JsonPath.read(next.body(),"$.data.snapshotAt"));
        var last=get("limit=1&cursor="+JsonPath.read(next.body(),"$.data.nextCursor"),token);ok(last);assertEquals(List.of(fourth.id()),ids(last));
        assertFalse(JsonPath.<Boolean>read(last.body(),"$.data.hasMore"));assertNull(JsonPath.read(last.body(),"$.data.nextCursor"));
    }
    @ParameterizedTest @ValueSource(strings={"DELETED","TAKEN_DOWN","WITHDRAWN","UNPUBLISHED","POINTER","REVISION","TITLE","VIDEO","ASSET"})
    void currentlyInvisibleBookmarksAreFilteredWithoutDeletingTheirFacts(String state) throws Exception {
        var author=owner();var viewer=owner();var row=published(author);bookmark(id(),viewer,row,BASE);
        switch(state){
            case "DELETED"->jdbc.update("UPDATE community_content SET lifecycle='DELETED' WHERE id=?",row.id());
            case "TAKEN_DOWN","WITHDRAWN","UNPUBLISHED"->jdbc.update("UPDATE community_content SET publication_status=? WHERE id=?",state,row.id());
            case "POINTER"->jdbc.update("UPDATE community_content SET published_revision_id=NULL WHERE id=?",row.id());
            case "REVISION"->jdbc.update("UPDATE content_revision SET status='REJECTED' WHERE id=?",row.revision());
            case "TITLE"->jdbc.update("UPDATE content_revision SET title='  ' WHERE id=?",row.revision());
            case "VIDEO"->jdbc.update("UPDATE community_content SET kind='VIDEO' WHERE id=?",row.id());
            default->{String asset=id();jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) VALUES(?,?,'CONTENT_IMAGE','test.png','image/png',1,?,'FAILED',UTC_TIMESTAMP(6))",asset,author.getId(),"private/"+asset);
                jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0)",row.revision(),asset);}
        }
        var response=get("limit=1",token(viewer));ok(response);assertTrue(ids(response).isEmpty());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_bookmark WHERE user_id=?",Integer.class,viewer.getId()));
    }
    @Test void readsTheCurrentPublishedRevisionAndRealInteractionFlagsEvenForDisabledAuthors() throws Exception {
        var author=owner();var viewer=owner();var row=published(author);bookmark(id(),viewer,row,BASE);
        String draft=id();jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,2,'DRAFT','私有草稿','私有正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",draft,row.id());
        jdbc.update("UPDATE community_content SET working_revision_id=? WHERE id=?",draft,row.id());
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?",author.getId());
        jdbc.update("INSERT INTO content_like(user_id,content_id,created_at) VALUES(?,?,UTC_TIMESTAMP(6))",viewer.getId(),row.id());
        jdbc.update("INSERT INTO content_like_stat(content_id,like_count,created_at,updated_at) VALUES(?,7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",row.id());
        var response=get("",token(viewer));ok(response);assertEquals(List.of(row.id()),ids(response));
        assertEquals(row.revision(),JsonPath.read(response.body(),"$.data.items[0].publishedRevisionId"));
        assertTrue(JsonPath.<Boolean>read(response.body(),"$.data.items[0].viewerHasLiked"));
        assertEquals(7,JsonPath.<Integer>read(response.body(),"$.data.items[0].likeCount"));assertFalse(response.body().contains("私有"));
    }
    @ParameterizedTest @CsvSource({"limit=0,422,VALIDATION_ERROR","limit=51,422,VALIDATION_ERROR","limit=99999999999999999999999,422,VALIDATION_ERROR",
            "limit=no,400,MALFORMED_REQUEST","limit=,400,MALFORMED_REQUEST","cursor=,400,CURSOR_INVALID","cursor=changed,400,CURSOR_INVALID",
            "userId=other,400,MALFORMED_REQUEST","kind=ARTICLE,400,MALFORMED_REQUEST","tagId=x,400,MALFORMED_REQUEST","limit=1&limit=2,400,MALFORMED_REQUEST","cursor=a&cursor=b,400,MALFORMED_REQUEST"})
    void invalidInputDoesNotConsumeQuota(String query,int status,String code) throws Exception {
        problem(get(query,token(owner())),status,code);assertNull(redis.opsForValue().get(sourceKey()));
    }
    @Test void overlongCursorAndRequestBodyAreRejectedBeforeQuota() throws Exception {
        String token=token(owner());problem(get("cursor="+"a".repeat(4097),token),422,"VALIDATION_ERROR");
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me/bookmarks")).header("Authorization","Bearer "+token)
                .header("Content-Type","application/json").method("GET",HttpRequest.BodyPublishers.ofString("{}"));
        problem(http.send(request.build(),HttpResponse.BodyHandlers.ofString()),400,"MALFORMED_REQUEST");assertNull(redis.opsForValue().get(sourceKey()));
    }
    @Test void cursorBindsUserLimitAndRouteButAllowsAnotherSessionOfTheSameUser() throws Exception {
        var author=owner();var viewer=owner();bookmark("cursor_b",viewer,published(author),BASE);bookmark("cursor_a",viewer,published(author),BASE);
        var first=get("limit=1",token(viewer));ok(first);String cursor=JsonPath.read(first.body(),"$.data.nextCursor");
        problem(get("limit=2&cursor="+cursor,token(viewer)),400,"CURSOR_INVALID");problem(get("limit=1&cursor="+cursor,token(owner())),400,"CURSOR_INVALID");
        var publicCursors=new PublicContentCursor(cursorProperties,json,Clock.systemUTC());
        var publicQuery=publicCursors.open(null,1,null,null,"user:"+viewer.getId());
        problem(get("limit=1&cursor="+publicCursors.next(publicQuery,BASE.toInstant(ZoneOffset.UTC),"cursor_a"),token(viewer)),400,"CURSOR_INVALID");
        var replacement=get("limit=1&cursor="+cursor,token(viewer));ok(replacement);assertEquals(1,ids(replacement).size());
        assertEquals("2",redis.opsForValue().get(sourceKey()));
    }
    @Test void expiredAuthenticatedCursorIs410AndDoesNotConsumeQuota() throws Exception {
        var viewer=owner();var old=new BookmarkCursor(cursorProperties,json,Clock.fixed(Instant.now().minusSeconds(cursorProperties.ttlSeconds()+1L),ZoneOffset.UTC));
        var query=old.open(null,1,viewer.getId());
        problem(get("limit=1&cursor="+old.next(query,query.snapshotAt().minusSeconds(1),"old"),token(viewer)),410,"CURSOR_EXPIRED");assertNull(redis.opsForValue().get(sourceKey()));
    }
    @Test void missingOrInvalidAuthenticationCannotReadBookmarks() throws Exception {
        problem(get("",null),401,"AUTHENTICATION_REQUIRED");problem(get("","bad-token"),401,"AUTHENTICATION_REQUIRED");assertNull(redis.opsForValue().get(sourceKey()));
    }
    @ParameterizedTest @CsvSource({"DISABLED,403,ACCOUNT_RESTRICTED","EMAIL,403,PERMISSION_DENIED","VERSION,401,AUTHENTICATION_REQUIRED"})
    void permissionsAreRecheckedInTheReadTransactionAfterAuthentication(String change,int status,String code) throws Exception {
        var viewer=owner();String token=token(viewer);
        doAnswer(call->{var principal=call.callRealMethod();switch(change){
            case "DISABLED"->jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?",viewer.getId());
            case "EMAIL"->jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?",viewer.getId());
            default->jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?",viewer.getId());}
            return principal;}).when(authentication).authenticate(token);
        problem(get("",token),status,code);verify(bookmarks,never()).list(anyString(),any(),any(),any(),anyInt());
    }
    @Test void writeBlockedUsersCanStillReadTheirBookmarksWithoutCreatorPrivileges() throws Exception {
        var viewer=owner();var row=published(owner());bookmark(id(),viewer,row,BASE);
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?",viewer.getId());
        jdbc.update("DELETE FROM app_user_role WHERE user_id=?",viewer.getId());
        var response=get("",token(viewer));ok(response);assertEquals(List.of(row.id()),ids(response));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void exhaustedSourceOrUserQuotaIs429WithRetryAfter(boolean user) throws Exception {
        var viewer=owner();String key=user?userKey(viewer):sourceKey();
        redis.opsForValue().set(key,user?"60":"120",Duration.ofSeconds(40));
        var response=get("",token(viewer));problem(response,429,"RATE_LIMITED");assertTrue(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow())>0);
        verify(bookmarks,never()).list(anyString(),any(),any(),any(),anyInt());
    }
    @Test void repeatedEmptyReadsConsumeOnlyTheIndependentReadBudget() throws Exception {
        var viewer=owner();String token=token(viewer);
        for(String group:List.of("content-bookmark","content-public-query"))redis.opsForValue().set(PREFIX+"{"+group+"}:source:"+TokenDigest.sha256("127.0.0.1"),"120",Duration.ofSeconds(40));
        ok(get("",token));ok(get("",token));assertEquals("2",redis.opsForValue().get(sourceKey()));assertEquals("2",redis.opsForValue().get(userKey(viewer)));
        assertEquals("120",redis.opsForValue().get(PREFIX+"{content-bookmark}:source:"+TokenDigest.sha256("127.0.0.1")));
    }
    @ParameterizedTest @ValueSource(strings={"ROOT","ASSETS","TAGS","AUTHORS","COUNTS","LIKES","BOOKMARKS"})
    void batchDependencyFailuresAre503RatherThanSuccessfulEmptyPages(String dependency) throws Exception {
        var viewer=owner();bookmark(id(),viewer,published(owner()),BASE);var failure=new TransientDataAccessResourceException("private SQL detail");
        switch(dependency){
            case "ROOT"->doThrow(failure).when(bookmarks).list(anyString(),any(),any(),any(),anyInt());
            case "ASSETS"->doThrow(failure).when(contents).findAssetsForRevisions(anyList());
            case "TAGS"->doThrow(failure).when(contents).findTagsForRevisions(anyList());
            case "AUTHORS"->doThrow(failure).when(profiles).findByIds(anyList());
            case "COUNTS"->doThrow(failure).when(contents).countVisibleByAuthors(anyList());
            case "LIKES"->doThrow(failure).when(likes).find(anyList(),anyString());
            default->doThrow(failure).when(bookmarkStates).find(anyList(),anyString());
        }
        var response=get("",token(viewer));problem(response,503,"DEPENDENCY_UNAVAILABLE");assertFalse(response.body().contains("private SQL"));
    }
    @Test void fullPagesUseBoundedBatchQueriesAndNoDetailLookups() throws Exception {
        var author=owner();var viewer=owner();for(int i=0;i<12;i++)bookmark(id(),viewer,published(author),BASE.plusSeconds(i));
        clearInvocations(bookmarks,contents,profiles,likes,bookmarkStates);var response=get("limit=50",token(viewer));ok(response);assertEquals(12,ids(response).size());
        verify(bookmarks,times(1)).list(eq(viewer.getId()),any(),isNull(),isNull(),eq(51));
        verify(contents,times(1)).findAssetsForRevisions(anyList());verify(contents,times(1)).findTagsForRevisions(anyList());
        verify(contents,times(1)).countVisibleByAuthors(anyList());verify(profiles,times(1)).findByIds(anyList());
        verify(likes,times(1)).find(anyList(),eq(viewer.getId()));verify(bookmarkStates,times(1)).find(anyList(),eq(viewer.getId()));
        verify(contents,never()).findById(anyString());verify(contents,never()).findAssets(anyString());verify(contents,never()).findTags(anyString());verify(profiles,never()).findById(anyString());
    }
    @Test void anEmptyFilteredBatchStillAdvancesTheLastScannedBookmark() throws Exception {
        var author=owner();var viewer=owner();bookmark("empty_b",viewer,published(author),BASE);bookmark("empty_a",viewer,published(author),BASE);
        doReturn(List.of()).when(profiles).findByIds(anyList());String token=token(viewer);
        var first=get("limit=1",token);ok(first);assertTrue(ids(first).isEmpty());assertTrue(JsonPath.<Boolean>read(first.body(),"$.data.hasMore"));
        var last=get("limit=1&cursor="+JsonPath.read(first.body(),"$.data.nextCursor"),token);ok(last);assertTrue(ids(last).isEmpty());assertFalse(JsonPath.<Boolean>read(last.body(),"$.data.hasMore"));
    }
    @Test void candidateRowsAndInteractionFlagsShareTheSameDatabaseSnapshot() throws Exception {
        var author=owner();var viewer=owner();var row=published(author);bookmark(id(),viewer,row,BASE);String token=token(viewer);
        var changed=new AtomicBoolean();var pool=Executors.newSingleThreadExecutor();
        try{
            doAnswer(call->{var result=sql.selectList(PublicContentMapper.class.getName()+".findAssetsForRevisions",Map.of("revisions",call.getArgument(0)));
                if(changed.compareAndSet(false,true))pool.submit(()->jdbc.update("DELETE FROM content_bookmark WHERE user_id=?",viewer.getId())).get(5,TimeUnit.SECONDS);return result;
            }).when(contents).findAssetsForRevisions(anyList());
            var first=get("",token);ok(first);assertEquals(List.of(row.id()),ids(first));assertTrue(JsonPath.<Boolean>read(first.body(),"$.data.items[0].viewerHasBookmarked"));
            var second=get("",token);ok(second);assertTrue(ids(second).isEmpty());
        }finally{pool.shutdownNow();}
    }
    @Test void defaultsAndMaximumPageSizesAreBounded() throws Exception {
        var author=owner();var viewer=owner();for(int i=0;i<51;i++)bookmark(id(),viewer,published(author),BASE.plusSeconds(i));String token=token(viewer);
        var normal=get("",token);ok(normal);assertEquals(20,ids(normal).size());
        var maximum=get("limit=50",token);ok(maximum);assertEquals(50,ids(maximum).size());
        var last=get("limit=50&cursor="+JsonPath.read(maximum.body(),"$.data.nextCursor"),token);ok(last);assertEquals(1,ids(last).size());assertNull(JsonPath.read(last.body(),"$.data.nextCursor"));
    }
    private String sourceKey(){return PREFIX+"{content-bookmark-query}:source:"+TokenDigest.sha256("127.0.0.1");}
    private String userKey(UserAccount user){return PREFIX+"{content-bookmark-query}:user:"+TokenDigest.sha256(user.getId());}
    private void problem(HttpResponse<String> response,int status,String code){assertEquals(status,response.statusCode(),response.body());assertEquals(code,JsonPath.read(response.body(),"$.code"));assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertTrue(response.headers().firstValue("X-Request-Id").isPresent());}
    private UserAccount owner() {
        var owner=LoginTestSupport.account();owner.setId(id());owner.setEmail("my-bookmarks-"+owner.getId()+"@example.test");owner.setEmailNormalized(owner.getEmail());
        owners.add(owner.getId());users.insert(owner);roles.insertDefaultRole(owner.getId(),owner.getCreatedAt());return owner;
    }
    private Seed published(UserAccount author) {
        String content=id(),revision=id();
        jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) VALUES(?,?,'ARTICLE','ACTIVE','UNPUBLISHED',3,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",content,author.getId());
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,1,'APPROVED','收藏列表测试','公开正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",revision,content);
        jdbc.update("UPDATE community_content SET published_revision_id=?,publication_status='PUBLISHED',first_published_at=?,published_at=? WHERE id=?",revision,BASE,BASE,content);
        return new Seed(content,revision);
    }
    private void bookmark(String id,UserAccount viewer,Seed content,LocalDateTime time){jdbc.update("INSERT INTO content_bookmark(id,user_id,content_id,created_at) VALUES(?,?,?,?)",id,viewer.getId(),content.id(),time);}
    private String token(UserAccount owner){return sessions.create(owner.getId(),owner.getAuthVersion(),"my-bookmarks-it").accessToken();}
    private HttpResponse<String> get(String query,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me/bookmarks"+(query.isEmpty()?"":"?"+query))).timeout(Duration.ofSeconds(20)).GET();
        if(token!=null)request.header("Authorization","Bearer "+token);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void ok(HttpResponse<String> response){assertEquals(200,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertTrue(response.headers().firstValue("X-Request-Id").isPresent());}
    private List<String> ids(HttpResponse<String> response){return JsonPath.read(response.body(),"$.data.items[*].id");}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
    private record Seed(String id,String revision){}
}
