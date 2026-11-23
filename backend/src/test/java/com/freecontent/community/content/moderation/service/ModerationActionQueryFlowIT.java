package com.freecontent.community.content.moderation.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.content.moderation.mapper.ModerationActionQueryMapper;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.processing.enabled=false",
        "app.media.upload-cleanup.enabled=false"})
class ModerationActionQueryFlowIT {
    private static final String PATH = "/api/v1/admin/moderation-actions/";
    private static final String PREFIX = "test:moderation-action-query:" + UUID.randomUUID() + ":";
    private static final LocalDateTime ACTION_TIME = LocalDateTime.of(2026, 11, 3, 8, 0);
    private static final int USER_LIMIT = 8;
    private static final int SOURCE_LIMIT = 16;

    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserAccountMapper users;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @MockitoSpyBean private ModerationActionQueryMapper actions;
    private final List<String> userIds = new ArrayList<>();
    private final List<String> commentIds = new ArrayList<>();
    private final List<String> commentActions = new ArrayList<>();
    private final List<String> contentActions = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.moderation-query.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.moderation-query.user-per-minute-limit", () -> USER_LIMIT);
        registry.add("app.content.moderation-query.ip-per-minute-limit", () -> SOURCE_LIMIT);
        registry.add("app.content.comment-admin-query.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        var keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String id : commentActions) jdbc.update("DELETE FROM comment_moderation_action WHERE id=?", id);
        for (String id : contentActions) jdbc.update("DELETE FROM content_moderation_action WHERE id=?", id);
        for (String id : commentIds) jdbc.update("DELETE FROM content_comment WHERE id=?", id);
        for (String userId : userIds) {
            ContentTestSupport.clearWorkspace(jdbc, userId);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", userId);
            users.deleteById(userId);
        }
    }

    @Test
    void moderatorReadsAnotherOperatorsCommentActionAfterTheCommentWasDeleted() throws Exception {
        var recordedOperator = account(UserRole.ADMIN);
        var reader = account(UserRole.MODERATOR);
        var author = account(UserRole.USER);
        String content = content(author);
        String comment = comment(content, author);
        String action = commentAction(id(), comment, recordedOperator, "评论处置原因", 3);

        var response = get(action, token(reader));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        Map<?, ?> result = JsonPath.parse(response.body()).read("$.data", Map.class);
        assertEquals(Map.of("id", action, "targetType", "COMMENT", "targetId", comment, "action", "HIDE_COMMENT",
                "operatorId", recordedOperator.getId(), "reason", "评论处置原因", "createdAt", "2026-11-03T08:00:00Z",
                "resultingVersion", 4), result);
    }

