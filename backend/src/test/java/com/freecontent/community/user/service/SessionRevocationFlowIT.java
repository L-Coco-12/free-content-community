package com.freecontent.community.user.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.session.*;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.user.service.transaction.SessionRevocationTransactionService;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
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
class SessionRevocationFlowIT {
    private static final String PREFIX="test:session-revocation:"+UUID.randomUUID()+":";
    @Value("${local.server.port}") int port;
    @Autowired UserAccountMapper users;
    @Autowired UserRoleMapper roles;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean RedisSessionStore sessions;
    @MockitoSpyBean SessionAuthenticationService authentication;
    @MockitoSpyBean SessionRevocationTransactionService transactions;
    @Autowired StringRedisTemplate redis;
    private final List<String> owners=new ArrayList<>();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry){
        for(String group:List.of("auth.login","user.session-revocation","user.session-query"))registry.add("app."+group+".redis-key-prefix",()->PREFIX);
    }
    @AfterEach void cleanup(){
        reset(sessions,authentication,transactions);
        var keys=redis.keys(PREFIX+"*");if(keys!=null && !keys.isEmpty())redis.delete(keys);
        for(String owner:owners){jdbc.update("DELETE FROM app_user_role WHERE user_id=?",owner);users.deleteById(owner);}
    }
    @Test void removesOnlyTheNamedOwnSessionAndRepeatedRequestsReturn204() throws Exception {
        var owner=owner();var current=issue(owner);var target=issue(owner);var spare=issue(owner);
        var spareBefore=redis.opsForHash().entries(key(spare));
        for(int i=0;i<2;i++){var response=revoke(target.session().sessionId(),current.accessToken());noContent(response);}
        assertFalse(redis.hasKey(key(target)));assertNull(redis.opsForZSet().score(index(owner),digest(target)));
        assertTrue(redis.hasKey(key(current)));assertEquals(spareBefore,redis.opsForHash().entries(key(spare)));
        assertNotNull(redis.opsForZSet().score(index(owner),digest(spare)));assertNull(sessions.find(target.accessToken()));
        var listed=send("GET","/api/v1/me/sessions",current.accessToken());assertEquals(200,listed.statusCode(),listed.body());
        assertFalse(JsonPath.<List<String>>read(listed.body(),"$.data.items[*].id").contains(target.session().sessionId()));
        assertEquals(401,send("GET","/api/v1/me",target.accessToken()).statusCode());
    }
    @Test void nonexistentAndForeignIdsReturnTheSame204WithoutTouchingOtherUsers() throws Exception {
        var current=issue(owner());var foreignOwner=owner();var foreign=issue(foreignOwner);
        var before=redis.opsForHash().entries(key(foreign));var members=redis.opsForZSet().range(index(foreignOwner),0,-1);
        noContent(revoke(id(),current.accessToken()));noContent(revoke(foreign.session().sessionId(),current.accessToken()));
        assertEquals(before,redis.opsForHash().entries(key(foreign)));assertEquals(members,redis.opsForZSet().range(index(foreignOwner),0,-1));
        assertEquals(200,send("GET","/api/v1/me",foreign.accessToken()).statusCode());
    }
    @Test void revokingCurrentSessionReturns204AndMakesItsTokenUnusable() throws Exception {
        var owner=owner();var current=issue(owner);var spare=issue(owner);
        noContent(revoke(current.session().sessionId(),current.accessToken()));assertFalse(redis.hasKey(key(current)));
        assertNull(redis.opsForZSet().score(index(owner),digest(current)));assertTrue(redis.hasKey(key(spare)));
        problem(revoke(current.session().sessionId(),current.accessToken()),401,"AUTHENTICATION_REQUIRED");
        assertEquals(200,send("GET","/api/v1/me",spare.accessToken()).statusCode());
    }
    @ParameterizedTest @ValueSource(strings={"EXPIRED","MISSING","OLD_VERSION","DISPLAY_DATA"})
    void staleOrDamagedDisplayMetadataDoesNotPreventRemovingTheOwnedTarget(String state) throws Exception {
        var owner=owner();var current=issue(owner);var target=issue(owner);
        switch(state){
            case "EXPIRED"->redis.opsForHash().put(key(target),"expiresAt",Long.toString(Instant.now().minusSeconds(1).toEpochMilli()));
            case "MISSING"->redis.delete(key(target));
            case "OLD_VERSION"->redis.opsForHash().put(key(target),"authVersion","0");
            default->{redis.opsForHash().put(key(target),"createdAt","invalid-display-time");redis.opsForHash().delete(key(target),"deviceLabel","lastSeenAt");}
        }
        noContent(revoke(target.session().sessionId(),current.accessToken()));assertFalse(redis.hasKey(key(target)));assertTrue(redis.hasKey(key(current)));
        if(!state.equals("MISSING"))assertNull(redis.opsForZSet().score(index(owner),digest(target)));
    }
    @Test void repeatingAnOldIdDoesNotRevokeTheNewLoginSession() throws Exception {
        var owner=owner();var current=issue(owner);var old=issue(owner);noContent(revoke(old.session().sessionId(),current.accessToken()));
        var fresh=issue(owner);var before=redis.opsForHash().entries(key(fresh));
        noContent(revoke(old.session().sessionId(),current.accessToken()));assertEquals(before,redis.opsForHash().entries(key(fresh)));
        assertTrue(redis.hasKey(key(current)));assertEquals(1L,users.selectById(owner.getId()).getAuthVersion());
    }
    @ParameterizedTest @ValueSource(strings={"DELETE","INDEX","EXPIRE","IDENTITY","VERSION"})
    void invalidatedCurrentCredentialsCannotRevokeAnotherSession(String change) throws Exception {
        var owner=owner();var current=issue(owner);var target=issue(owner);var before=redis.opsForHash().entries(key(target));
        changeCurrentAfterAuthentication(owner,current,change);
        problem(revoke(target.session().sessionId(),current.accessToken()),401,"AUTHENTICATION_REQUIRED");
        assertEquals(before,redis.opsForHash().entries(key(target)));assertNotNull(redis.opsForZSet().score(index(owner),digest(target)));
    }
    @ParameterizedTest @ValueSource(strings={"DELETE","INDEX","EXPIRE"})
    void alreadyAuthenticatedSelfRevocationStillReturns204AfterConcurrentLogoutOrExpiry(String change) throws Exception {
        var owner=owner();var current=issue(owner);var spare=issue(owner);changeCurrentAfterAuthentication(owner,current,change);
        noContent(revoke(current.session().sessionId(),current.accessToken()));assertFalse(redis.hasKey(key(current)));
        assertNull(redis.opsForZSet().score(index(owner),digest(current)));assertTrue(redis.hasKey(key(spare)));
    }
    @ParameterizedTest @CsvSource({"DISABLED,403,ACCOUNT_RESTRICTED","EMAIL,403,PERMISSION_DENIED","VERSION,401,AUTHENTICATION_REQUIRED"})
    void accountEligibilityIsRecheckedAfterAuthentication(String change,int status,String code) throws Exception {
        var owner=owner();var current=issue(owner);var target=issue(owner);
        doAnswer(call->{var principal=call.callRealMethod();switch(change){
            case "DISABLED"->jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?",owner.getId());
            case "EMAIL"->jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?",owner.getId());
            default->jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?",owner.getId());}
            return principal;}).when(authentication).authenticate(current.accessToken());
        problem(revoke(target.session().sessionId(),current.accessToken()),status,code);assertTrue(redis.hasKey(key(target)));
        verify(sessions,never()).revokeOwned(anyString(),anyLong(),anyString(),anyString(),anyString());
    }
    @Test void writeBlockedUsersCanRevokeSessionsWithoutCreatorOrAdminRoles() throws Exception {
        var owner=owner();var current=issue(owner);var target=issue(owner);
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?",owner.getId());jdbc.update("DELETE FROM app_user_role WHERE user_id=?",owner.getId());
        noContent(revoke(target.session().sessionId(),current.accessToken()));assertFalse(redis.hasKey(key(target)));
        noContent(revoke(current.session().sessionId(),current.accessToken()));assertFalse(redis.hasKey(key(current)));
    }
    @Test void invalidIdsExtraInputAndMissingAuthenticationDoNotConsumeQuota() throws Exception {
        var current=issue(owner());
        problem(revoke("target",null),401,"AUTHENTICATION_REQUIRED");problem(revoke("target","invalid"),401,"AUTHENTICATION_REQUIRED");
        for(String invalid:List.of("bad!","a".repeat(65)))problem(revoke(invalid,current.accessToken()),422,"VALIDATION_ERROR");
        for(String query:List.of("userId=other","expectedVersion=1","sessionId=other"))
            problem(send("DELETE","/api/v1/me/sessions/target?"+query,current.accessToken()),400,"MALFORMED_REQUEST");
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me/sessions/target"))
                .header("Authorization","Bearer "+current.accessToken()).method("DELETE",HttpRequest.BodyPublishers.ofString("{}"));
        problem(http.send(request.build(),HttpResponse.BodyHandlers.ofString()),400,"MALFORMED_REQUEST");
        assertNull(redis.opsForValue().get(sourceKey()));assertTrue(redis.hasKey(key(current)));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void exhaustedSourceOrUserBudgetReturns429BeforeDeletion(boolean user) throws Exception {
        var owner=owner();var current=issue(owner);var target=issue(owner);redis.opsForValue().set(user?userKey(owner):sourceKey(),user?"60":"120",Duration.ofSeconds(40));
        var response=revoke(target.session().sessionId(),current.accessToken());problem(response,429,"RATE_LIMITED");
        assertTrue(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow())>0);assertTrue(redis.hasKey(key(target)));
        verify(transactions,never()).revoke(anyString(),any());
    }
    @Test void repeated204RequestsCountInAnIndependentBudget() throws Exception {
        var owner=owner();var current=issue(owner);String queryKey=PREFIX+"{user-session-query}:source:"+TokenDigest.sha256("127.0.0.1");
        redis.opsForValue().set(queryKey,"120",Duration.ofSeconds(40));
        for(int i=0;i<3;i++)noContent(revoke("absent",current.accessToken()));
        assertEquals("3",redis.opsForValue().get(sourceKey()));assertEquals("3",redis.opsForValue().get(userKey(owner)));
        assertEquals("120",redis.opsForValue().get(queryKey));
    }
    @ParameterizedTest @ValueSource(strings={"FOREIGN_OWNER","MISSING_ID","WRONG_TYPE","DUPLICATE_ID","BAD_DIGEST","OVERSIZED_INDEX","INDEX_TYPE"})
    void corruptionIsDetectedBeforeAnyTargetDeletion(String damage) throws Exception {
        var owner=owner();var current=issue(owner);var target=issue(owner);var bad=issue(owner);
        redis.opsForZSet().add(index(owner),digest(target),0);redis.opsForZSet().add(index(owner),digest(current),1);redis.opsForZSet().add(index(owner),digest(bad),2);
        switch(damage){
            case "FOREIGN_OWNER"->redis.opsForHash().put(key(bad),"userId",owner().getId());
            case "MISSING_ID"->redis.opsForHash().delete(key(bad),"sessionId");
            case "WRONG_TYPE"->{redis.delete(key(bad));redis.opsForValue().set(key(bad),"invalid");}
            case "DUPLICATE_ID"->redis.opsForHash().put(key(bad),"sessionId",target.session().sessionId());
            case "BAD_DIGEST"->redis.opsForZSet().add(index(owner),"untrusted-key",3);
            case "OVERSIZED_INDEX"->{for(int i=0;i<100;i++)redis.opsForZSet().add(index(owner),TokenDigest.sha256("orphan"+i),3);}
            default->doAnswer(call->{var principal=call.callRealMethod();redis.delete(index(owner));redis.opsForValue().set(index(owner),"wrong-type");return principal;}).when(authentication).authenticate(current.accessToken());
        }
        var before=redis.opsForHash().entries(key(target));var response=revoke(target.session().sessionId(),current.accessToken());
        problem(response,503,"DEPENDENCY_UNAVAILABLE");assertEquals(before,redis.opsForHash().entries(key(target)));
        assertTrue(redis.hasKey(key(current)));assertTrue(redis.hasKey(key(bad)));assertFalse(response.body().contains("SESSION_"));assertFalse(response.body().contains(current.accessToken()));
    }
    @Test void danglingIndexEntriesDoNotBlockRevocationAndOtherExpiryTimesAreNotExtended() throws Exception {
        var owner=owner();var current=issue(owner);var target=issue(owner);var missing=issue(owner);redis.delete(key(missing));
        String expiry=(String)redis.opsForHash().get(key(current),"expiresAt");long ttlBefore=redis.getExpire(key(current),TimeUnit.MILLISECONDS);
        noContent(revoke(target.session().sessionId(),current.accessToken()));assertFalse(redis.hasKey(key(target)));
        assertEquals(expiry,redis.opsForHash().get(key(current),"expiresAt"));assertTrue(redis.getExpire(key(current),TimeUnit.MILLISECONDS)<=ttlBefore);
        assertNotNull(redis.opsForZSet().score(index(owner),digest(missing)));assertFalse(redis.hasKey(key(missing)));
    }
    @Test void concurrentRepeatedRevocationsBothReturn204() throws Exception {
        var owner=owner();var current=issue(owner);var target=issue(owner);var pool=Executors.newFixedThreadPool(2);
        try{var first=pool.submit(()->revoke(target.session().sessionId(),current.accessToken()));var second=pool.submit(()->revoke(target.session().sessionId(),current.accessToken()));
            noContent(first.get(20,TimeUnit.SECONDS));noContent(second.get(20,TimeUnit.SECONDS));assertFalse(redis.hasKey(key(target)));assertTrue(redis.hasKey(key(current)));
        }finally{pool.shutdownNow();}
    }
    @Test void concurrentSelfRevocationsAuthenticatedBeforeDeletionBothReturn204() throws Exception {
        var current=issue(owner());var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        doAnswer(call->{var principal=call.callRealMethod();barrier.await(10,TimeUnit.SECONDS);return principal;}).when(authentication).authenticate(current.accessToken());
        try{var first=pool.submit(()->revoke(current.session().sessionId(),current.accessToken()));var second=pool.submit(()->revoke(current.session().sessionId(),current.accessToken()));
            noContent(first.get(20,TimeUnit.SECONDS));noContent(second.get(20,TimeUnit.SECONDS));assertFalse(redis.hasKey(key(current)));
        }finally{pool.shutdownNow();}
    }
    @Test void twoSessionsRevokingEachOtherCannotBothActAfterTheFirstRevocation() throws Exception {
        var owner=owner();var first=issue(owner);var second=issue(owner);var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        doAnswer(call->{var principal=call.callRealMethod();barrier.await(10,TimeUnit.SECONDS);return principal;}).when(authentication).authenticate(anyString());
        try{var a=pool.submit(()->revoke(second.session().sessionId(),first.accessToken()));var b=pool.submit(()->revoke(first.session().sessionId(),second.accessToken()));
            assertEquals(List.of(204,401),List.of(a.get(20,TimeUnit.SECONDS).statusCode(),b.get(20,TimeUnit.SECONDS).statusCode()).stream().sorted().toList());
            assertNotEquals(redis.hasKey(key(first)),redis.hasKey(key(second)));assertEquals(1L,redis.opsForZSet().size(index(owner)));
        }finally{pool.shutdownNow();}
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void redisAndTransactionFailuresReturn503WithoutReportingSuccess(boolean redisFailure) throws Exception {
        var owner=owner();var current=issue(owner);var target=issue(owner);var failure=new TransientDataAccessResourceException("private connection detail");
        if(redisFailure)doThrow(failure).when(sessions).revokeOwned(anyString(),anyLong(),anyString(),anyString(),anyString());
        else doThrow(failure).when(transactions).revoke(anyString(),any());
        var response=revoke(target.session().sessionId(),current.accessToken());problem(response,503,"DEPENDENCY_UNAVAILABLE");assertTrue(redis.hasKey(key(target)));assertFalse(response.body().contains("private"));
    }
    private void changeCurrentAfterAuthentication(UserAccount owner,IssuedSession current,String change){
        doAnswer(call->{var principal=call.callRealMethod();switch(change){
            case "DELETE"->redis.delete(key(current));
            case "INDEX"->redis.opsForZSet().remove(index(owner),digest(current));
            case "EXPIRE"->redis.opsForHash().put(key(current),"expiresAt",Long.toString(Instant.now().minusSeconds(1).toEpochMilli()));
            case "IDENTITY"->redis.opsForHash().put(key(current),"sessionId","changed");
            default->redis.opsForHash().put(key(current),"authVersion","2");}
            return principal;}).when(authentication).authenticate(current.accessToken());
    }
    private String sourceKey(){return PREFIX+"{user-session-revocation}:source:"+TokenDigest.sha256("127.0.0.1");}
    private String userKey(UserAccount owner){return PREFIX+"{user-session-revocation}:user:"+TokenDigest.sha256(owner.getId());}
    private void problem(HttpResponse<String> response,int status,String code){assertEquals(status,response.statusCode(),response.body());assertEquals(code,JsonPath.read(response.body(),"$.code"));assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertTrue(response.headers().firstValue("X-Request-Id").isPresent());}
    private UserAccount owner(){var value=LoginTestSupport.account();value.setId(id());value.setEmail("revoke-session-"+value.getId()+"@example.test");value.setEmailNormalized(value.getEmail());
        owners.add(value.getId());users.insert(value);roles.insertDefaultRole(value.getId(),value.getCreatedAt());return value;}
    private IssuedSession issue(UserAccount owner){return sessions.create(owner.getId(),1,"测试设备");}
    private String digest(IssuedSession session){return TokenDigest.sha256(session.accessToken());}
    private String key(IssuedSession session){return PREFIX+"{sessions}:token:"+digest(session);}
    private String index(UserAccount owner){return PREFIX+"{sessions}:user:"+owner.getId();}
    private HttpResponse<String> revoke(String target,String token) throws Exception{return send("DELETE","/api/v1/me/sessions/"+target,token);}
    private HttpResponse<String> send(String method,String path,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).method(method,HttpRequest.BodyPublishers.noBody());
        if(token!=null)request.header("Authorization","Bearer "+token);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void noContent(HttpResponse<String> response){assertEquals(204,response.statusCode(),response.body());assertEquals("",response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertTrue(response.headers().firstValue("X-Request-Id").isPresent());}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
}
