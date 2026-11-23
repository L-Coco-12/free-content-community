package com.freecontent.community.report.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.report.mapper.ReportMapper;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.processing.enabled=false",
        "app.media.upload-cleanup.enabled=false", "app.report.submission.user-per-minute-limit=6"})
class ReportSubmissionFlowIT {
    private static final String PATH = "/api/v1/reports";
    private static final String PREFIX = "test:report-create:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private SqlSessionTemplate sqlSession;
    @MockitoSpyBean private ReportMapper reports;
    private final List<String> userIds = new ArrayList<>();
    private final List<String> commentIds = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.report.submission.redis-key-prefix", () -> PREFIX);
        registry.add("app.report.query.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        var keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String userId : userIds) {
            jdbc.update("DELETE FROM report_submission_request WHERE reporter_id=?", userId);
            jdbc.update("DELETE a FROM report_attachment a JOIN user_report r ON r.id=a.report_id WHERE r.reporter_id=?", userId);
            jdbc.update("DELETE FROM user_report WHERE reporter_id=?", userId);
        }
        for (int i = commentIds.size() - 1; i >= 0; i--) {
            jdbc.update("DELETE FROM content_comment WHERE id=?", commentIds.get(i));
        }
        for (String userId : userIds) {
            ContentTestSupport.clearWorkspace(jdbc, userId);
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?", userId);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", userId);
            users.deleteById(userId);
        }
    }

    @Test
    void createsPrivateOpenReportAndReturnsTheContractLocation() throws Exception {
        var author = account();
        var reporter = account();
        String content = publish(author);
        String access = token(reporter);

        var response = report("CONTENT", content, "SPAM", "内容包含重复广告", List.of(), access, id());

        assertEquals(201, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var data = JsonPath.parse(response.body());
        String reportId = data.read("$.data.id", String.class);
        String location = response.headers().firstValue("Location").orElseThrow();
        assertEquals("/api/v1/me/reports/" + reportId, location);
        assertEquals(Set.of("id", "targetType", "targetId", "reasonCode", "description", "state", "version",
                "createdAt", "resolvedAt", "resolution"), data.read("$.data", Map.class).keySet());
        assertEquals(content, data.read("$.data.targetId", String.class));
        assertEquals("OPEN", data.read("$.data.state", String.class));
        assertEquals(1, data.read("$.data.version", Integer.class));
        assertNull(data.read("$.data.resolvedAt"));
        assertNull(data.read("$.data.resolution"));
        assertEquals(reporter.getId(), jdbc.queryForObject("SELECT reporter_id FROM user_report WHERE id=?", String.class, reportId));
        var read = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + location))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + access).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, read.statusCode(), read.body());
        assertEquals(data.read("$.data", Map.class), JsonPath.parse(read.body()).read("$.data", Map.class));
    }

    @Test
    void storesCommentTargetAndKeepsReadyAttachmentsInRequestOrder() throws Exception {
        var author = account();
        var reporter = account();
        String comment = comment(publish(author), author, null);
        String first = asset(reporter, "REPORT_ATTACHMENT", "READY", "z");
        String second = asset(reporter, "REPORT_ATTACHMENT", "READY", "a");
        String description = "😀".repeat(1000);

        var response = report("COMMENT", comment, "OTHER", description, List.of(first, second), token(reporter), id());

        assertEquals(201, response.statusCode(), response.body());
        String reportId = reportId(response);
        assertEquals("COMMENT", JsonPath.read(response.body(), "$.data.targetType"));
        assertEquals(description, JsonPath.read(response.body(), "$.data.description"));
        assertEquals(comment, jdbc.queryForObject("SELECT comment_id FROM user_report WHERE id=?", String.class, reportId));
        assertEquals(List.of(first, second), jdbc.queryForList(
                "SELECT asset_id FROM report_attachment WHERE report_id=? ORDER BY position", String.class, reportId));
    }

    @Test
    void unavailableContentAndCommentPlaceholdersReturnNotFound() throws Exception {
        var author = account();
        var reporter = account();
        String access = token(reporter);
        String content = publish(author);
        String root = comment(content, author, null);
        String reply = comment(content, author, root);
        for (String state : List.of("HIDDEN", "DELETED")) {
            jdbc.update("UPDATE content_comment SET state=? WHERE id=?", state, root);
            problem(report("COMMENT", root, "SPAM", "", List.of(), access, id()), 404, "RESOURCE_NOT_FOUND");
        }
        jdbc.update("UPDATE community_content SET publication_status='TAKEN_DOWN' WHERE id=?", content);
        problem(report("CONTENT", content, "SPAM", "", List.of(), access, id()), 404, "RESOURCE_NOT_FOUND");
        problem(report("COMMENT", reply, "SPAM", "", List.of(), access, id()), 404, "RESOURCE_NOT_FOUND");
        problem(report("CONTENT", id(), "SPAM", "", List.of(), access, id()), 404, "RESOURCE_NOT_FOUND");
        assertEquals(0, reportCount(reporter));
    }

    @Test
    void rejectsForeignMissingWrongSceneAndUnreadyAttachments() throws Exception {
        var author = account();
        var reporter = account();
        String content = publish(author);
        String access = token(reporter);
        String foreign = asset(author, "REPORT_ATTACHMENT", "READY", "");
        String wrongScene = asset(reporter, "AVATAR", "READY", "");
        String pending = asset(reporter, "REPORT_ATTACHMENT", "PROCESSING", "");

        problem(report("CONTENT", content, "ABUSE", "", List.of(foreign), access, id()), 404, "RESOURCE_NOT_FOUND");
        problem(report("CONTENT", content, "ABUSE", "", List.of(id()), access, id()), 404, "RESOURCE_NOT_FOUND");
        problem(report("CONTENT", content, "ABUSE", "", List.of(wrongScene), access, id()), 422, "VALIDATION_ERROR");
        String pendingKey = id();
        problem(report("CONTENT", content, "ABUSE", "", List.of(pending), access, pendingKey), 409, "MEDIA_NOT_READY");
        assertEquals(0, reportCount(reporter));

        jdbc.update("UPDATE media_asset SET state='READY' WHERE id=?", pending);
        assertEquals(201, report("CONTENT", content, "ABUSE", "", List.of(pending), access, pendingKey).statusCode());
    }

    @Test
    void replaysOriginalReceiptAfterResolutionAndAllowsNewReport() throws Exception {
        var author = account();
        var reporter = account();
        String content = publish(author);
        String access = token(reporter);
        String key = id();
        var original = raw(access, key, "application/json",
                "{\"targetType\":\"CONTENT\",\"targetId\":\"" + content + "\",\"reasonCode\":\"SPAM\"}");
        assertEquals(201, original.statusCode(), original.body());
        String reportId = reportId(original);
        problem(report("CONTENT", content, "SPAM", "", List.of(), access, id()), 409, "REPORT_ALREADY_OPEN");
        problem(report("CONTENT", content, "ABUSE", "", List.of(), access, key), 409, "IDEMPOTENCY_KEY_REUSED");
        close(reportId);
        jdbc.update("UPDATE community_content SET publication_status='WITHDRAWN' WHERE id=?", content);

        var replay = report("CONTENT", content, "SPAM", "", List.of(), access, key);
        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(original.headers().firstValue("Location"), replay.headers().firstValue("Location"));
        assertEquals(JsonPath.parse(original.body()).read("$.data", Map.class),
                JsonPath.parse(replay.body()).read("$.data", Map.class));
        jdbc.update("UPDATE community_content SET publication_status='PUBLISHED' WHERE id=?", content);
        var next = report("CONTENT", content, "SPAM", "", List.of(), access, id());
        assertEquals(201, next.statusCode(), next.body());
        assertNotEquals(reportId, reportId(next));
        assertEquals(2, reportCount(reporter));
    }

    @Test
    void scopesKeyByReporterAndAllowsReuseAfterExpiration() throws Exception {
        var author = account();
        var first = account();
        var second = account();
        String content = publish(author);
        String key = id();
        String firstAccess = token(first);
        var original = report("CONTENT", content, "SPAM", "", List.of(), firstAccess, key);
        var independent = report("CONTENT", content, "ABUSE", "", List.of(), token(second), key);
        assertEquals(201, original.statusCode(), original.body());
        assertEquals(201, independent.statusCode(), independent.body());
        assertNotEquals(reportId(original), reportId(independent));
        close(reportId(original));
        jdbc.update("UPDATE report_submission_request SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE report_id=?",
                reportId(original));

        var afterExpiry = report("CONTENT", content, "COPYRIGHT", "", List.of(), firstAccess, key);
        assertEquals(201, afterExpiry.statusCode(), afterExpiry.body());
        assertNotEquals(reportId(original), reportId(afterExpiry));
    }

    @Test
    void concurrentKeysStillCreateOnlyOneOpenReport() throws Exception {
        var author = account();
        var reporter = account();
        String content = publish(author);
        String access = token(reporter);
        var first = http.sendAsync(reportRequest("CONTENT", content, "SPAM", "", List.of(), access, id()),
                HttpResponse.BodyHandlers.ofString());
        var second = http.sendAsync(reportRequest("CONTENT", content, "ABUSE", "", List.of(), access, id()),
                HttpResponse.BodyHandlers.ofString());

        var responses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        assertEquals(List.of(201, 409), responses.stream().map(HttpResponse::statusCode).sorted().toList(),
                responses.toString());
        problem(responses.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow(),
                409, "REPORT_ALREADY_OPEN");
        assertEquals(1, reportCount(reporter));
    }

    @Test
    void liveLeaseReturnsRetryAfterWithoutWaitingForCreationToFinish() throws Exception {
        var author = account();
        var reporter = account();
        String content = publish(author);
        String access = token(reporter);
        String key = id();
        var writing = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            var submission = invocation.getArgument(0, ReportMapper.Submission.class);
            int inserted = sqlSession.insert(ReportMapper.class.getName() + ".insert",
                    Map.of("r", submission, "reporter", reporter.getId()));
            writing.countDown();
            if (!finish.await(12, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for the duplicate request");
            return inserted;
        }).when(reports).insert(any(), eq(reporter.getId()));
        var initial = http.sendAsync(reportRequest("CONTENT", content, "SPAM", "", List.of(), access, key),
                HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(writing.await(8, TimeUnit.SECONDS));
            var duplicate = report("CONTENT", content, "SPAM", "", List.of(), access, key);
            problem(duplicate, 409, "REQUEST_IN_PROGRESS");
            assertTrue(Integer.parseInt(duplicate.headers().firstValue("Retry-After").orElseThrow()) >= 1);
        } finally {
            finish.countDown();
        }
        var created = initial.get(15, TimeUnit.SECONDS);
        assertEquals(201, created.statusCode(), created.body());
    }

    @Test
    void attachmentWriteFailureRollsBackReportAndReleasesKeyForRetry() throws Exception {
        var author = account();
        var reporter = account();
        String content = publish(author);
        String access = token(reporter);
        String first = asset(reporter, "REPORT_ATTACHMENT", "READY", "a");
        String second = asset(reporter, "REPORT_ATTACHMENT", "READY", "b");
        String key = id();
        var failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            String reportId = invocation.getArgument(0);
            int inserted = sqlSession.insert(ReportMapper.class.getName() + ".attach",
                    Map.of("reportId", reportId, "assetId", second, "position", 1));
            if (failOnce.getAndSet(false)) throw new DataAccessResourceFailureException("Test attachment write failure");
            return inserted;
        }).when(reports).attach(anyString(), eq(second), eq(1));

        problem(report("CONTENT", content, "ABUSE", "", List.of(first, second), access, key), 503, "DEPENDENCY_UNAVAILABLE");
        assertEquals(0, reportCount(reporter));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM report_attachment WHERE asset_id IN (?,?)",
                Integer.class, first, second));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM report_submission_request WHERE reporter_id=?",
                Integer.class, reporter.getId()));

        var retry = report("CONTENT", content, "ABUSE", "", List.of(first, second), access, key);
        assertEquals(201, retry.statusCode(), retry.body());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM report_attachment WHERE report_id=?",
                Integer.class, reportId(retry)));
    }

    @Test
    void limitsNewSubmissionsButStillReplaysIdenticalRequest() throws Exception {
        var author = account();
        var reporter = account();
        String content = publish(author);
        String access = token(reporter);
        String key = id();
        var original = report("CONTENT", content, "SPAM", "", List.of(), access, key);
        assertEquals(201, original.statusCode(), original.body());
        for (int i = 0; i < 5; i++) {
            problem(report("CONTENT", content, "SPAM", "", List.of(), access, id()), 409, "REPORT_ALREADY_OPEN");
        }
        var limited = report("CONTENT", content, "SPAM", "", List.of(), access, id());
        problem(limited, 429, "RATE_LIMITED");
        assertTrue(Integer.parseInt(limited.headers().firstValue("Retry-After").orElseThrow()) >= 1);

        var replay = report("CONTENT", content, "SPAM", "", List.of(), access, key);
        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(reportId(original), reportId(replay));
    }

    @Test
    void requiresAnAuthenticatedActiveVerifiedAccount() throws Exception {
        var reporter = account();
        String access = token(reporter);
        String content = publish(account());
        problem(report("CONTENT", content, "SPAM", "", List.of(), null, id()), 401, "AUTHENTICATION_REQUIRED");
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", reporter.getId());
        problem(report("CONTENT", content, "SPAM", "", List.of(), access, id()), 403, "ACCOUNT_RESTRICTED");
        jdbc.update("UPDATE app_user SET account_status='ACTIVE', email_verified=FALSE WHERE id=?", reporter.getId());
        problem(report("CONTENT", content, "SPAM", "", List.of(), access, id()), 403, "PERMISSION_DENIED");
        assertEquals(0, reportCount(reporter));
    }

    @Test
    void validatesJsonHeaderAndUnicodeBoundaries() throws Exception {
        String access = token(account());
        String required = "{\"targetType\":\"CONTENT\",\"targetId\":\"target_1\",\"reasonCode\":\"SPAM\"";
        String key = id();
        problem(raw(access, null, "application/json", required + "}"), 400, "MALFORMED_REQUEST");
        problem(raw(access, "short", "application/json", required + "}"), 422, "VALIDATION_ERROR");
        problem(raw(access, key, "text/plain", required + "}"), 415, "UNSUPPORTED_MEDIA_TYPE");
        problem(raw(access, key, "application/json", required + " ".repeat(32 * 1024) + "}"), 413, "PAYLOAD_TOO_LARGE");
        for (String extra : List.of(",\"unexpected\":true}", ",\"description\":null}", ",\"attachmentAssetIds\":null}",
                ",\"attachmentAssetIds\":[100]}", ",\"attachmentAssetIds\":[null]}")) {
            problem(raw(access, key, "application/json", required + extra), 400, "MALFORMED_REQUEST");
        }
        problem(raw(access, key, "application/json", required.replace("\"CONTENT\"", "0") + "}"), 400, "MALFORMED_REQUEST");
        for (String extra : List.of(",\"description\":\"" + "😀".repeat(1001) + "\"}", ",\"attachmentAssetIds\":[\"a\",\"a\"]}",
                ",\"attachmentAssetIds\":[\"a\",\"b\",\"c\",\"d\"]}", ",\"attachmentAssetIds\":[\"bad/id\"]}")) {
            problem(raw(access, key, "application/json", required + extra), 422, "VALIDATION_ERROR");
        }
        String other = required.replace("\"SPAM\"", "\"OTHER\"");
        problem(raw(access, key, "application/json", other + "}"), 422, "VALIDATION_ERROR");
        problem(raw(access, key, "application/json", other + ",\"description\":\" \u3000\"}"), 422, "VALIDATION_ERROR");
        problem(raw(access, key, "application/json", required.replace("target_1", "bad/id") + "}"), 422, "VALIDATION_ERROR");
    }

    @Test
    void documentsConditionalDescriptionAndPrivateCreationResponse() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var doc = JsonPath.parse(response.body());
        String operation = "$.paths['" + PATH + "'].post";
        assertEquals("createReport", doc.read(operation + ".operationId", String.class));
        assertEquals(Boolean.TRUE, doc.read(operation + "['x-idempotency-required']", Boolean.class));
        assertEquals("/api/v1/me/reports/{reportId}",
                doc.read(operation + ".responses['201'].headers.Location.description", String.class));
        assertEquals("#/components/schemas/Report",
                doc.read(operation + ".requestBody.content['application/json'].schema['$ref']", String.class));
        assertEquals(Boolean.TRUE, doc.read("$.components.schemas.Report.properties.attachmentAssetIds.uniqueItems", Boolean.class));
        assertEquals("OTHER", doc.read("$.components.schemas.Report.allOf[0].if.properties.reasonCode.const", String.class));
        assertEquals(List.of("description"), doc.read("$.components.schemas.Report.allOf[0].then.required", List.class));
        assertEquals(Set.of("id", "targetType", "targetId", "reasonCode", "description", "state", "version",
                "createdAt", "resolvedAt", "resolution"), Set.copyOf(doc.read("$.components.schemas.ReportRecord.required", List.class)));
    }

    private UserAccount account() {
        var account = LoginTestSupport.account();
        account.setId(id());
        account.setEmail("report-create-" + account.getId() + "@example.test");
        account.setEmailNormalized(account.getEmail());
        userIds.add(account.getId());
        users.insert(account);
        roles.insertDefaultRole(account.getId(), account.getCreatedAt());
        return account;
    }

    private String publish(UserAccount author) {
        String content = id();
        String revision = id();
        jdbc.update("""
                INSERT INTO community_content(id, author_id, kind, lifecycle, publication_status, version, created_at, updated_at)
                VALUES (?, ?, 'ARTICLE', 'ACTIVE', 'UNPUBLISHED', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, content, author.getId());
        jdbc.update("""
                INSERT INTO content_revision(id, content_id, revision_number, status, title, body_markdown, created_at, updated_at)
                VALUES (?, ?, 1, 'APPROVED', '举报测试标题', '公开正文', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, revision, content);
        jdbc.update("""
                UPDATE community_content SET published_revision_id=?, publication_status='PUBLISHED',
                    first_published_at=UTC_TIMESTAMP(6), published_at=UTC_TIMESTAMP(6) WHERE id=?
                """, revision, content);
        return content;
    }

    private HttpResponse<String> report(String targetType, String target, String reason, String description,
                                        List<String> attachments, String token, String key) throws Exception {
        return http.send(reportRequest(targetType, target, reason, description, attachments, token, key),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest reportRequest(String targetType, String target, String reason, String description,
                                      List<String> attachments, String token, String key) {
        String json = jsonMapper.writeValueAsString(Map.of("targetType", targetType, "targetId", target,
                "reasonCode", reason, "description", description, "attachmentAssetIds", attachments));
        return request(token, key, "application/json", json);
    }

    private HttpResponse<String> raw(String token, String key, String type, String json) throws Exception {
        return http.send(request(token, key, type, json), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String token, String key, String type, String json) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        return request.build();
    }

    private String comment(String content, UserAccount author, String root) {
        String comment = id();
        jdbc.update("""
                INSERT INTO content_comment(id, content_id, author_id, root_comment_id, reply_to_comment_id,
                    body, state, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, '评论正文', 'VISIBLE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, comment, content, author.getId(), root, root);
        commentIds.add(comment);
        return comment;
    }

    private String asset(UserAccount owner, String scene, String state, String prefix) {
        String asset = prefix + id();
        jdbc.update("""
                INSERT INTO media_asset(id, owner_id, scene, file_name, declared_content_type, declared_size_bytes,
                    storage_key, state, actual_content_type, actual_size_bytes, created_at)
                VALUES (?, ?, ?, 'attachment.png', 'image/png', 100, ?, ?, 'image/png', 100, UTC_TIMESTAMP(6))
                """, asset, owner.getId(), scene, "test-report/" + asset, state);
        return asset;
    }

    private void close(String reportId) {
        jdbc.update("""
                UPDATE user_report SET state='DISMISSED', version=2, resolved_at=UTC_TIMESTAMP(6), resolution='已处理'
                WHERE id=?
                """, reportId);
    }

    private int reportCount(UserAccount reporter) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_report WHERE reporter_id=?", Integer.class, reporter.getId());
    }

    private static String reportId(HttpResponse<String> response) {
        return JsonPath.parse(response.body()).read("$.data.id", String.class);
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.read(response.body(), "$.code"));
    }

    private String token(UserAccount account) {
        return sessions.create(account.getId(), account.getAuthVersion(), "report-create-it").accessToken();
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
