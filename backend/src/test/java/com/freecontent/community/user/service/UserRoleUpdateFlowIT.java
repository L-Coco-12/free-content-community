package com.freecontent.community.user.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.config.UserRoleUpdateProperties;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.AdminAccountChangeMapper;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.freecontent.community.user.mapper.UserRoleUpdateMapper;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionTemplate;
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
class UserRoleUpdateFlowIT {
    private static final String PREFIX = "test:user-role-update:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private RedisSessionStore sessions;
    @MockitoSpyBean private AdminAccountChangeMapper accountChanges;
    @MockitoSpyBean private UserRoleUpdateMapper writes;
    @Autowired private StringRedisTemplate redis;
    @Autowired private UserRoleUpdateProperties quota;
    @Autowired private SqlSessionTemplate sqlSession;
    private final List<String> fixtures = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.user.role-update.redis-key-prefix", () -> PREFIX);
        registry.add("app.user.restriction.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String userId : fixtures) {
            jdbc.update("DELETE FROM user_role_change_audit WHERE user_id=? OR operator_id=?", userId, userId);
            jdbc.update("DELETE FROM user_restriction_audit WHERE user_id=? OR operator_id=?", userId, userId);
        }
        for (String userId : fixtures) {
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", userId);
            users.deleteById(userId);
        }
    }

    @Test
    void replacementRemovesUnlistedRolesAndReturnsTheNewVersionWithMaskedEmail() throws Exception {
        var operator = account(UserRole.ADMIN);
        var target = account(UserRole.ADMIN);
        String oldToken = token(target);
        var originalGrant = jdbc.queryForObject("SELECT created_at FROM app_user_role WHERE user_id=? AND role_code='USER'",
                java.time.LocalDateTime.class, target.getId());

        var response = replace(target.getId(), 1, "[\"USER\",\"MODERATOR\"]", token(operator));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(2, JsonPath.<Integer>read(response.body(), "$.data.version"));
        assertEquals("***@example.test", JsonPath.read(response.body(), "$.data.maskedEmail"));
        assertEquals(Set.of("USER", "MODERATOR"), Set.copyOf(JsonPath.<List<String>>read(response.body(), "$.data.roles")));
        assertEquals(Set.of(UserRole.USER, UserRole.MODERATOR), Set.copyOf(roles.findRoles(target.getId())));
        assertEquals(originalGrant, jdbc.queryForObject("SELECT created_at FROM app_user_role WHERE user_id=? AND role_code='USER'",
                java.time.LocalDateTime.class, target.getId()));
        assertEquals(2L, users.selectById(target.getId()).getAuthVersion());
        assertNull(sessions.find(oldToken));
        var audit = jdbc.queryForMap("""
                SELECT operator_id, before_roles, after_roles, before_version, after_version, reason, request_id
                FROM user_role_change_audit WHERE user_id=?
                """, target.getId());
        assertEquals(operator.getId(), audit.get("operator_id"));
        assertEquals(List.of("USER", "ADMIN"), JsonPath.read((String) audit.get("before_roles"), "$"));
        assertEquals(List.of("USER", "MODERATOR"), JsonPath.read((String) audit.get("after_roles"), "$"));
        assertEquals(1, audit.get("before_version"));
        assertEquals(2, audit.get("after_version"));
        assertEquals("测试角色替换", audit.get("reason"));
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), audit.get("request_id"));
    }

    @Test
    void sameSetInDifferentOrderPreservesVersionAndSessionAndStaleRequestStillConflicts() throws Exception {
        var operator = account(UserRole.ADMIN);
        var target = account(UserRole.MODERATOR);
        String operatorToken = token(operator);
        String targetToken = token(target);
        var stillCurrent = replace(target.getId(), 1, "[\"MODERATOR\",\"USER\"]", operatorToken);
        assertEquals(200, stillCurrent.statusCode(), stillCurrent.body());
        assertEquals(1, JsonPath.<Integer>read(stillCurrent.body(), "$.data.version"));
        var stale = replace(target.getId(), 2, "[\"USER\",\"MODERATOR\"]", operatorToken);
        problem(stale, 409, "RESOURCE_VERSION_CONFLICT");
        assertEquals(1, JsonPath.<Integer>read(stale.body(), "$.currentVersion"));
        assertEquals(1L, users.selectById(target.getId()).getAuthVersion());
        assertEquals(200, readCurrentUser(targetToken).statusCode());
        assertEquals(0, auditCount(target));
    }

    @Test
    void oldTokenIsRejectedEvenWhenRedisSessionCleanupFails() throws Exception {
        var operator = account(UserRole.ADMIN);
        var target = account(UserRole.USER);
        String targetToken = token(target);
        doThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE))
                .when(sessions).revokeBeforeVersion(target.getId(), 2L);

        var response = replace(target.getId(), 1, "[\"USER\",\"ADMIN\"]", token(operator));

        assertEquals(200, response.statusCode(), response.body());
        assertNotNull(sessions.find(targetToken));
        problem(readCurrentUser(targetToken), 401, "AUTHENTICATION_REQUIRED");
        assertEquals(200, readCurrentUser(token(users.selectById(target.getId()))).statusCode());
    }

    @Test
    void auditFailureRollsBackRoleRowsAndVersions() throws Exception {
        var operator = account(UserRole.ADMIN);
        var target = account(UserRole.MODERATOR);
        doThrow(new DataAccessResourceFailureException("audit unavailable")).when(writes).insertAudit(any());

        problem(replace(target.getId(), 1, "[\"USER\",\"ADMIN\"]", token(operator)), 503, "DEPENDENCY_UNAVAILABLE");

        assertEquals(Set.of(UserRole.USER, UserRole.MODERATOR), Set.copyOf(roles.findRoles(target.getId())));
        var after = users.selectById(target.getId());
        assertEquals(1, after.getVersion());
        assertEquals(1L, after.getAuthVersion());
        assertEquals(0, auditCount(target));
    }

    @Test
    void roleRemovalAndRestrictionCannotConcurrentlyRemoveBothEffectiveAdmins() throws Exception {
        var first = account(UserRole.ADMIN);
        var second = account(UserRole.ADMIN);
        var firstCounted = new CountDownLatch(1);
        var secondLockAttempted = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.incrementAndGet() == 2) secondLockAttempted.countDown();
            return sqlSession.selectOne(AdminAccountChangeMapper.class.getName() + ".lockAdminAccountChanges");
        }).when(accountChanges).lockAdminAccountChanges();
        // Limit the invariant to the two fixtures so existing local administrators do not affect the test.
        doAnswer(call -> {
            String id = call.getArgument(0);
            Boolean another = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM app_user_role r JOIN app_user u ON u.id=r.user_id
                        WHERE u.id IN (?,?) AND u.id<>? AND r.role_code='ADMIN'
                          AND u.account_status='ACTIVE' AND u.email_verified=TRUE
                    )
                    """, Boolean.class, first.getId(), second.getId(), id);
            if (id.equals(first.getId())) {
                firstCounted.countDown();
                if (!secondLockAttempted.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Second lock not attempted");
            }
            return another;
        }).when(accountChanges).hasOtherEffectiveAdmin(anyString());

        String secondToken = token(second);
        var demotion = http.sendAsync(roleRequest(first.getId(), 1, "[\"USER\"]", token(first)), HttpResponse.BodyHandlers.ofString());
        assertTrue(firstCounted.await(10, TimeUnit.SECONDS));
        var restriction = http.sendAsync(put(second.getId() + "/restriction",
                "{\"expectedVersion\":1,\"status\":\"DISABLED\",\"reason\":\"test\"}", secondToken), HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(demotion, restriction).get(25, TimeUnit.SECONDS);

        assertEquals(200, demotion.join().statusCode(), demotion.join().body());
        problem(restriction.join(), 409, "LAST_ADMIN_PROTECTED");
        assertEquals(List.of(UserRole.USER), roles.findRoles(first.getId()));
        assertEquals(UserAccount.Status.ACTIVE, users.selectById(second.getId()).getAccountStatus());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "[\"ADMIN\"]|422|VALIDATION_ERROR", "[\"USER\",\"USER\"]|422|VALIDATION_ERROR",
            "[]|422|VALIDATION_ERROR", "[\"USER\",null]|422|VALIDATION_ERROR",
            "[\"USER\",\"OWNER\"]|400|MALFORMED_REQUEST", "[\"USER\",1]|400|MALFORMED_REQUEST"
    })
    void invalidRoleSetsDoNotChangeTarget(String requested, int status, String code) throws Exception {
        var operator = account(UserRole.ADMIN);
        var target = account(UserRole.USER);
        problem(replace(target.getId(), 1, requested, token(operator)), status, code);
        assertEquals(1, users.selectById(target.getId()).getVersion());
        assertEquals(0, auditCount(target));
    }

    @Test
    void ordinaryModeratorsCannotChangeRolesAndMissingTargetIs404() throws Exception {
        var moderator = account(UserRole.MODERATOR);
        var operator = account(UserRole.ADMIN);
        problem(replace(moderator.getId(), 1, "[\"USER\",\"ADMIN\"]", token(moderator)), 403, "PERMISSION_DENIED");
        problem(replace("missing_" + UUID.randomUUID(), 1, "[\"USER\"]", token(operator)), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void rateLimitedRoleChangeDoesNotWriteAudit() throws Exception {
        var operator = account(UserRole.ADMIN);
        var target = account(UserRole.USER);
        redis.opsForValue().set(PREFIX + "{user-role-update}:user:" + TokenDigest.sha256(operator.getId()),
                Integer.toString(quota.userPerMinuteLimit()), Duration.ofSeconds(40));

        var response = replace(target.getId(), 1, "[\"USER\",\"MODERATOR\"]", token(operator));

        problem(response, 429, "RATE_LIMITED");
        assertTrue(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
        assertEquals(0, auditCount(target));
    }

    private int auditCount(UserAccount account) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_role_change_audit WHERE user_id=?", Integer.class, account.getId());
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.read(response.body(), "$.code"));
    }

    private UserAccount account(UserRole role) {
        var account = LoginTestSupport.account();
        account.setId(UUID.randomUUID().toString().replace("-", ""));
        account.setEmail("role-update-" + account.getId() + "@example.test");
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
        return sessions.create(account.getId(), account.getAuthVersion(), "user-role-update-it").accessToken();
    }

    private HttpResponse<String> replace(String id, int version, String selectedRoles, String token) throws Exception {
        return http.send(roleRequest(id, version, selectedRoles, token), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest roleRequest(String id, int version, String selectedRoles, String token) {
        String json = """
                {"expectedVersion":%d,"roles":%s,"reason":"测试角色替换"}
                """.formatted(version, selectedRoles);
        return put(id + "/roles", json, token);
    }

    private HttpRequest put(String suffix, String json, String token) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/users/" + suffix))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(json)).build();
    }

    private HttpResponse<String> readCurrentUser(String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
