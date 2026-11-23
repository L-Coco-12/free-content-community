package com.freecontent.community.content.comment.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.content.comment.mapper.CommentHideMapper;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.model.UserRole;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.processing.enabled=false",
        "app.media.upload-cleanup.enabled=false"})
class CommentHideFlowIT {
    private static final String PREFIX = "test:comment-hide:" + UUID.randomUUID() + ":";
    private static final String ADMIN = "/api/v1/admin/comments/";
    private static final String PUBLIC = "/api/v1/comments/";
    private static final int USER_LIMIT = 8;

    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserAccountMapper users;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private SqlSession sqlSession;
    @Autowired private JsonMapper jsonMapper;
    @MockitoSpyBean private CommentHideMapper writes;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    private final List<String> userIds = new ArrayList<>();
    private final List<String> commentIds = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.comment-hide.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.moderation-query.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.comment-hide.user-per-minute-limit", () -> USER_LIMIT);
        registry.add("app.content.comment-hide.ip-per-minute-limit", () -> 30);
        registry.add("app.content.comment-admin-query.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.comment-query.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.public-query.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.comment.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        var keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String user : userIds) {
            jdbc.update("DELETE FROM comment_hide_request WHERE operator_id=?", user);
            jdbc.update("DELETE FROM comment_moderation_action WHERE operator_id=?", user);
        }
        for (int i = commentIds.size() - 1; i >= 0; i--) jdbc.update("DELETE FROM content_comment WHERE id=?", commentIds.get(i));
        for (String user : userIds) {
            ContentTestSupport.clearWorkspace(jdbc, user);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", user);
            users.deleteById(user);
        }
    }

    @Test
    void moderatorHidesVisibleCommentAndReceivesItsPersistedAction() throws Exception {
        var moderator = account(UserRole.MODERATOR);
        var author = account(UserRole.USER);
        String content = content(author);
        String comment = insert(content, author, null, null, "VISIBLE", 3, "需要保留的评论原文");

        String token = token(moderator);
        var response = hide(comment, token, id(), 3, "违反社区规范");

        assertEquals(201, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var json = JsonPath.parse(response.body());
        String actionId = json.read("$.data.id", String.class);
        assertEquals("COMMENT", json.read("$.data.targetType", String.class));
        assertEquals(comment, json.read("$.data.targetId", String.class));
        assertEquals("HIDE_COMMENT", json.read("$.data.action", String.class));
        assertEquals(moderator.getId(), json.read("$.data.operatorId", String.class));
        assertEquals("违反社区规范", json.read("$.data.reason", String.class));
        assertEquals(4, json.read("$.data.resultingVersion", Integer.class));
        assertEquals(Map.of("state", "HIDDEN", "version", 4, "body", "需要保留的评论原文"),
                jdbc.queryForMap("SELECT state, version, body FROM content_comment WHERE id=?", comment));
        var audit = jdbc.queryForMap("""
                SELECT comment_id, operator_id, before_state, after_state, before_version, resulting_version, request_id
                FROM comment_moderation_action WHERE id=?
                """, actionId);
        assertEquals(comment, audit.get("comment_id"));
        assertEquals(moderator.getId(), audit.get("operator_id"));
        assertEquals("VISIBLE", audit.get("before_state"));
        assertEquals("HIDDEN", audit.get("after_state"));
        assertEquals(3, audit.get("before_version"));
        assertEquals(4, audit.get("resulting_version"));
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), audit.get("request_id"));
        var persisted = read("/api/v1/admin/moderation-actions/" + actionId, token);
        assertEquals(json.read("$.data", Map.class), persisted.read("$.data", Map.class));
    }

    @Test
    void hidingPublicRootPreservesRepliesAndMasksRootWhileUpdatingCounts() throws Exception {
        var moderator = account(UserRole.MODERATOR);
        var author = account(UserRole.USER);
        var other = account(UserRole.USER);
        String content = content(author);
        publish(content);
        String root = insert(content, author, null, null, "VISIBLE", 1, "根评论私有原文");
        String reply = insert(content, other, root, root, "VISIBLE", 1, "别人留下的楼层回复");

        actionId(hide(root, token(moderator), id(), 1, "隐藏原因"));

        var rootView = read(PUBLIC + root, null);
        assertEquals("DELETED_PLACEHOLDER", rootView.read("$.data.state", String.class));
        assertNull(rootView.read("$.data.body"));
        assertNull(rootView.read("$.data.author"));
        assertEquals(1, rootView.read("$.data.replyCount", Integer.class));
        assertEquals(List.of(reply), read(PUBLIC + root + "/replies", null).read("$.data.items[*].id"));
        assertEquals(1, read("/api/v1/contents/" + content, null).read("$.data.commentCount", Integer.class));
        var adminView = read(ADMIN + root, token(moderator));
        assertEquals("HIDDEN", adminView.read("$.data.state", String.class));
        assertEquals("根评论私有原文", adminView.read("$.data.body", String.class));
        assertEquals(2, adminView.read("$.data.version", Integer.class));
    }

    @Test
    void staleVersionsAndNonVisibleStatesReturnDistinctConflicts() throws Exception {
        var operator = account(UserRole.ADMIN);
        var author = account(UserRole.USER);
        String content = content(author);
        String visible = insert(content, author, null, null, "VISIBLE", 4, "当前版本");
        String hidden = insert(content, author, null, null, "HIDDEN", 2, "已隐藏");
        String deleted = insert(content, author, null, null, "DELETED", 3, "已删除");
        String token = token(operator);
        String key = id();

        var stale = hide(visible, token, key, 3, "操作原因");
        problem(stale, 409, "RESOURCE_VERSION_CONFLICT");
        assertEquals(4, JsonPath.parse(stale.body()).read("$.currentVersion", Integer.class));
        problem(hide(hidden, token, id(), 2, "操作原因"), 409, "COMMENT_STATE_CONFLICT");
        problem(hide(deleted, token, id(), 3, "操作原因"), 409, "COMMENT_STATE_CONFLICT");
        problem(hide(id(), token, id(), 1, "操作原因"), 404, "RESOURCE_NOT_FOUND");
        assertEquals(0L, countActions(visible));
        assertEquals(0L, countActions(hidden));
        assertEquals(0L, countActions(deleted));
        assertEquals(0L, countLeases(operator.getId()));
        actionId(hide(visible, token, key, 4, "操作原因"));
    }

    @Test
    void replayReturnsTheOriginalActionEvenAfterDeletionAndWithWriteQuotaExhausted() throws Exception {
        var operator = account(UserRole.ADMIN);
        var author = account(UserRole.USER);
        String content = content(author);
        String comment = insert(content, author, null, null, "VISIBLE", 1, "评论原文");
        String another = insert(content, author, null, null, "VISIBLE", 1, "其他评论");
        String key = id();
        String token = token(operator);
        var created = hide(comment, token, key, 1, "  原因正文  ");
        String action = actionId(created);
        assertEquals("原因正文", JsonPath.parse(created.body()).read("$.data.reason", String.class));
        assertEquals(204, send("DELETE", PUBLIC + comment, token(author), HttpRequest.BodyPublishers.noBody()).statusCode());
        redis.opsForValue().set(quotaKey(operator.getId()), Integer.toString(USER_LIMIT), Duration.ofMinutes(1));

        var replay = hide(comment, token, key, 1, "原因正文");
        assertEquals(action, actionId(replay));
        assertEquals(JsonPath.parse(created.body()).read("$.data", Map.class), JsonPath.parse(replay.body()).read("$.data", Map.class));
        problem(hide(comment, token, key, 1, "更改原因"), 409, "IDEMPOTENCY_KEY_REUSED");
        problem(hide(another, token, key, 1, "原因正文"), 409, "IDEMPOTENCY_KEY_REUSED");
        var limited = hide(another, token, id(), 1, "其他请求");
        problem(limited, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()) > 0);
        assertEquals("DELETED", state(comment));
        assertEquals(3, version(comment));
        assertEquals(1L, countActions(comment));
        assertEquals(1L, countLeases(operator.getId()));
        assertEquals("VISIBLE", state(another));
    }

    @Test
    void expiredKeyCanBeUsedAgainAndThePreviousAuditSurvives() throws Exception {
        var operator = account(UserRole.MODERATOR);
        var author = account(UserRole.USER);
        String content = content(author);
        String one = insert(content, author, null, null, "VISIBLE", 1, "一");
        String two = insert(content, author, null, null, "VISIBLE", 2, "二");
        String token = token(operator);
        String key = id();
        String old = actionId(hide(one, token, key, 1, "原因"));
        jdbc.update("UPDATE comment_hide_request SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE operator_id=?",
                operator.getId());

        String next = actionId(hide(two, token, key, 2, "另一个处置"));

        assertNotEquals(old, next);
        assertEquals(1L, countActions(one));
        assertEquals(1L, countActions(two));
    }

    @Test
    void unauthorizedAccountsAndUnregisteredRoutesCannotHide() throws Exception {
        var operator = account(UserRole.MODERATOR);
        var author = account(UserRole.USER);
        String content = content(author);
        String comment = insert(content, author, null, null, "VISIBLE", 1, "不应被修改");
        String token = token(operator);
        problem(hide(comment, null, id(), 1, "原因"), 401, "AUTHENTICATION_REQUIRED");
        problem(hide(comment, token(author), id(), 1, "原因"), 403, "PERMISSION_DENIED");
        problem(send("GET", ADMIN + comment + "/hides", token, HttpRequest.BodyPublishers.noBody()), 403, "PERMISSION_DENIED");
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", operator.getId());
        problem(hide(comment, token, id(), 1, "原因"), 403, "ACCOUNT_RESTRICTED");
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", operator.getId());
        problem(hide(comment, token, id(), 1, "原因"), 403, "ACCOUNT_RESTRICTED");
        assertEquals("VISIBLE", state(comment));
        assertEquals(0L, countActions(comment));
    }

    @Test
    void aRoleRevokedAfterBearerAuthenticationCannotReplayTheAction() throws Exception {
        var operator = account(UserRole.MODERATOR);
        var author = account(UserRole.USER);
        String content = content(author);
        String comment = insert(content, author, null, null, "VISIBLE", 1, "原文");
        String token = token(operator);
        String key = id();
        actionId(hide(comment, token, key, 1, "原因"));
        doAnswer(call -> {
            var old = call.callRealMethod();
            jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='MODERATOR'", operator.getId());
            return old;
        }).when(authentication).authenticate(token);

        problem(hide(comment, token, key, 1, "原因"), 403, "PERMISSION_DENIED");
        assertEquals(1L, countActions(comment));
    }

    @Test
    void sameKeyInProgressReturns409AndLaterReusesTheCommittedAction() throws Exception {
        var operator = account(UserRole.MODERATOR);
        var author = account(UserRole.USER);
        String content = content(author);
        String comment = insert(content, author, null, null, "VISIBLE", 1, "原文");
        String token = token(operator);
        String key = id();
        var written = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        doAnswer(call -> {
            int count = insertAction(call.getArgument(0, CommentHideMapper.Action.class));
            written.countDown();
            assertTrue(finish.await(12, TimeUnit.SECONDS));
            return count;
        }).when(writes).insertAction(any());

        var first = http.sendAsync(hideRequest(comment, token, key, "application/json", body(1, "原因")).build(),
                HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(written.await(7, TimeUnit.SECONDS));
            var duplicate = hide(comment, token, key, 1, "原因");
            problem(duplicate, 409, "REQUEST_IN_PROGRESS");
            assertTrue(Long.parseLong(duplicate.headers().firstValue("Retry-After").orElseThrow()) > 0);
            assertEquals(0L, countActions(comment));
            assertEquals("VISIBLE", state(comment));
        } finally {
            finish.countDown();
        }
        String action = actionId(first.get(12, TimeUnit.SECONDS));
        assertEquals(action, actionId(hide(comment, token, key, 1, "原因")));
        assertEquals(1L, countActions(comment));
    }

    @Test
    void differentOperatorsCannotBothHideTheSameVersion() throws Exception {
        var one = account(UserRole.MODERATOR);
        var two = account(UserRole.ADMIN);
        var author = account(UserRole.USER);
        String comment = insert(content(author), author, null, null, "VISIBLE", 1, "原文");
        var a = http.sendAsync(hideRequest(comment, token(one), id(), "application/json", body(1, "甲")).build(),
                HttpResponse.BodyHandlers.ofString());
        var b = http.sendAsync(hideRequest(comment, token(two), id(), "application/json", body(1, "乙")).build(),
                HttpResponse.BodyHandlers.ofString());
        var first = a.get(15, TimeUnit.SECONDS);
        var second = b.get(15, TimeUnit.SECONDS);

        assertEquals(List.of(201, 409), List.of(first.statusCode(), second.statusCode()).stream().sorted().toList());
        var conflict = first.statusCode() == 409 ? first : second;
        problem(conflict, 409, "RESOURCE_VERSION_CONFLICT");
        assertEquals(2, JsonPath.parse(conflict.body()).read("$.currentVersion", Integer.class));
        assertEquals(1L, countActions(comment));
        assertEquals(2, version(comment));
    }

    @Test
    void auditWriteFailureRollsBackStateAndLeaseAllowsRetry() throws Exception {
        var operator = account(UserRole.ADMIN);
        var author = account(UserRole.USER);
        String comment = insert(content(author), author, null, null, "VISIBLE", 1, "原文");
        String token = token(operator);
        String key = id();
        var first = new AtomicBoolean(true);
        doAnswer(call -> {
            int count = insertAction(call.getArgument(0, CommentHideMapper.Action.class));
            if (first.getAndSet(false)) throw new TransientDataAccessResourceException("private SQL detail");
            return count;
        }).when(writes).insertAction(any());

        var failure = hide(comment, token, key, 1, "原因");
        problem(failure, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(failure.body().contains("private SQL detail"));
        assertEquals("VISIBLE", state(comment));
        assertEquals(1, version(comment));
        assertEquals(0L, countActions(comment));
        assertEquals(0L, countLeases(operator.getId()));
        actionId(hide(comment, token, key, 1, "原因"));
        assertEquals("HIDDEN", state(comment));
        assertEquals(1L, countActions(comment));
    }

    @Test
    void requestValidationDistinguishesSyntaxSizeTypeAndFieldErrors() throws Exception {
        var operator = account(UserRole.ADMIN);
        var author = account(UserRole.USER);
        String comment = insert(content(author), author, null, null, "VISIBLE", 1, "原文");
        String token = token(operator);
        String key = id();
        problem(hide(comment, token, null, 1, "原因"), 400, "MALFORMED_REQUEST");
        problem(hide("bad.id", token, key, 1, "原因"), 422, "VALIDATION_ERROR");
        problem(hide(comment, token, "short", 1, "原因"), 422, "VALIDATION_ERROR");
        problem(hide(comment, token, key, 0, "原因"), 422, "VALIDATION_ERROR");
        problem(hide(comment, token, key, 1, "　 \u00a0"), 422, "VALIDATION_ERROR");
        problem(rawHide(comment, token, key, "application/json", "{\"expectedVersion\":2147483648,\"reason\":\"x\"}"),
                422, "VALIDATION_ERROR");
        problem(rawHide(comment, token, key, "application/json", "{\"expectedVersion\":1.2,\"reason\":\"x\"}"),
                400, "MALFORMED_REQUEST");
        problem(rawHide(comment, token, key, "application/json", "{\"expectedVersion\":1,\"reason\":\"x\",\"extra\":true}"),
                400, "MALFORMED_REQUEST");
        problem(rawHide(comment, token, key, "text/plain", "x"), 415, "UNSUPPORTED_MEDIA_TYPE");
        var chunked = HttpRequest.BodyPublishers.ofInputStream(
                () -> new ByteArrayInputStream((" ".repeat(33000) + "{\"reason\":\"x\"}").getBytes(StandardCharsets.UTF_8)));
        var oversized = hideRequest(comment, token, key, "application/json", chunked).build();
        problem(http.send(oversized, HttpResponse.BodyHandlers.ofString()), 413, "PAYLOAD_TOO_LARGE");
        assertEquals(0L, countLeases(operator.getId()));
        assertEquals("VISIBLE", state(comment));
    }

    @Test
    void generatedOpenApiUsesModerationActionAndRequiresBothInputFields() throws Exception {
        var response = send("GET", "/v3/api-docs", null, HttpRequest.BodyPublishers.noBody());
        assertEquals(200, response.statusCode(), response.body());
        var doc = JsonPath.parse(response.body());
        String path = "$.paths['/api/v1/admin/comments/{commentId}/hides'].post";
        assertEquals("hideComment", doc.read(path + ".operationId", String.class));
        assertEquals(List.of(Map.of("bearerAuth", List.of())), doc.read(path + ".security", List.class));
        String envelope = doc.read(path + ".responses['201'].content['application/json'].schema['$ref']", String.class);
        assertEquals("#/components/schemas/ModerationAction", doc.read("$.components.schemas['"
                + envelope.substring(envelope.lastIndexOf('/') + 1) + "'].properties.data['$ref']", String.class));
        assertEquals(java.util.Set.of("reason", "expectedVersion"),
                java.util.Set.copyOf(doc.<List<String>>read("$.components.schemas.HideCommentRequest.required")));
        assertEquals(false, doc.read("$.components.schemas.HideCommentRequest.additionalProperties", Boolean.class));
    }

    private int insertAction(CommentHideMapper.Action action) {
        return sqlSession.insert(CommentHideMapper.class.getName() + ".insertAction", Map.of("a", action));
    }

    private String state(String commentId) {
        return jdbc.queryForObject("SELECT state FROM content_comment WHERE id=?", String.class, commentId);
    }

    private int version(String commentId) {
        return jdbc.queryForObject("SELECT version FROM content_comment WHERE id=?", Integer.class, commentId);
    }

    private long countActions(String commentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM comment_moderation_action WHERE comment_id=?", Long.class, commentId);
    }

    private long countLeases(String operator) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM comment_hide_request WHERE operator_id=?", Long.class, operator);
    }

    private DocumentContext read(String path, String token) throws Exception {
        var response = send("GET", path, token, HttpRequest.BodyPublishers.noBody());
        assertEquals(200, response.statusCode(), response.body());
        return JsonPath.parse(response.body());
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.parse(response.body()).read("$.code", String.class));
    }

    private static String actionId(HttpResponse<String> response) {
        assertEquals(201, response.statusCode(), response.body());
        return JsonPath.parse(response.body()).read("$.data.id", String.class);
    }

    private static String quotaKey(String operator) {
        return PREFIX + "{content-comment-hide}:user:" + TokenDigest.sha256(operator);
    }

    private UserAccount account(UserRole role) {
        var user = LoginTestSupport.account();
        user.setId(id());
        user.setEmail("comment-hide-" + user.getId() + "@example.test");
        user.setEmailNormalized(user.getEmail());
        userIds.add(user.getId());
        users.insert(user);
        jdbc.update("INSERT INTO app_user_role(user_id, role_code, created_at) VALUES (?, ?, UTC_TIMESTAMP(6))",
                user.getId(), role.name());
        return user;
    }

    private String content(UserAccount author) {
        String id = id();
        jdbc.update("""
                INSERT INTO community_content(id, author_id, kind, lifecycle, publication_status, version, created_at, updated_at)
                VALUES (?, ?, 'ARTICLE', 'ACTIVE', 'UNPUBLISHED', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, id, author.getId());
        return id;
    }

    private void publish(String contentId) {
        String revision = id();
        jdbc.update("""
                INSERT INTO content_revision(id, content_id, revision_number, status, title, body_markdown, created_at, updated_at)
                VALUES (?, ?, 1, 'APPROVED', '隐藏评论测试', '公开正文', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, revision, contentId);
        jdbc.update("""
                UPDATE community_content SET published_revision_id=?, publication_status='PUBLISHED',
                    first_published_at=UTC_TIMESTAMP(6), published_at=UTC_TIMESTAMP(6) WHERE id=?
                """, revision, contentId);
    }

    private String insert(String content, UserAccount author, String root, String target, String state, int version, String body) {
        String id = id();
        jdbc.update("""
                INSERT INTO content_comment(id, content_id, author_id, root_comment_id, reply_to_comment_id,
                                            body, state, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, id, content, author.getId(), root, target, body, state, version);
        commentIds.add(id);
        return id;
    }

    private HttpResponse<String> hide(String commentId, String token, String key, int version, String reason) throws Exception {
        return http.send(hideRequest(commentId, token, key, "application/json", body(version, reason)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> rawHide(String commentId, String token, String key, String mediaType, String raw) throws Exception {
        return http.send(hideRequest(commentId, token, key, mediaType, HttpRequest.BodyPublishers.ofString(raw)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.BodyPublisher body(int version, String reason) {
        return HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(Map.of("expectedVersion", version, "reason", reason)));
    }

    private HttpRequest.Builder hideRequest(String commentId, String token, String key, String mediaType, HttpRequest.BodyPublisher body) {
        var request = request("POST", ADMIN + commentId + "/hides", token, body).header("Content-Type", mediaType);
        if (key != null) request.header("Idempotency-Key", key);
        return request;
    }

    private HttpResponse<String> send(String method, String path, String token, HttpRequest.BodyPublisher body) throws Exception {
        return http.send(request(method, path, token, body).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String method, String path, String token, HttpRequest.BodyPublisher body) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).method(method, body);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request;
    }

    private String token(UserAccount user) {
        return sessions.create(user.getId(), user.getAuthVersion(), "comment-hide-it").accessToken();
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
