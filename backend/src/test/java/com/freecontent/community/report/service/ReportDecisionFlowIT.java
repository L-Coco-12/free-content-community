package com.freecontent.community.report.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.notification.mapper.NotificationWriteMapper;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.processing.enabled=false",
        "app.media.upload-cleanup.enabled=false"})
class ReportDecisionFlowIT {
    private static final String PATH = "/api/v1/admin/reports";
    private static final String PREFIX = "test:report-decision:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserAccountMapper users;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JsonMapper json;
    @Autowired private SqlSessionTemplate sqlSession;
    @MockitoSpyBean private NotificationWriteMapper notifications;
    private final List<String> accountIds = new ArrayList<>();
    private final List<String> commentIds = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.report.decision.redis-key-prefix", () -> PREFIX);
        registry.add("app.report.decision.user-per-minute-limit", () -> 4);
        registry.add("app.report.decision.ip-per-minute-limit", () -> 7);
        registry.add("app.content.take-down.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.comment-hide.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        var keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String id : accountIds) jdbc.update("DELETE FROM report_decision_request WHERE operator_id=?", id);
        for (String id : accountIds) jdbc.update("DELETE FROM report_decision_audit WHERE operator_id=?", id);
        for (String id : accountIds) {
            jdbc.update("DELETE FROM user_notification WHERE recipient_id=?", id);
            jdbc.update("DELETE FROM user_report WHERE reporter_id=?", id);
            jdbc.update("DELETE FROM content_take_down_request WHERE created_by=?", id);
            jdbc.update("DELETE FROM comment_hide_request WHERE operator_id=?", id);
        }
        for (String id : commentIds) {
            jdbc.update("DELETE FROM comment_moderation_action WHERE comment_id=?", id);
            jdbc.update("DELETE FROM content_comment WHERE id=?", id);
        }
        for (String id : accountIds) {
            jdbc.update("DELETE FROM content_take_down_audit WHERE content_id IN (SELECT id FROM community_content WHERE author_id=?)", id);
            jdbc.update("DELETE FROM content_publication_outbox WHERE content_id IN (SELECT id FROM community_content WHERE author_id=?)", id);
            jdbc.update("DELETE FROM content_moderation_action WHERE content_id IN (SELECT id FROM community_content WHERE author_id=?)", id);
            ContentTestSupport.clearWorkspace(jdbc, id);
        }
        for (String id : accountIds) {
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", id);
            users.deleteById(id);
        }
    }

