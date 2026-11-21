package com.freecontent.community.user.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.user.config.AdminUserQueryProperties;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
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
class AdminUserQueryFlowIT {
    private static final String PATH = "/api/v1/admin/users";
    private static final String PREFIX = "test:admin-user-query:" + UUID.randomUUID() + ":";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 18, 3, 4, 5);
    private static final Set<String> FIELDS = Set.of("id", "displayName", "maskedEmail", "emailVerified",
            "roles", "accountStatus", "version", "createdAt");

    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private AdminUserQueryProperties quota;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    private final List<String> fixtures = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.user.admin-query.redis-key-prefix", () -> PREFIX);
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
    void exactIdReturnsOnlyTheContractFieldsWithMaskedEmailAndActualRoles() throws Exception {
        var admin = account(UserAccount.Status.ACTIVE, UserRole.ADMIN);
        var target = account(UserAccount.Status.DISABLED, UserRole.MODERATOR);
        jdbc.update("UPDATE app_user SET email_verified=0, version=7 WHERE id=?", target.getId());

        var response = get("?userId=" + target.getId() + "&status=DISABLED", token(admin));

        assertEquals(200, response.statusCode(), response.body());
        var payload = JsonPath.parse(response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), payload.read("$.requestId", String.class));
        assertEquals(1, payload.read("$.data.page", Integer.class));
        assertEquals(20, payload.read("$.data.pageSize", Integer.class));
        assertEquals(1, payload.read("$.data.total", Integer.class));
        List<Map<String, Object>> items = payload.read("$.data.items");
        assertEquals(1, items.size());
        var item = items.get(0);
        assertEquals(FIELDS, item.keySet());
        assertEquals(target.getId(), item.get("id"));
        assertEquals(target.getDisplayName(), item.get("displayName"));
        assertEquals("***@example.test", item.get("maskedEmail"));
        assertEquals(false, item.get("emailVerified"));
        assertEquals(Set.of("USER", "MODERATOR"), Set.copyOf((List<?>) item.get("roles")));
        assertEquals("DISABLED", item.get("accountStatus"));
        assertEquals(7, item.get("version"));
        assertEquals("2026-11-18T03:04:05Z", item.get("createdAt"));
        assertFalse(response.body().contains(target.getEmail()));
    }

    @Test
    void detailReturnsOneMaskedUserWithTheSameFieldsAsTheList() throws Exception {
        var admin = account(UserAccount.Status.ACTIVE, UserRole.ADMIN);
        var target = account(UserAccount.Status.DISABLED, UserRole.MODERATOR);
        jdbc.update("UPDATE app_user SET version=7 WHERE id=?", target.getId());
        String token = token(admin);

        var response = get("/" + target.getId(), token);

        assertEquals(200, response.statusCode(), response.body());
        var payload = JsonPath.parse(response.body());
        Map<String, Object> detail = payload.read("$.data");
        assertEquals(FIELDS, detail.keySet());
        assertEquals(target.getId(), detail.get("id"));
        assertEquals("DISABLED", detail.get("accountStatus"));
        assertEquals(7, detail.get("version"));
        assertEquals("***@example.test", detail.get("maskedEmail"));
        assertEquals(Set.of("USER", "MODERATOR"), Set.copyOf((List<?>) detail.get("roles")));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), payload.read("$.requestId", String.class));
        assertEquals(detail, page(get("?userId=" + target.getId(), token)).read("$.data.items[0]"));
    }

    @Test
    void detailReturns404ForAnUnknownUser() throws Exception {
        var admin = account(UserAccount.Status.ACTIVE, UserRole.ADMIN);
        problem(get("/missing_" + UUID.randomUUID(), token(admin)), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void detailRejectsInvalidPathAndQueryBeforeChargingQuota() throws Exception {
        var admin = account(UserAccount.Status.ACTIVE, UserRole.ADMIN);
        String token = token(admin);
        var invalid = get("/invalid!", token);
        problem(invalid, 422, "VALIDATION_ERROR");
        assertEquals("path", JsonPath.read(invalid.body(), "$.violations[0].location"));
        assertEquals("userId", JsonPath.read(invalid.body(), "$.violations[0].path"));
        problem(get("/" + admin.getId() + "?status=ACTIVE", token), 400, "MALFORMED_REQUEST");
        assertNull(redis.opsForValue().get(quotaKey(admin)));
    }

    @Test
    void userListAndDetailShareOneQuota() throws Exception {
        var admin = account(UserAccount.Status.ACTIVE, UserRole.ADMIN);
        String token = token(admin);
        if (quota.userPerMinuteLimit() > 1) {
            redis.opsForValue().set(quotaKey(admin), Integer.toString(quota.userPerMinuteLimit() - 1), Duration.ofSeconds(40));
        }

        page(get("?userId=" + admin.getId(), token));
        var response = get("/" + admin.getId(), token);

        problem(response, 429, "RATE_LIMITED");
        assertTrue(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
    }

    @Test
    void statusPaginationUsesRegistrationTimeAndIdWithRolesGroupedByUser() throws Exception {
        var admin = account(UserAccount.Status.ACTIVE, UserRole.ADMIN);
        var one = account(UserAccount.Status.WRITE_BLOCKED, UserRole.MODERATOR);
        var two = account(UserAccount.Status.WRITE_BLOCKED, UserRole.USER);
        var newer = account(UserAccount.Status.WRITE_BLOCKED, UserRole.ADMIN);
        var recent = LocalDateTime.of(2999, 1, 1, 0, 0);
        jdbc.update("UPDATE app_user SET created_at=? WHERE id IN (?,?)", recent, one.getId(), two.getId());
        jdbc.update("UPDATE app_user SET created_at=? WHERE id=?", recent.plusDays(1), newer.getId());
        var firstTie = one.getId().compareTo(two.getId()) > 0 ? one : two;
        var secondTie = firstTie == one ? two : one;
        String token = token(admin);
        String filter = "?status=WRITE_BLOCKED&pageSize=2";
        Long matching = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE account_status='WRITE_BLOCKED'", Long.class);

        var firstPage = page(get(filter, token));
        var secondPage = page(get(filter + "&page=2", token));

        assertEquals(List.of(newer.getId(), firstTie.getId()), firstPage.read("$.data.items[*].id"));
        assertEquals(secondTie.getId(), secondPage.read("$.data.items[0].id", String.class));
        assertEquals(matching, firstPage.read("$.data.total", Long.class));
        assertEquals(matching, secondPage.read("$.data.total", Long.class));
        assertEquals(Set.of("USER", "ADMIN"), Set.copyOf(firstPage.<List<String>>read("$.data.items[0].roles")));
        assertEquals(firstTie == one ? Set.of("USER", "MODERATOR") : Set.of("USER"),
                Set.copyOf(firstPage.<List<String>>read("$.data.items[1].roles")));
    }

    @Test
    void mismatchedFiltersAndHugePagesReturnEmptyListsWithTheRightTotal() throws Exception {
        var admin = account(UserAccount.Status.ACTIVE, UserRole.ADMIN);
        String token = token(admin);
        String filter = "?userId=" + admin.getId();
        var missing = page(get(filter + "&status=DISABLED", token));
        assertEquals(List.of(), missing.read("$.data.items"));
        assertEquals(0, missing.read("$.data.total", Integer.class));

        var beyond = page(get(filter + "&page=99999999999999999999999", token));
        assertEquals(List.of(), beyond.read("$.data.items"));
        assertEquals(1, beyond.read("$.data.total", Integer.class));
    }

    @Test
    void malformedStoredEmailIsNotReturnedAsTheDomain() throws Exception {
        var admin = account(UserAccount.Status.ACTIVE, UserRole.ADMIN);
        var target = account(UserAccount.Status.ACTIVE, UserRole.USER);
        jdbc.update("UPDATE app_user SET email='private-legacy-value' WHERE id=?", target.getId());

        var result = page(get("?userId=" + target.getId(), token(admin)));

        assertEquals("***", result.read("$.data.items[0].maskedEmail", String.class));
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"USER", "MODERATOR"})
    void onlyAdminsWithUserReadCanListUsers(UserRole role) throws Exception {
        var user = account(UserAccount.Status.ACTIVE, role);
        String token = token(user);
        problem(get("", token), 403, "PERMISSION_DENIED");
        problem(get("/" + user.getId(), token), 403, "PERMISSION_DENIED");
    }

    @Test
    void missingAuthenticationReturns401() throws Exception {
        problem(get("", null), 401, "AUTHENTICATION_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void revokedAdminRoleIsRecheckedInsideTheQueryTransaction(boolean detail) throws Exception {
        var admin = account(UserAccount.Status.ACTIVE, UserRole.ADMIN);
        String token = token(admin);
        doAnswer(call -> {
            var principal = call.callRealMethod();
            jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='ADMIN'", admin.getId());
            return principal;
        }).when(authentication).authenticate(token);

        problem(get(detail ? "/" + admin.getId() : "", token), 403, "PERMISSION_DENIED");
    }

    @ParameterizedTest
    @CsvSource({
            "page=abc,400,MALFORMED_REQUEST", "page=0,422,VALIDATION_ERROR",
            "pageSize=101,422,VALIDATION_ERROR", "status=UNKNOWN,400,MALFORMED_REQUEST",
            "userId=invalid!,422,VALIDATION_ERROR", "email=someone,400,MALFORMED_REQUEST",
            "page=1&page=2,400,MALFORMED_REQUEST"
    })
    void rejectsInvalidParametersBeforeChargingQuota(String query, int status, String code) throws Exception {
        var admin = account(UserAccount.Status.ACTIVE, UserRole.ADMIN);
        problem(get("?" + query, token(admin)), status, code);
        assertNull(redis.opsForValue().get(quotaKey(admin)));
    }

    @Test
    void exhaustedUserQueryQuotaReturns429AndRetryAfter() throws Exception {
        var admin = account(UserAccount.Status.ACTIVE, UserRole.ADMIN);
        redis.opsForValue().set(quotaKey(admin), Integer.toString(quota.userPerMinuteLimit()), Duration.ofSeconds(40));

        var response = get("", token(admin));

        problem(response, 429, "RATE_LIMITED");
        assertTrue(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
    }

    private String quotaKey(UserAccount admin) {
        return PREFIX + "{user-admin-query}:user:" + TokenDigest.sha256(admin.getId());
    }

    private com.jayway.jsonpath.DocumentContext page(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return JsonPath.parse(response.body());
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.read(response.body(), "$.code"));
    }

    private UserAccount account(UserAccount.Status status, UserRole role) {
        var account = LoginTestSupport.account();
        account.setId(UUID.randomUUID().toString().replace("-", ""));
        account.setEmail("private-" + account.getId() + "@example.test");
        account.setEmailNormalized(account.getEmail());
        account.setAccountStatus(status);
        account.setCreatedAt(CREATED_AT);
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
        return sessions.create(account.getId(), account.getAuthVersion(), "admin-user-query-it").accessToken();
    }

    private HttpResponse<String> get(String suffix, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH + suffix))
                .timeout(Duration.ofSeconds(20)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
