package com.freecontent.community.user.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.user.config.RoleQueryProperties;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.freecontent.community.user.model.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false",
        "app.media.processing.enabled=false"})
class AdminRoleQueryFlowIT {
    private static final String PATH = "/api/v1/admin/roles";
    private static final String PREFIX = "test:admin-role-query:" + UUID.randomUUID() + ":";
    private static final Set<String> USER_PERMISSIONS = Set.of("content:write:self", "statistics:read:self");
    private static final Set<String> MODERATOR_PERMISSIONS = Set.of("content:write:self", "statistics:read:self",
            "comment:hide", "comment:moderate:read", "content:moderate:read", "content:take-down", "moderation:read",
            "report:decide", "report:read", "review:decide", "review:read");
    private static final Set<String> ADMIN_PERMISSIONS = Set.of("audit:read", "comment:hide", "comment:moderate:read",
            "content:moderate:read", "content:take-down", "content:write:self", "moderation:read", "report:decide",
            "report:read", "review:decide", "review:read", "role:manage", "statistics:read:platform",
            "statistics:read:self", "tag:manage", "task:read", "task:retry", "user:read", "user:restrict");

    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RoleQueryProperties quota;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    private final List<String> fixtures = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.user.role-query.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String userId : fixtures) {
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", userId);
            users.deleteById(userId);
        }
    }

    @Test
    void adminReceivesTheCompletePresetRoleCatalog() throws Exception {
        String token = token(account(UserRole.ADMIN));

        var response = get("", token);

        assertEquals(200, response.statusCode(), response.body());
        var payload = JsonPath.parse(response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), payload.read("$.requestId", String.class));
        assertEquals(List.of("USER", "MODERATOR", "ADMIN"), payload.read("$.data.items[*].code"));
        List<Map<String, Object>> items = payload.read("$.data.items");
        assertRole(items.get(0), "普通用户", USER_PERMISSIONS);
        assertRole(items.get(1), "审核员", MODERATOR_PERMISSIONS);
        assertRole(items.get(2), "管理员", ADMIN_PERMISSIONS);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"USER", "MODERATOR"})
    void nonAdminRolesCannotReadTheCatalog(UserRole role) throws Exception {
        problem(get("", token(account(role))), 403, "PERMISSION_DENIED");
    }

    @Test
    void missingAuthenticationReturns401() throws Exception {
        problem(get("", null), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void writeBlockedAdminCanStillQueryTheCatalog() throws Exception {
        var admin = account(UserRole.ADMIN);
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", admin.getId());

        var response = get("", token(admin));

        assertEquals(200, response.statusCode(), response.body());
    }

    @ParameterizedTest
    @CsvSource({"ROLE,403,PERMISSION_DENIED", "VERSION,401,AUTHENTICATION_REQUIRED", "STATUS,403,ACCOUNT_RESTRICTED"})
    void authorizationChangesAfterAuthenticationPreventReadingTheCatalog(String change, int status, String code) throws Exception {
        var admin = account(UserRole.ADMIN);
        String token = token(admin);
        doAnswer(call -> {
            var principal = call.callRealMethod();
            switch (change) {
                case "ROLE" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='ADMIN'", admin.getId());
                case "VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", admin.getId());
                case "STATUS" -> jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", admin.getId());
                default -> throw new IllegalArgumentException(change);
            }
            return principal;
        }).when(authentication).authenticate(token);

        problem(get("", token), status, code);
    }

    @Test
    void queryAndRequestBodyAreRejectedBeforeChargingQuota() throws Exception {
        var admin = account(UserRole.ADMIN);
        String token = token(admin);
        problem(get("?role=USER", token), 400, "MALFORMED_REQUEST");
        var request = request("", token).method("GET", HttpRequest.BodyPublishers.ofString("{}")).build();
        problem(http.send(request, HttpResponse.BodyHandlers.ofString()), 400, "MALFORMED_REQUEST");
        assertNull(redis.opsForValue().get(quotaKey("user", admin.getId())));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void exhaustedRoleQueryQuotaReturns429AndRetryAfter(boolean userQuota) throws Exception {
        var admin = account(UserRole.ADMIN);
        String key = userQuota ? quotaKey("user", admin.getId()) : quotaKey("source", "127.0.0.1");
        int limit = userQuota ? quota.userPerMinuteLimit() : quota.ipPerMinuteLimit();
        redis.opsForValue().set(key, Integer.toString(limit), Duration.ofSeconds(40));

        var response = get("", token(admin));

        problem(response, 429, "RATE_LIMITED");
        assertTrue(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
    }

    private String quotaKey(String dimension, String value) {
        return PREFIX + "{user-role-query}:" + dimension + ":" + TokenDigest.sha256(value);
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.read(response.body(), "$.code"));
    }

    private void assertRole(Map<String, Object> role, String name, Set<String> expectedPermissions) {
        assertEquals(Set.of("code", "name", "permissions"), role.keySet());
        assertEquals(name, role.get("name"));
        var permissions = (List<?>) role.get("permissions");
        assertEquals(expectedPermissions.size(), permissions.size());
        assertEquals(expectedPermissions, new HashSet<>(permissions));
    }

    private UserAccount account(UserRole role) {
        var account = LoginTestSupport.account();
        account.setId(UUID.randomUUID().toString().replace("-", ""));
        account.setEmail("roles-" + account.getId() + "@example.test");
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
        return sessions.create(account.getId(), account.getAuthVersion(), "admin-role-query-it").accessToken();
    }

    private HttpResponse<String> get(String query, String token) throws Exception {
        return http.send(request(query, token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String query, String token) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH + query))
                .timeout(Duration.ofSeconds(20));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request;
    }
}