    @Test
    void returnsContentActionWithItsOriginalVersionWithoutUsingCurrentContentVersion() throws Exception {
        var admin = account(UserRole.ADMIN);
        var author = account(UserRole.USER);
        String content = content(author);
        String id = contentAction(id(), content, admin, "下架时的原因", 4);

        var response = get(id, token(admin));

        assertEquals(200, response.statusCode(), response.body());
        Map<?, ?> result = JsonPath.parse(response.body()).read("$.data", Map.class);
        assertEquals(Map.of("id", id, "targetType", "CONTENT", "targetId", content, "action", "TAKE_DOWN",
                "reason", "下架时的原因", "operatorId", admin.getId(), "createdAt", "2026-11-03T08:00:00Z",
                "resultingVersion", 4), result);
        assertEquals(9, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, content));
    }

    @Test
    void conflictingIdsAcrossTheTwoActionTablesReturn503WithoutChoosingOne() throws Exception {
        var admin = account(UserRole.ADMIN);
        var author = account(UserRole.USER);
        String content = content(author);
        String comment = comment(content, author);
        String id = id();
        contentAction(id, content, admin, "内容处置原记录", 2);
        commentAction(id, comment, admin, "评论处置原记录", 3);

        var response = get(id, token(admin));

        problem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("内容处置原记录"));
        assertFalse(response.body().contains("评论处置原记录"));
    }

    @Test
    void onlyAuthorizedOperatorsCanGetAnAction() throws Exception {
        var admin = account(UserRole.ADMIN);
        var reviewer = account(UserRole.MODERATOR);
        var author = account(UserRole.USER);
        String content = content(author);
        String actionId = contentAction(id(), content, admin, "原下架原因", 2);
        problem(get(actionId, null), 401, "AUTHENTICATION_REQUIRED");
        problem(get(actionId, token(author)), 403, "PERMISSION_DENIED");
        String token = token(reviewer);
        problem(send("POST", PATH + actionId, token, HttpRequest.BodyPublishers.noBody()), 403, "PERMISSION_DENIED");
        problem(get(actionId + "/history", token), 403, "PERMISSION_DENIED");
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", reviewer.getId());
        assertEquals(200, get(actionId, token).statusCode());
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", reviewer.getId());
        problem(get(actionId, token), 403, "ACCOUNT_RESTRICTED");
    }

    @Test
    void roleRevokedAfterBearerAuthenticationDoesNotReadTheAction() throws Exception {
        var reviewer = account(UserRole.MODERATOR);
        String content = content(reviewer);
        String actionId = contentAction(id(), content, reviewer, "需要权限的原因", 2);
        String token = token(reviewer);
        doAnswer(call -> {
            var prior = call.callRealMethod();
            jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='MODERATOR'", reviewer.getId());
            return prior;
        }).when(authentication).authenticate(token);

        problem(get(actionId, token), 403, "PERMISSION_DENIED");
        verify(actions, never()).findById(anyString());
    }

    @Test
    void invalidRequestDoesNotConsumeQuotaAndValidMissingIdReturns404() throws Exception {
        String token = token(account(UserRole.ADMIN));
        String missing = id();
        problem(get("bad.id", token), 422, "VALIDATION_ERROR");
        problem(get("x".repeat(65), token), 422, "VALIDATION_ERROR");
        problem(get(missing + "?type=COMMENT", token), 400, "MALFORMED_REQUEST");
        problem(send("GET", PATH + missing, token, HttpRequest.BodyPublishers.ofString("{}")), 400, "MALFORMED_REQUEST");
        assertNull(redis.opsForValue().get(quotaKey("source", "127.0.0.1")));
        verify(actions, never()).findById(anyString());

        problem(get(missing, token), 404, "RESOURCE_NOT_FOUND");
        assertEquals("1", redis.opsForValue().get(quotaKey("source", "127.0.0.1")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "source"})
    void eitherIndependentQuotaReturns429WithRetryAfter(String scope) throws Exception {
        var admin = account(UserRole.ADMIN);
        boolean user = scope.equals("user");
        String token = token(admin);
        String key = quotaKey(scope, user ? admin.getId() : "127.0.0.1");
        redis.opsForValue().set(key, Integer.toString(user ? USER_LIMIT : SOURCE_LIMIT), Duration.ofMinutes(1));

        var response = get(id(), token);

        problem(response, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
        verify(actions, never()).findById(anyString());
        problem(send("GET", "/api/v1/admin/comments/" + id(), token, HttpRequest.BodyPublishers.noBody()),
                404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void databaseFailureReturns503WithoutPrivateDetails() throws Exception {
        String actionId = id();
        doThrow(new TransientDataAccessResourceException("private SQL detail")).when(actions).findById(actionId);
        var response = get(actionId, token(account(UserRole.ADMIN)));

        problem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("private SQL detail"));
    }

    @Test
    void openApiDeclaresTheProtectedRouteAndExistingModerationActionSchema() throws Exception {
        var response = send("GET", "/v3/api-docs", null, HttpRequest.BodyPublishers.noBody());
        assertEquals(200, response.statusCode(), response.body());
        var spec = JsonPath.parse(response.body());
        String path = "$.paths['/api/v1/admin/moderation-actions/{actionId}'].get";
        assertEquals("getModerationAction", spec.read(path + ".operationId", String.class));
        assertEquals(List.of(Map.of("bearerAuth", List.of())), spec.read(path + ".security", List.class));
        String envelope = spec.read(path + ".responses['200'].content['application/json'].schema['$ref']", String.class);
        assertEquals("#/components/schemas/ModerationAction", spec.read("$.components.schemas['"
                + envelope.substring(envelope.lastIndexOf('/') + 1) + "'].properties.data['$ref']", String.class));
    }

    private static String quotaKey(String scope, String value) {
        return PREFIX + "{content-moderation-query}:" + scope + ":" + TokenDigest.sha256(value);
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.parse(response.body()).read("$.code", String.class));
    }

    private UserAccount account(UserRole role) {
        var account = LoginTestSupport.account();
        account.setId(id());
        account.setEmail("moderation-action-" + account.getId() + "@example.test");
        account.setEmailNormalized(account.getEmail());
        userIds.add(account.getId());
        users.insert(account);
        jdbc.update("INSERT INTO app_user_role(user_id, role_code, created_at) VALUES (?, ?, UTC_TIMESTAMP(6))",
                account.getId(), role.name());
        return account;
    }

    private String content(UserAccount author) {
        String id = id();
        jdbc.update("""
                INSERT INTO community_content(id, author_id, kind, lifecycle, publication_status, version, created_at, updated_at)
                VALUES (?, ?, 'ARTICLE', 'ACTIVE', 'UNPUBLISHED', 9, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, id, author.getId());
        return id;
    }

    private String comment(String content, UserAccount author) {
        String id = id();
        jdbc.update("""
                INSERT INTO content_comment(id, content_id, author_id, body, state, version, created_at, updated_at)
                VALUES (?, ?, ?, '评论原文', 'DELETED', 9, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, id, content, author.getId());
        commentIds.add(id);
        return id;
    }

    private String commentAction(String id, String comment, UserAccount operator, String reason, int beforeVersion) {
        jdbc.update("""
                INSERT INTO comment_moderation_action(id, comment_id, operator_id, reason, before_state, after_state,
                                                      before_version, resulting_version, request_id, created_at)
                VALUES (?, ?, ?, ?, 'VISIBLE', 'HIDDEN', ?, ?, ?, ?)
                """, id, comment, operator.getId(), reason, beforeVersion, beforeVersion + 1, id(), ACTION_TIME);
        commentActions.add(id);
        return id;
    }

    private String contentAction(String id, String content, UserAccount operator, String reason, int resultingVersion) {
        jdbc.update("""
                INSERT INTO content_moderation_action(id, content_id, reason_code, reason, operator_id, resulting_version, created_at)
                VALUES (?, ?, 'SPAM', ?, ?, ?, ?)
                """, id, content, reason, operator.getId(), resultingVersion, ACTION_TIME);
        contentActions.add(id);
        return id;
    }

    private HttpResponse<String> get(String actionId, String token) throws Exception {
        return send("GET", PATH + actionId, token, HttpRequest.BodyPublishers.noBody());
    }

    private HttpResponse<String> send(String method, String path, String token, HttpRequest.BodyPublisher body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).method(method, body);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(UserAccount account) {
        return sessions.create(account.getId(), account.getAuthVersion(), "moderation-action-query-it").accessToken();
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