    @Test
    void dismissesAnOpenReportAndCommitsTheAuditAndPrivateResultNotification() throws Exception {
        var operator = account(UserRole.MODERATOR);
        var reporter = account(UserRole.USER);
        String target = content(account(UserRole.USER));
        String report = openReport(reporter, target);
        String reason = "已核查，当前证据不足";

        var response = decide(report, Map.of("expectedVersion", 1, "decision", "DISMISS", "reason", reason),
                token(operator), id());

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var document = JsonPath.parse(response.body());
        assertEquals(report, document.read("$.data.id", String.class));
        assertEquals("DISMISSED", document.read("$.data.state", String.class));
        assertEquals(2, document.read("$.data.version", Integer.class));
        assertEquals(reason, document.read("$.data.resolution", String.class));
        assertEquals(reporter.getId(), document.read("$.data.reporterId", String.class));
        assertEquals("DISMISSED", jdbc.queryForObject("SELECT state FROM user_report WHERE id=?", String.class, report));
        var audit = jdbc.queryForMap("SELECT * FROM report_decision_audit WHERE report_id=?", report);
        assertEquals(operator.getId(), audit.get("operator_id"));
        assertEquals("DISMISS", audit.get("decision"));
        assertEquals(reason, audit.get("reason"));
        assertEquals(document.read("$.requestId", String.class), audit.get("request_id"));
        var notice = jdbc.queryForMap("SELECT * FROM user_notification WHERE type='REPORT_RESULT' AND target_id=?", report);
        assertEquals(reporter.getId(), notice.get("recipient_id"));
        assertEquals("REPORT", notice.get("target_type"));
        assertEquals("你提交的举报已驳回。", notice.get("body"));
        assertFalse(notice.get("body").toString().contains(reason));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resolvesReportsOnlyAfterTheMatchingModerationActionIsExecuted(boolean commentTarget) throws Exception {
        var operator = account(UserRole.ADMIN);
        var reporter = account(UserRole.USER);
        var author = account(UserRole.USER);
        String access = token(operator);
        String content = publish(author);
        String target = commentTarget ? comment(content, author) : content;
        String report = openReport(reporter, commentTarget ? "COMMENT" : "CONTENT", target);
        String action = commentTarget ? hide(target, access) : takeDown(target, access);

        var response = decide(report, resolve(action), access, id());

        assertEquals(200, response.statusCode(), response.body());
        var data = JsonPath.parse(response.body());
        assertEquals("RESOLVED", data.read("$.data.state", String.class));
        assertEquals(target, data.read("$.data.targetId", String.class));
        assertEquals(2, data.read("$.data.version", Integer.class));
        var audit = jdbc.queryForMap("SELECT content_action_id, comment_action_id FROM report_decision_audit WHERE report_id=?", report);
        assertEquals(action, audit.get(commentTarget ? "comment_action_id" : "content_action_id"));
        assertNull(audit.get(commentTarget ? "content_action_id" : "comment_action_id"));
        assertEquals("你提交的举报已采纳。", jdbc.queryForObject("""
                SELECT body FROM user_notification WHERE recipient_id=? AND type='REPORT_RESULT' AND target_id=?
                """, String.class, reporter.getId(), report));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_notification WHERE recipient_id=? AND type='REPORT_RESULT' AND target_id=?
                """, Integer.class, author.getId(), report));
    }

    @Test
    void rejectsMissingOrWrongTargetActionWithoutClosingTheReport() throws Exception {
        var operator = account(UserRole.ADMIN);
        var reporter = account(UserRole.USER);
        var author = account(UserRole.USER);
        String report = openReport(reporter, content(author));
        String access = token(operator);
        String key = id();
        String action = hide(comment(content(author), author), access);

        problem(decide(report, Map.of("expectedVersion", 1, "decision", "RESOLVE", "reason", "核查"), access, key),
                422, "VALIDATION_ERROR");
        problem(decide(report, resolve(id()), access, key), 404, "RESOURCE_NOT_FOUND");
        problem(decide(report, resolve(action), access, key), 422, "VALIDATION_ERROR");
        assertEquals("OPEN", state(report));
        assertEquals(0, auditCount(report));
        assertEquals(0, noticeCount(report));
    }

    @Test
    void checksExpectedVersionAndRefusesToProcessAnAlreadyClosedReport() throws Exception {
        var operator = account(UserRole.ADMIN);
        String report = openReport(account(UserRole.USER), content(account(UserRole.USER)));
        String access = token(operator);
        var stale = decide(report, Map.of("expectedVersion", 2, "decision", "DISMISS", "reason", "核查"), access, id());
        problem(stale, 409, "RESOURCE_VERSION_CONFLICT");
        assertEquals(1, JsonPath.parse(stale.body()).read("$.currentVersion", Integer.class));
        assertEquals("OPEN", state(report));

        assertEquals(200, decide(report, dismiss("核查"), access, id()).statusCode());
        problem(decide(report, Map.of("expectedVersion", 2, "decision", "DISMISS", "reason", "再次处理"), access, id()),
                409, "REPORT_STATE_CONFLICT");
        assertEquals(1, auditCount(report));
        assertEquals(1, noticeCount(report));
    }

    @Test
    void replaysTheOriginalResultAndBindsTheKeyToOperatorAndReport() throws Exception {
        var first = account(UserRole.ADMIN);
        var second = account(UserRole.MODERATOR);
        var reporter = account(UserRole.USER);
        var author = account(UserRole.USER);
        String report = openReport(reporter, content(author));
        String other = openReport(reporter, content(author));
        String access = token(first);
        String key = id();
        var original = decide(report, dismiss("   核查   "), access, key);
        assertEquals(200, original.statusCode(), original.body());

        var replay = decide(report, dismiss("核查"), access, key);
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(JsonPath.parse(original.body()).read("$.data", Map.class), JsonPath.parse(replay.body()).read("$.data", Map.class));
        problem(decide(report, dismiss("其他说明"), access, key), 409, "IDEMPOTENCY_KEY_REUSED");
        problem(decide(other, dismiss("核查"), access, key), 409, "IDEMPOTENCY_KEY_REUSED");
        assertEquals(200, decide(other, dismiss("核查"), token(second), key).statusCode());
        assertEquals(1, auditCount(report));
        assertEquals(1, noticeCount(report));
    }

    @Test
    void concurrentSameKeyReceivesRetryAfterWhileTheFirstTransactionIsOpen() throws Exception {
        var operator = account(UserRole.ADMIN);
        var reporter = account(UserRole.USER);
        String report = openReport(reporter, content(account(UserRole.USER)));
        String access = token(operator);
        String key = id();
        var writing = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        doAnswer(call -> {
            int written = writeNotification(call.getArgument(0), reporter.getId(), report, call.getArgument(3), call.getArgument(4));
            writing.countDown();
            assertTrue(finish.await(12, TimeUnit.SECONDS));
            return written;
        }).when(notifications).insertReportResult(anyString(), eq(reporter.getId()), eq(report), anyString(), any());
        var first = http.sendAsync(postRequest(PATH + "/" + report + "/decisions", json.writeValueAsString(dismiss("核查")),
                "application/json", access, key).build(), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(writing.await(8, TimeUnit.SECONDS));
            var duplicate = decide(report, dismiss("核查"), access, key);
            problem(duplicate, 409, "REQUEST_IN_PROGRESS");
            assertTrue(Integer.parseInt(duplicate.headers().firstValue("Retry-After").orElseThrow()) >= 1);
            assertEquals("OPEN", state(report));
            assertEquals(0, noticeCount(report));
        } finally {
            finish.countDown();
        }
        assertEquals(200, first.get(15, TimeUnit.SECONDS).statusCode());
        assertEquals(1, auditCount(report));
        assertEquals(1, noticeCount(report));
    }

    @Test
    void competingOperatorsOnlyCommitOneDecisionForTheReport() throws Exception {
        String report = openReport(account(UserRole.USER), content(account(UserRole.USER)));
        String first = token(account(UserRole.ADMIN));
        String second = token(account(UserRole.MODERATOR));
        String path = PATH + "/" + report + "/decisions";
        String body = json.writeValueAsString(dismiss("核查"));
        var firstResponse = http.sendAsync(postRequest(path, body, "application/json", first, id()).build(),
                HttpResponse.BodyHandlers.ofString());
        var secondResponse = http.sendAsync(postRequest(path, body, "application/json", second, id()).build(),
                HttpResponse.BodyHandlers.ofString());

        var responses = List.of(firstResponse.get(15, TimeUnit.SECONDS), secondResponse.get(15, TimeUnit.SECONDS));
        assertEquals(List.of(200, 409), responses.stream().map(HttpResponse::statusCode).sorted().toList());
        var conflict = responses.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow();
        problem(conflict, 409, "RESOURCE_VERSION_CONFLICT");
        assertEquals(2, JsonPath.parse(conflict.body()).read("$.currentVersion", Integer.class));
        assertEquals(1, auditCount(report));
        assertEquals(1, noticeCount(report));
    }

    @Test
    void expiredIdempotencyKeyCanBeReusedForAnotherReport() throws Exception {
        var operator = account(UserRole.ADMIN);
        var reporter = account(UserRole.USER);
        var author = account(UserRole.USER);
        String access = token(operator);
        String first = openReport(reporter, content(author));
        String second = openReport(reporter, content(author));
        String key = id();
        assertEquals(200, decide(first, dismiss("核查"), access, key).statusCode());
        jdbc.update("""
                UPDATE report_decision_request SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND
                WHERE operator_id=? AND key_hash=?
                """, operator.getId(), TokenDigest.sha256(key));

        var response = decide(second, dismiss("再次核查"), access, key);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(second, JsonPath.parse(response.body()).read("$.data.id", String.class));
        assertEquals(1, auditCount(first));
        assertEquals(1, auditCount(second));
    }

    @Test
    void rollsBackFailedClosureWithoutUndoingExistingModerationAndAllowsSameKeyRetry() throws Exception {
        var operator = account(UserRole.MODERATOR);
        var reporter = account(UserRole.USER);
        var author = account(UserRole.USER);
        String target = comment(content(author), author);
        String report = openReport(reporter, "COMMENT", target);
        String access = token(operator);
        String action = hide(target, access);
        String key = id();
        var failOnce = new AtomicBoolean(true);
        doAnswer(call -> {
            int written = writeNotification(call.getArgument(0), reporter.getId(), report, call.getArgument(3), call.getArgument(4));
            if (failOnce.getAndSet(false)) throw new DataAccessResourceFailureException("Test report notice write failure");
            return written;
        }).when(notifications).insertReportResult(anyString(), eq(reporter.getId()), eq(report), anyString(), any());

        problem(decide(report, resolve(action), access, key), 503, "DEPENDENCY_UNAVAILABLE");
        assertEquals("OPEN", state(report));
        assertEquals("HIDDEN", jdbc.queryForObject("SELECT state FROM content_comment WHERE id=?", String.class, target));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM comment_moderation_action WHERE id=?", Integer.class, action));
        assertEquals(0, auditCount(report));
        assertEquals(0, noticeCount(report));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM report_decision_request WHERE operator_id=?",
                Integer.class, operator.getId()));
        assertEquals(200, decide(report, resolve(action), access, key).statusCode());
        assertEquals("RESOLVED", state(report));
        assertEquals(1, auditCount(report));
        assertEquals(1, noticeCount(report));
    }

    @Test
    void requiresAnActiveAuthorizedAccount() throws Exception {
        var reporter = account(UserRole.USER);
        var moderator = account(UserRole.MODERATOR);
        String access = token(moderator);
        String report = openReport(reporter, content(account(UserRole.USER)));
        problem(decide(report, dismiss("核查"), null, id()), 401, "AUTHENTICATION_REQUIRED");
        problem(decide(report, dismiss("核查"), token(reporter), id()), 403, "PERMISSION_DENIED");
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", moderator.getId());
        problem(decide(report, dismiss("核查"), access, id()), 403, "ACCOUNT_RESTRICTED");
        assertEquals("OPEN", state(report));
        assertEquals(0, auditCount(report));
    }

    @Test
    void rejectsMalformedHeadersQueriesAndJsonAndEnforcesBodyLimits() throws Exception {
        String report = openReport(account(UserRole.USER), content(account(UserRole.USER)));
        String access = token(account(UserRole.ADMIN));
        String key = id();
        String path = PATH + "/" + report + "/decisions";
        String body = json.writeValueAsString(dismiss("核查"));
        problem(raw(path, body, "application/json", access, null), 400, "MALFORMED_REQUEST");
        problem(raw(path, body, "application/json", access, "short"), 422, "VALIDATION_ERROR");
        problem(raw(path, body, "text/plain", access, key), 415, "UNSUPPORTED_MEDIA_TYPE");
        problem(raw(path, body + " ".repeat(32 * 1024), "application/json", access, key), 413, "PAYLOAD_TOO_LARGE");
        problem(post(path + "?override=true", dismiss("核查"), access, key), 400, "MALFORMED_REQUEST");
        var duplicate = postRequest(path, body, "application/json", access, key).header("Idempotency-Key", id()).build();
        problem(http.send(duplicate, HttpResponse.BodyHandlers.ofString()), 400, "MALFORMED_REQUEST");
        problem(post(path, Map.of("expectedVersion", 1.5, "decision", "DISMISS", "reason", "核查"), access, key),
                400, "MALFORMED_REQUEST");
        problem(post(path, Map.of("expectedVersion", 2147483648L, "decision", "DISMISS", "reason", "核查"), access, key),
                422, "VALIDATION_ERROR");
        problem(post(path, Map.of("expectedVersion", 1, "decision", "DISMISS", "reason", "核查", "extra", true), access, key),
                400, "MALFORMED_REQUEST");
        problem(post(path, dismiss(" \u3000 "), access, key), 422, "VALIDATION_ERROR");
        problem(post(path, dismiss("😀".repeat(1001)), access, key), 422, "VALIDATION_ERROR");
        assertEquals("OPEN", state(report));
        assertEquals(0, auditCount(report));
    }

    @Test
    void limitsNewDecisionsByOperatorAndSourceWhileAllowingReplay() throws Exception {
        var reporter = account(UserRole.USER);
        var author = account(UserRole.USER);
        String first = token(account(UserRole.ADMIN));
        String second = token(account(UserRole.MODERATOR));
        String original = openReport(reporter, content(author));
        String key = id();
        assertEquals(200, decide(original, dismiss("核查"), first, key).statusCode());
        for (int i = 0; i < 3; i++) {
            assertEquals(200, decide(openReport(reporter, content(author)), dismiss("核查"), first, id()).statusCode());
        }
        String pending = openReport(reporter, content(author));
        rateLimited(decide(pending, dismiss("核查"), first, id()));
        assertEquals(200, decide(original, dismiss("核查"), first, key).statusCode());
        for (int i = 0; i < 3; i++) {
            assertEquals(200, decide(openReport(reporter, content(author)), dismiss("核查"), second, id()).statusCode());
        }
        rateLimited(decide(pending, dismiss("核查"), second, id()));
        assertEquals("OPEN", state(pending));
        assertEquals(1, noticeCount(original));
    }

    @Test
    void documentsPermissionsAndConditionalModerationActionRequirement() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var doc = JsonPath.parse(response.body());
        String operation = "$.paths['" + PATH + "/{reportId}/decisions'].post";
        assertEquals("decideReport", doc.read(operation + ".operationId", String.class));
        assertEquals(List.of("report:decide"), doc.read(operation + "['x-permissions']", List.class));
        assertEquals(Boolean.TRUE, doc.read(operation + "['x-idempotency-required']", Boolean.class));
        assertEquals(Set.of("expectedVersion", "reason", "decision"),
                Set.copyOf(doc.read("$.components.schemas.ReportDecisionRequest.required", List.class)));
        assertEquals("RESOLVE", doc.read("$.components.schemas.ReportDecisionRequest.allOf[0].if.properties.decision.const", String.class));
        assertEquals(List.of("moderationActionId"),
                doc.read("$.components.schemas.ReportDecisionRequest.allOf[0].then.required", List.class));
    }

    private String openReport(UserAccount reporter, String content) {
        return openReport(reporter, "CONTENT", content);
    }

    private String openReport(UserAccount reporter, String type, String target) {
        String report = id();
        jdbc.update("""
                INSERT INTO user_report(id, reporter_id, target_type, content_id, comment_id, reason_code, description, created_at)
                VALUES (?, ?, ?, ?, ?, 'SPAM', '举报证据', UTC_TIMESTAMP(6))
                """, report, reporter.getId(), type, "CONTENT".equals(type) ? target : null, "COMMENT".equals(type) ? target : null);
        return report;
    }

    private String comment(String content, UserAccount author) {
        String comment = id();
        jdbc.update("""
                INSERT INTO content_comment(id, content_id, author_id, body, state, created_at, updated_at)
                VALUES (?, ?, ?, '评论目标', 'VISIBLE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, comment, content, author.getId());
        commentIds.add(comment);
        return comment;
    }

    private String publish(UserAccount author) {
        String content = content(author);
        String revision = id();
        jdbc.update("""
                INSERT INTO content_revision(id, content_id, revision_number, status, title, body_markdown, created_at, updated_at)
                VALUES (?, ?, 1, 'APPROVED', '测试作品标题', '测试正文', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, revision, content);
        jdbc.update("""
                UPDATE community_content SET publication_status='PUBLISHED', published_revision_id=?,
                    first_published_at=UTC_TIMESTAMP(6), published_at=UTC_TIMESTAMP(6) WHERE id=?
                """, revision, content);
        return content;
    }

    private String takeDown(String target, String access) throws Exception {
        var response = post("/api/v1/admin/contents/" + target + "/take-downs",
                Map.of("expectedVersion", 1, "reasonCode", "SPAM", "reason", "已核实并下架"), access, id());
        assertEquals(201, response.statusCode(), response.body());
        return JsonPath.parse(response.body()).read("$.data.id", String.class);
    }

    private String hide(String target, String access) throws Exception {
        var response = post("/api/v1/admin/comments/" + target + "/hides",
                Map.of("expectedVersion", 1, "reason", "已核实并隐藏"), access, id());
        assertEquals(201, response.statusCode(), response.body());
        return JsonPath.parse(response.body()).read("$.data.id", String.class);
    }

    private static Map<String, Object> resolve(String action) {
        return Map.of("expectedVersion", 1, "decision", "RESOLVE", "reason", "已核查并处置", "moderationActionId", action);
    }

    private static Map<String, Object> dismiss(String reason) {
        return Map.of("expectedVersion", 1, "decision", "DISMISS", "reason", reason);
    }

    private String state(String report) {
        return jdbc.queryForObject("SELECT state FROM user_report WHERE id=?", String.class, report);
    }

    private int auditCount(String report) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM report_decision_audit WHERE report_id=?", Integer.class, report);
    }

    private int noticeCount(String report) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE type='REPORT_RESULT' AND target_id=?",
                Integer.class, report);
    }

    private int writeNotification(String id, String recipient, String report, String body, LocalDateTime now) {
        return sqlSession.insert(NotificationWriteMapper.class.getName() + ".insertReportResult",
                Map.of("id", id, "recipient", recipient, "reportId", report, "body", body, "now", now));
    }

    private String content(UserAccount author) {
        String content = id();
        jdbc.update("""
                INSERT INTO community_content(id, author_id, kind, lifecycle, publication_status, version, created_at, updated_at)
                VALUES (?, ?, 'ARTICLE', 'ACTIVE', 'UNPUBLISHED', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, content, author.getId());
        return content;
    }

    private UserAccount account(UserRole role) {
        var account = LoginTestSupport.account();
        account.setId(id());
        account.setEmail("report-decision-" + account.getId() + "@example.test");
        account.setEmailNormalized(account.getEmail());
        accountIds.add(account.getId());
        users.insert(account);
        jdbc.update("INSERT INTO app_user_role(user_id, role_code, created_at) VALUES (?, ?, UTC_TIMESTAMP(6))",
                account.getId(), role.name());
        return account;
    }

    private HttpResponse<String> decide(String report, Map<String, Object> body, String token, String key) throws Exception {
        return post(PATH + "/" + report + "/decisions", body, token, key);
    }

    private HttpResponse<String> post(String path, Map<String, Object> body, String token, String key) throws Exception {
        return raw(path, json.writeValueAsString(body), "application/json", token, key);
    }

    private HttpResponse<String> raw(String path, String body, String contentType, String token, String key) throws Exception {
        return http.send(postRequest(path, body, contentType, token, key).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder postRequest(String path, String body, String contentType, String token, String key) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        return request;
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.parse(response.body()).read("$.code", String.class));
    }

    private static void rateLimited(HttpResponse<String> response) {
        problem(response, 429, "RATE_LIMITED");
        assertTrue(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow()) >= 1);
    }

    private String token(UserAccount user) {
        return sessions.create(user.getId(), user.getAuthVersion(), "report-decision-it").accessToken();
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
