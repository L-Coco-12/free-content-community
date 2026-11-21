package com.freecontent.community.user.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.*;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.user.service.transaction.MySessionsTransactionService;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
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
class MySessionsFlowIT {
    private static final String PREFIX="test:my-sessions:"+UUID.randomUUID()+":";
    @Value("${local.server.port}") int port;
    @Autowired UserAccountMapper users;
    @Autowired UserRoleMapper roles;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean RedisSessionStore sessions;
    @MockitoSpyBean SessionAuthenticationService authentication;
    @MockitoSpyBean MySessionsTransactionService transactions;
    @Autowired StringRedisTemplate redis;
    private final List<String> owners=new ArrayList<>();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry){
        for(String group:List.of("auth.login","user.session-query","user.public-query"))registry.add("app."+group+".redis-key-prefix",()->PREFIX);
    }
    @AfterEach void cleanup(){
        reset(sessions,authentication,transactions);
        var keys=redis.keys(PREFIX+"*");if(keys!=null && !keys.isEmpty())redis.delete(keys);
        for(String owner:owners){jdbc.update("DELETE FROM app_user_role WHERE user_id=?",owner);users.deleteById(owner);}
    }
    @Test void listsOnlyMySessionDisplayFieldsInCreationTimeAndIdOrderWithoutExtendingExpiry() throws Exception {
        var owner=owner();var current=issue(owner,"我的电脑");var other=issue(owner,"手机");var foreign=issue(owner(),"其他用户");
        long created=Instant.now().minusSeconds(60).toEpochMilli();
        for(var item:List.of(current,other))redis.opsForHash().putAll(key(item),Map.of("createdAt",Long.toString(created),"lastSeenAt",Long.toString(created)));
        long ttlBefore=redis.getExpire(key(current),java.util.concurrent.TimeUnit.MILLISECONDS);
        String expiry=(String)redis.opsForHash().get(key(current),"expiresAt");
        var response=get("",current.accessToken());ok(response);
        var expected=List.of(current.session().sessionId(),other.session().sessionId()).stream().sorted(Comparator.reverseOrder()).toList();
        assertEquals(expected,JsonPath.read(response.body(),"$.data.items[*].id"));
        List<Map<String,Object>> items=JsonPath.read(response.body(),"$.data.items");
        for(var item:items){assertEquals(Set.of("id","createdAt","expiresAt","lastSeenAt","current","deviceLabel"),item.keySet());
            assertEquals(item.get("id").equals(current.session().sessionId()),item.get("current"));assertEquals(Instant.ofEpochMilli(created).toString(),item.get("createdAt"));}
        assertEquals(1,items.stream().filter(item->Boolean.TRUE.equals(item.get("current"))).count());
        assertTrue(items.stream().allMatch(item->Instant.parse((String)item.get("expiresAt")).isAfter(Instant.parse((String)item.get("createdAt")))));
        assertFalse(response.body().contains(foreign.session().sessionId()));assertFalse(response.body().contains(current.accessToken()));
        assertFalse(response.body().contains(TokenDigest.sha256(current.accessToken())));assertFalse(response.body().contains("authVersion"));
        assertEquals(expiry,redis.opsForHash().get(key(current),"expiresAt"));assertTrue(redis.getExpire(key(current),java.util.concurrent.TimeUnit.MILLISECONDS)<=ttlBefore);
    }
    @Test void filtersExpiredRevokedMissingAndOldVersionEntriesWithoutChangingOtherSessions() throws Exception {
        var owner=owner();var current=issue(owner,"当前");var expired=issue(owner,"过期");var missing=issue(owner,"悬空");var old=issue(owner,"旧版本");var revoked=issue(owner,"撤销");
        redis.opsForHash().put(key(expired),"expiresAt",Long.toString(Instant.now().minusSeconds(1).toEpochMilli()));
        redis.delete(key(missing));redis.opsForHash().put(key(old),"authVersion","2");
        sessions.revoke(owner.getId(),revoked.session().sessionId(),TokenDigest.sha256(revoked.accessToken()));
        var before=redis.opsForHash().entries(key(old));var members=redis.opsForZSet().range(index(owner),0,-1);
        var response=get("",current.accessToken());ok(response);assertEquals(List.of(current.session().sessionId()),JsonPath.read(response.body(),"$.data.items[*].id"));
        assertEquals(before,redis.opsForHash().entries(key(old)));assertEquals(members,redis.opsForZSet().range(index(owner),0,-1));assertFalse(redis.hasKey(key(missing)));
    }
    @Test void creationTimeTakesPriorityOverSessionIdAndOtherDevicesAreNotTouched() throws Exception {
        var owner=owner();var current=issue(owner,"  我的电脑  ");var other=issue(owner,null);long older=Instant.now().minusSeconds(90).toEpochMilli(),newer=older+1000;
        redis.opsForHash().putAll(key(current),Map.of("sessionId","session_z","createdAt",Long.toString(older),"lastSeenAt",Long.toString(older)));
        redis.opsForHash().putAll(key(other),Map.of("sessionId","session_a","createdAt",Long.toString(newer),"lastSeenAt",Long.toString(newer)));
        var before=redis.opsForHash().entries(key(other));var response=get("",current.accessToken());ok(response);
        assertEquals(List.of("session_a","session_z"),JsonPath.read(response.body(),"$.data.items[*].id"));
        assertEquals(List.of(false,true),JsonPath.read(response.body(),"$.data.items[*].current"));
        assertEquals(List.of("","我的电脑"),JsonPath.read(response.body(),"$.data.items[*].deviceLabel"));
        assertEquals(before,redis.opsForHash().entries(key(other)));
        assertTrue(Instant.parse(JsonPath.read(response.body(),"$.data.items[1].lastSeenAt")).isAfter(Instant.ofEpochMilli(older)));
    }
    @ParameterizedTest @ValueSource(strings={"DELETE","INDEX","EXPIRE","IDENTITY","VERSION"})
    void aCurrentSessionRevokedAfterAuthenticationCannotReadTheList(String change) throws Exception {
        var owner=owner();var current=issue(owner,"当前");issue(owner,"其他设备");
        doAnswer(call->{var principal=call.callRealMethod();switch(change){
            case "DELETE"->redis.delete(key(current));
            case "INDEX"->redis.opsForZSet().remove(index(owner),TokenDigest.sha256(current.accessToken()));
            case "EXPIRE"->redis.opsForHash().put(key(current),"expiresAt",Long.toString(Instant.now().minusSeconds(1).toEpochMilli()));
            case "IDENTITY"->redis.opsForHash().put(key(current),"sessionId","changed");
            default->redis.opsForHash().put(key(current),"authVersion","2");}
            return principal;}).when(authentication).authenticate(current.accessToken());
        problem(get("",current.accessToken()),401,"AUTHENTICATION_REQUIRED");
    }
    @ParameterizedTest @CsvSource({"DISABLED,403,ACCOUNT_RESTRICTED","EMAIL,403,PERMISSION_DENIED","VERSION,401,AUTHENTICATION_REQUIRED"})
    void accountEligibilityIsRecheckedAfterAuthentication(String change,int status,String code) throws Exception {
        var owner=owner();var current=issue(owner,"当前");
        doAnswer(call->{var principal=call.callRealMethod();switch(change){
            case "DISABLED"->jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?",owner.getId());
            case "EMAIL"->jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?",owner.getId());
            default->jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?",owner.getId());}
            return principal;}).when(authentication).authenticate(current.accessToken());
        problem(get("",current.accessToken()),status,code);verify(sessions,never()).listActive(anyString(),anyLong(),anyString(),anyString());
    }
    @Test void writeBlockedUsersCanReadSessionsWithoutCreatorOrAdminRoles() throws Exception {
        var owner=owner();var current=issue(owner,"当前");jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?",owner.getId());
        jdbc.update("DELETE FROM app_user_role WHERE user_id=?",owner.getId());ok(get("",current.accessToken()));
    }
    @ParameterizedTest @ValueSource(strings={"sessionId=x","userId=other","cursor=x","limit=1","limit=1&limit=2"})
    void undefinedQueryParametersAreRejectedBeforeQuota(String query) throws Exception {
        var current=issue(owner(),null);problem(get(query,current.accessToken()),400,"MALFORMED_REQUEST");assertNull(redis.opsForValue().get(sourceKey()));
    }
    @Test void requestBodyAndMissingOrInvalidCredentialsAreRejected() throws Exception {
        problem(get("",null),401,"AUTHENTICATION_REQUIRED");problem(get("","invalid"),401,"AUTHENTICATION_REQUIRED");
        var current=issue(owner(),null);var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me/sessions"))
                .header("Authorization","Bearer "+current.accessToken()).method("GET",HttpRequest.BodyPublishers.ofString("{}"));
        problem(http.send(request.build(),HttpResponse.BodyHandlers.ofString()),400,"MALFORMED_REQUEST");assertNull(redis.opsForValue().get(sourceKey()));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void exhaustedSourceOrUserQuotaReturns429WithRetryAfter(boolean user) throws Exception {
        var owner=owner();var current=issue(owner,null);redis.opsForValue().set(user?userKey(owner):sourceKey(),user?"60":"120",Duration.ofSeconds(40));
        var response=get("",current.accessToken());problem(response,429,"RATE_LIMITED");assertTrue(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow())>0);
        verify(transactions,never()).list(any());
    }
    @Test void repeatedQueriesUseAnIndependentBudget() throws Exception {
        var owner=owner();var current=issue(owner,null);
        String publicKey=PREFIX+"{user-public-query}:source:"+TokenDigest.sha256("127.0.0.1");redis.opsForValue().set(publicKey,"120",Duration.ofSeconds(40));
        ok(get("",current.accessToken()));ok(get("",current.accessToken()));assertEquals("2",redis.opsForValue().get(sourceKey()));
        assertEquals("2",redis.opsForValue().get(userKey(owner)));assertEquals("120",redis.opsForValue().get(publicKey));
    }
    @ParameterizedTest @ValueSource(strings={"OWNER","MISSING_FIELD","WRONG_TYPE","DUPLICATE_ID","TIME","INDEX_TYPE","OVERSIZED_INDEX","BAD_DIGEST"})
    void damagedSessionDataReturns503WithoutLeakingOtherUsersOrInternalDetails(String damage) throws Exception {
        var owner=owner();var current=issue(owner,null);var other=issue(owner,"其他设备");
        switch(damage){
            case "OWNER"->redis.opsForHash().put(key(other),"userId",owner().getId());
            case "MISSING_FIELD"->redis.opsForHash().delete(key(other),"lastSeenAt");
            case "WRONG_TYPE"->{redis.delete(key(other));redis.opsForValue().set(key(other),"invalid");}
            case "DUPLICATE_ID"->redis.opsForHash().put(key(other),"sessionId",current.session().sessionId());
            case "TIME"->redis.opsForHash().put(key(other),"createdAt","not-a-time");
            case "OVERSIZED_INDEX"->{for(int i=0;i<100;i++)redis.opsForZSet().add(index(owner),TokenDigest.sha256("orphan"+i),0);}
            case "BAD_DIGEST"->redis.opsForZSet().add(index(owner),"untrusted-key",0);
            default->doAnswer(call->{var principal=call.callRealMethod();redis.delete(index(owner));redis.opsForValue().set(index(owner),"wrong-type");return principal;}).when(authentication).authenticate(current.accessToken());
        }
        var response=get("",current.accessToken());problem(response,503,"DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("SESSION_"));assertFalse(response.body().contains(current.accessToken()));assertFalse(response.body().contains("其他设备"));
        assertTrue(redis.hasKey(key(current)));assertTrue(redis.hasKey(key(other)));
    }
    @Test void filteringUsesExactBigintVersionsWithoutLuaFloatingPointRounding() throws Exception {
        var owner=owner();long version=9007199254740993L;jdbc.update("UPDATE app_user SET auth_version=? WHERE id=?",version,owner.getId());
        var current=sessions.create(owner.getId(),version,"当前");var old=sessions.create(owner.getId(),version,"旧设备");
        redis.opsForHash().put(key(old),"authVersion",Long.toString(version-1));
        var response=get("",current.accessToken());ok(response);assertEquals(List.of(current.session().sessionId()),JsonPath.read(response.body(),"$.data.items[*].id"));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void listReadAndAccountDataFailuresAre503(boolean redisFailure) throws Exception {
        var current=issue(owner(),null);var failure=new TransientDataAccessResourceException("private connection detail");
        if(redisFailure)doThrow(failure).when(sessions).listActive(anyString(),anyLong(),anyString(),anyString());else doThrow(failure).when(transactions).list(any());
        var response=get("",current.accessToken());problem(response,503,"DEPENDENCY_UNAVAILABLE");assertFalse(response.body().contains("private"));
    }
    @Test void generatedSchemaAndSecurityMatchTheSessionListContract() throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v3/api-docs")).timeout(Duration.ofSeconds(45)).GET();
        var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());
        assertEquals("listMySessions",JsonPath.read(response.body(),"$.paths['/api/v1/me/sessions'].get.operationId"));
        assertEquals(List.of(Map.of("bearerAuth",List.of())),JsonPath.read(response.body(),"$.paths['/api/v1/me/sessions'].get.security"));
        Map<String,Object> fields=JsonPath.read(response.body(),"$.components.schemas.Session.properties");
        var expected=Set.of("id","createdAt","expiresAt","lastSeenAt","current","deviceLabel");assertEquals(expected,fields.keySet());
        assertEquals(expected,new HashSet<>(JsonPath.<List<String>>read(response.body(),"$.components.schemas.Session.required")));
        assertEquals(Set.of("items"),JsonPath.<Map<String,Object>>read(response.body(),"$.components.schemas.SessionList.properties").keySet());
    }
    private String sourceKey(){return PREFIX+"{user-session-query}:source:"+TokenDigest.sha256("127.0.0.1");}
    private String userKey(UserAccount owner){return PREFIX+"{user-session-query}:user:"+TokenDigest.sha256(owner.getId());}
    private void problem(HttpResponse<String> response,int status,String code){assertEquals(status,response.statusCode(),response.body());assertEquals(code,JsonPath.read(response.body(),"$.code"));assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertTrue(response.headers().firstValue("X-Request-Id").isPresent());}
    private UserAccount owner(){var value=LoginTestSupport.account();value.setId(id());value.setEmail("sessions-"+value.getId()+"@example.test");value.setEmailNormalized(value.getEmail());
        owners.add(value.getId());users.insert(value);roles.insertDefaultRole(value.getId(),value.getCreatedAt());return value;}
    private IssuedSession issue(UserAccount owner,String device){return sessions.create(owner.getId(),1,device);}
    private String key(IssuedSession session){return PREFIX+"{sessions}:token:"+TokenDigest.sha256(session.accessToken());}
    private String index(UserAccount owner){return PREFIX+"{sessions}:user:"+owner.getId();}
    private HttpResponse<String> get(String query,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me/sessions"+(query.isEmpty()?"":"?"+query))).timeout(Duration.ofSeconds(20)).GET();
        if(token!=null)request.header("Authorization","Bearer "+token);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void ok(HttpResponse<String> response){assertEquals(200,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertTrue(response.headers().firstValue("X-Request-Id").isPresent());}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
}
