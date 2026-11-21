package com.freecontent.community.user.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.config.UserRestrictionProperties;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.AdminAccountChangeMapper;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRestrictionMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.freecontent.community.user.model.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false",
        "app.media.processing.enabled=false"})
class UserRestrictionFlowIT {
    private static final String PREFIX = "test:user-restriction:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private RedisSessionStore sessions;
    @MockitoSpyBean private UserRestrictionMapper restrictions;
    @MockitoSpyBean private AdminAccountChangeMapper accountChanges;
    @Autowired private StringRedisTemplate redis;
    @Autowired private UserRestrictionProperties quota;
    private final List<String> fixtures = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.user.restriction.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String userId : fixtures) {
            jdbc.update("DELETE FROM user_restriction_audit WHERE user_id=? OR operator_id=?", userId, userId);
        }
        for (String userId : fixtures) {
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", userId);
            users.deleteById(userId);
        }
    }

    @Test
    void restrictionReturnsTheNewStatusAndVersionWithMaskedEmail() throws Exception {
        var admin = account(UserRole.ADMIN);
        var target = account(UserRole.USER);

        var response = restrict(target.getId(), 1, "WRITE_BLOCKED", token(admin));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("WRITE_BLOCKED", JsonPath.read(response.body(), "$.data.accountStatus"));
        assertEquals(2, JsonPath.<Integer>read(response.body(), "$.data.version"));
        assertEquals("***@example.test", JsonPath.read(response.body(), "$.data.maskedEmail"));
        assertEquals(UserAccount.Status.WRITE_BLOCKED, users.selectById(target.getId()).getAccountStatus());
        var audit = jdbc.queryForMap("""
                SELECT operator_id, before_status, after_status, before_version, after_version, reason, request_id
                FROM user_restriction_audit WHERE user_id=?
                """, target.getId());
        assertEquals(admin.getId(), audit.get("operator_id"));
        assertEquals("ACTIVE", audit.get("before_status"));
        assertEquals("WRITE_BLOCKED", audit.get("after_status"));
        assertEquals(1, audit.get("before_version"));
        assertEquals(2, audit.get("after_version"));
        assertEquals("测试账号限制", audit.get("reason"));
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), audit.get("request_id"));
    }

    @Test
    void writeBlockedSessionCanReadAndLogOutButCannotWriteBusinessData() throws Exception {
        var admin = account(UserRole.ADMIN);
        var target = account(UserRole.USER);
        String targetToken = token(target);
        assertEquals(200, restrict(target.getId(), 1, "WRITE_BLOCKED", token(admin)).statusCode());

        assertEquals("WRITE_BLOCKED", JsonPath.read(request("GET", "/api/v1/me", null, targetToken).body(), "$.data.accountStatus"));
        problem(request("PATCH", "/api/v1/me", "{\"expectedVersion\":2,\"displayName\":\"renamed\"}", targetToken),
                403, "ACCOUNT_RESTRICTED");
        assertEquals(204, request("DELETE", "/api/v1/auth/sessions/current", null, targetToken).statusCode());
        assertEquals(1L, users.selectById(target.getId()).getAuthVersion());
    }

    @Test
    void disablingRevokesEveryExistingSessionAndRestoringNeverRevalidatesThem() throws Exception {
        var admin = account(UserRole.ADMIN);
        var target = account(UserRole.USER);
        String adminToken = token(admin);
        String first = token(target);
        String second = token(target);

        assertEquals(200, restrict(target.getId(), 1, "DISABLED", adminToken).statusCode());

        assertEquals(2L, users.selectById(target.getId()).getAuthVersion());
        assertNull(sessions.find(first));
        assertNull(sessions.find(second));
        assertEquals(200, restrict(target.getId(), 2, "ACTIVE", adminToken).statusCode());
        problem(request("GET", "/api/v1/me", null, first), 401, "AUTHENTICATION_REQUIRED");
        assertEquals(200, request("GET", "/api/v1/me", null, token(users.selectById(target.getId()))).statusCode());
    }

    @Test
    void oldTokensRemainInvalidAfterRestoreWhenRedisCleanupFails() throws Exception {
        var admin = account(UserRole.ADMIN);
        var target = account(UserRole.USER);
        String adminToken = token(admin);
        String oldToken = token(target);
        doThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE))
                .when(sessions).revokeBeforeVersion(target.getId(), 2L);

        assertEquals(200, restrict(target.getId(), 1, "DISABLED", adminToken).statusCode());
        assertEquals(200, restrict(target.getId(), 2, "ACTIVE", adminToken).statusCode());

        assertNotNull(sessions.find(oldToken));
        problem(request("GET", "/api/v1/me", null, oldToken), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void noOpDoesNotIncrementVersionAndAStaleVersionStillConflicts() throws Exception {
        var admin = account(UserRole.ADMIN);
        var target = account(UserRole.USER);
        String token = token(admin);
        var before = users.selectById(target.getId());

        var noOp = restrict(target.getId(), 1, "ACTIVE", token);
        assertEquals(200, noOp.statusCode(), noOp.body());
        assertEquals(1, JsonPath.<Integer>read(noOp.body(), "$.data.version"));
        assertEquals(before.getUpdatedAt(), users.selectById(target.getId()).getUpdatedAt());
        var stale = restrict(target.getId(), 2, "ACTIVE", token);
        problem(stale, 409, "RESOURCE_VERSION_CONFLICT");
        assertEquals(1, JsonPath.<Integer>read(stale.body(), "$.currentVersion"));
        assertEquals(0, auditCount(target));
    }

    @Test
    void auditFailureRollsBackTheStatusAndBothVersions() throws Exception {
        var admin = account(UserRole.ADMIN);
        var target = account(UserRole.USER);
        String session = token(target);
        doThrow(new DataAccessResourceFailureException("audit unavailable")).when(restrictions).insertAudit(any());

        problem(restrict(target.getId(), 1, "DISABLED", token(admin)), 503, "DEPENDENCY_UNAVAILABLE");

        var after = users.selectById(target.getId());
        assertEquals(UserAccount.Status.ACTIVE, after.getAccountStatus());
        assertEquals(1, after.getVersion());
        assertEquals(1L, after.getAuthVersion());
        assertEquals(0, auditCount(target));
        assertEquals(200, request("GET", "/api/v1/me", null, session).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISABLED", "WRITE_BLOCKED"})
    void onlyOneOfTwoConcurrentSelfRestrictionsCanRemoveAnEffectiveAdmin(String state) throws Exception {
        var first = account(UserRole.ADMIN);
        var second = account(UserRole.ADMIN);
        // Keep pre-existing administrators in the local database out of the fixture population.
        doAnswer(call -> jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM app_user_role r JOIN app_user u ON u.id=r.user_id
                    WHERE u.id IN (?,?) AND u.id<>? AND r.role_code='ADMIN'
                      AND u.account_status='ACTIVE' AND u.email_verified=TRUE
                )
                """, Boolean.class, first.getId(), second.getId(), call.getArgument(0, String.class)))
                .when(accountChanges).hasOtherEffectiveAdmin(anyString());
        String firstToken = token(first);
        String secondToken = token(second);

        var a = http.sendAsync(restrictionRequest(first.getId(), 1, state, firstToken), HttpResponse.BodyHandlers.ofString());
        var b = http.sendAsync(restrictionRequest(second.getId(), 1, state, secondToken), HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(a, b).get(25, TimeUnit.SECONDS);

        assertEquals(List.of(200, 409), List.of(a.join().statusCode(), b.join().statusCode()).stream().sorted().toList());
        problem(a.join().statusCode() == 409 ? a.join() : b.join(), 409, "LAST_ADMIN_PROTECTED");
        assertEquals(1, auditCount(first) + auditCount(second));
    }

    @Test
    void nonAdminAndLimitedAdminAreRejected() throws Exception {
        var moderator = account(UserRole.MODERATOR);
        var admin = account(UserRole.ADMIN);
        var target = account(UserRole.USER);
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", admin.getId());

        problem(restrict(target.getId(), 1, "DISABLED", token(moderator)), 403, "PERMISSION_DENIED");
        problem(restrict(target.getId(), 1, "DISABLED", token(admin)), 403, "ACCOUNT_RESTRICTED");
        assertEquals(0, auditCount(target));
    }

    @Test
    void invalidInputAndMissingTargetReturnContractErrors() throws Exception {
        var admin = account(UserRole.ADMIN);
        String token = token(admin);
        String path = "/api/v1/admin/users/" + admin.getId() + "/restriction";
        problem(request("PUT", path, "{\"status\":\"ACTIVE\",\"reason\":\"test\"}", token), 422, "VALIDATION_ERROR");
        problem(request("PUT", path, "{\"expectedVersion\":\"1\",\"status\":\"ACTIVE\",\"reason\":\"test\"}", token),
                400, "MALFORMED_REQUEST");
        problem(restrict("bad!", 1, "ACTIVE", token), 422, "VALIDATION_ERROR");
        problem(restrict("missing_" + UUID.randomUUID(), 1, "DISABLED", token), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void exhaustedRestrictionQuotaReturnsRetryAfterWithoutChangingTheAccount() throws Exception {
        var admin = account(UserRole.ADMIN);
        var target = account(UserRole.USER);
        String key = PREFIX + "{user-restriction}:user:" + TokenDigest.sha256(admin.getId());
        redis.opsForValue().set(key, Integer.toString(quota.userPerMinuteLimit()), Duration.ofSeconds(40));

        var response = restrict(target.getId(), 1, "DISABLED", token(admin));

        problem(response, 429, "RATE_LIMITED");
        assertTrue(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
        assertEquals(UserAccount.Status.ACTIVE, users.selectById(target.getId()).getAccountStatus());
    }

    private int auditCount(UserAccount user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_restriction_audit WHERE user_id=?", Integer.class, user.getId());
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.read(response.body(), "$.code"));
    }

    private UserAccount account(UserRole role) {
        var account = LoginTestSupport.account();
        account.setId(UUID.randomUUID().toString().replace("-", ""));
        account.setEmail("restriction-" + account.getId() + "@example.test");
        account.setEmailNormalized(account.getEmail());
        fixtures.add(account.getId());
        users.insert(account);
        roles.insertDefaultRole(account.getId(), account.getCreatedAt());
        if (role != UserRole.USER) {
            jdbc.update("INSERT INTO app_user_role(user_id, role_code, created_at) VALUES(?, ?, ?)",
                    account.getId(), role.name(), account.getCreatedAt());
        }
        return account;
    }

    private String token(UserAccount account) {
        return sessions.create(account.getId(), account.getAuthVersion(), "user-restriction-it").accessToken();
    }

    private HttpResponse<String> restrict(String id, int version, String status, String token) throws Exception {
        return http.send(restrictionRequest(id, version, status, token), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest restrictionRequest(String id, int version, String status, String token) {
        String json = """
                {"expectedVersion":%d,"status":"%s","reason":"测试账号限制"}
                """.formatted(version, status);
        return buildRequest("PUT", "/api/v1/admin/users/" + id + "/restriction", json, token);
    }

    private HttpResponse<String> request(String method, String path, String json, String token) throws Exception {
        return http.send(buildRequest(method, path, json, token), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest buildRequest(String method, String path, String json, String token) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token);
        if (json != null) builder.header("Content-Type", "application/json");
        return builder.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json)).build();
    }
}
