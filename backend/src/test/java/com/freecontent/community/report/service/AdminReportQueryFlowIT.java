package com.freecontent.community.report.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.report.dto.ReportRecord.State;
import com.freecontent.community.report.mapper.AdminReportQueryMapper;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.model.UserRole;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.processing.enabled=false",
        "app.media.upload-cleanup.enabled=false"})
class AdminReportQueryFlowIT {
    private static final String PATH = "/api/v1/admin/reports";
    private static final String PREFIX = "test:admin-reports:" + UUID.randomUUID() + ":";
    private static final LocalDateTime PENDING_TIME = LocalDateTime.of(2000, 1, 1, 8, 0);
    private static final LocalDateTime HISTORY_TIME = LocalDateTime.of(2099, 1, 1, 8, 0);
    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserAccountMapper users;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private SqlSessionTemplate sqlSession;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @MockitoSpyBean private AdminReportQueryMapper reports;
    private final List<String> userIds = new ArrayList<>();
    private final List<String> commentIds = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.report.admin-query.redis-key-prefix", () -> PREFIX);
        registry.add("app.report.admin-query.user-per-minute-limit", () -> 4);
        registry.add("app.report.admin-query.ip-per-minute-limit", () -> 6);
        registry.add("app.report.query.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        var keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String userId : userIds) {
            jdbc.update("DELETE a FROM report_attachment a JOIN user_report r ON r.id=a.report_id WHERE r.reporter_id=?", userId);
            jdbc.update("DELETE FROM user_report WHERE reporter_id=?", userId);
        }
        for (String commentId : commentIds) jdbc.update("DELETE FROM content_comment WHERE id=?", commentId);
        for (String userId : userIds) {
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?", userId);
            ContentTestSupport.clearWorkspace(jdbc, userId);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", userId);
            users.deleteById(userId);
        }
    }

    @Test
    void moderatorListsOpenReportsByDefaultWithReporterAndOrderedAttachments() throws Exception {
        var moderator = account(UserRole.MODERATOR);
        var reporter = account(UserRole.USER);
        String target = content(account(UserRole.USER));
        String open = report(reporter, target, State.OPEN, PENDING_TIME);
        String closed = report(reporter, target, State.DISMISSED, PENDING_TIME.minusSeconds(1));
        String first = asset(reporter);
        String second = asset(reporter);
        jdbc.update("INSERT INTO report_attachment(report_id, asset_id, position) VALUES (?, ?, 1), (?, ?, 0)",
                open, second, open, first);

        var response = get("", token(moderator));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var json = JsonPath.parse(response.body());
        assertEquals(1, json.read("$.data.page", Integer.class));
        assertEquals(20, json.read("$.data.pageSize", Integer.class));
        assertEquals(count(State.OPEN), json.read("$.data.total", Long.class));
        List<Map<String, Object>> items = json.read("$.data.items");
        assertTrue(items.stream().allMatch(item -> "OPEN".equals(item.get("state"))));
        assertTrue(items.stream().noneMatch(item -> closed.equals(item.get("id"))));
        var item = items.stream().filter(row -> open.equals(row.get("id"))).findFirst().orElseThrow();
        assertEquals(Set.of("id", "targetType", "targetId", "reasonCode", "description", "state", "version",
                "createdAt", "resolvedAt", "resolution", "reporterId", "attachmentAssetIds"), item.keySet());
        assertEquals(reporter.getId(), item.get("reporterId"));
        assertEquals("CONTENT", item.get("targetType"));
        assertEquals(target, item.get("targetId"));
        assertEquals(List.of(first, second), item.get("attachmentAssetIds"));
    }

    @Test
    void moderatorReadsAResolvedReportAndOnlyItsAttachmentsAfterTargetTakeDown() throws Exception {
        var reporter = account(UserRole.USER);
        String target = content(account(UserRole.USER));
        String resolved = report(reporter, target, State.RESOLVED, HISTORY_TIME);
        String other = report(reporter, target, State.OPEN, PENDING_TIME);
        String first = asset(reporter);
        String second = asset(reporter);
        jdbc.update("INSERT INTO report_attachment(report_id, asset_id, position) VALUES (?, ?, 1), (?, ?, 0), (?, ?, 0)",
                resolved, second, resolved, first, other, asset(reporter));

        var response = detail(resolved, token(account(UserRole.MODERATOR)));

        var json = ok(response);
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        Map<?, ?> record = json.read("$.data", Map.class);
        assertEquals(Set.of("id", "targetType", "targetId", "reasonCode", "description", "state", "version",
                "createdAt", "resolvedAt", "resolution", "reporterId", "attachmentAssetIds"), record.keySet());
        assertEquals(resolved, record.get("id"));
        assertEquals(target, record.get("targetId"));
        assertEquals(reporter.getId(), record.get("reporterId"));
        assertEquals("RESOLVED", record.get("state"));
        assertEquals(2, record.get("version"));
        assertEquals("2099-01-01T09:00:00Z", record.get("resolvedAt"));
        assertEquals("已处理", record.get("resolution"));
        assertEquals(List.of(first, second), record.get("attachmentAssetIds"));
    }

    @Test
    void pendingPaginationUsesOldestFirstAndIdTieBreakAndHandlesLargePageNumbers() throws Exception {
        var reporter = account(UserRole.USER);
        var author = account(UserRole.USER);
        String older = report(reporter, content(author), State.OPEN, PENDING_TIME.minusSeconds(1));
        String first = report(reporter, content(author), State.OPEN, PENDING_TIME);
        String second = report(reporter, content(author), State.OPEN, PENDING_TIME);
        var tied = new ArrayList<>(List.of(first, second));
        tied.sort(Comparator.naturalOrder());
        String access = token(account(UserRole.ADMIN));

        var pageOne = ok(get("?state=OPEN&page=1&pageSize=2", access));
        var pageTwo = ok(get("?page=2&pageSize=2", access));
        String huge = "999999999999999999999999999";
        var pastEnd = ok(get("?page=" + huge + "&pageSize=100", access));

        assertEquals(List.of(older, tied.get(0)), pageOne.read("$.data.items[*].id", List.class));
        assertEquals(tied.get(1), pageTwo.read("$.data.items[0].id", String.class));
        assertEquals(List.of(), pastEnd.read("$.data.items", List.class));
        assertEquals(huge, String.valueOf(pastEnd.read("$.data.page", Object.class)));
        assertEquals(100, pastEnd.read("$.data.pageSize", Integer.class));
        assertEquals(count(State.OPEN), pastEnd.read("$.data.total", Long.class));
    }

    @Test
    void filtersClosedStatesAndIncludesCurrentResultsForHiddenCommentTargets() throws Exception {
        var reporter = account(UserRole.USER);
        var author = account(UserRole.USER);
        String target = content(author);
        String comment = id();
        jdbc.update("""
                INSERT INTO content_comment(id, content_id, author_id, body, state, created_at, updated_at)
                VALUES (?, ?, ?, '被隐藏的评论', 'HIDDEN', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, comment, target, author.getId());
        commentIds.add(comment);
        String older = report(reporter, target, State.RESOLVED, HISTORY_TIME.minusSeconds(1));
        String newer = report(reporter, "COMMENT", comment, State.RESOLVED, HISTORY_TIME);
        String dismissed = report(reporter, target, State.DISMISSED, HISTORY_TIME);
        String access = token(account(UserRole.ADMIN));

        var resolved = ok(get("?state=RESOLVED&pageSize=2", access));
        var rejected = ok(get("?state=DISMISSED&pageSize=1", access));

        assertEquals(List.of(newer, older), resolved.read("$.data.items[*].id", List.class));
        assertEquals(count(State.RESOLVED), resolved.read("$.data.total", Long.class));
        assertEquals("COMMENT", resolved.read("$.data.items[0].targetType", String.class));
        assertEquals(comment, resolved.read("$.data.items[0].targetId", String.class));
        assertEquals("已处理", resolved.read("$.data.items[0].resolution", String.class));
        assertEquals("2099-01-01T09:00:00Z", resolved.read("$.data.items[0].resolvedAt", String.class));
        assertEquals(List.of(), resolved.read("$.data.items[0].attachmentAssetIds", List.class));
        assertEquals(List.of(dismissed), rejected.read("$.data.items[*].id", List.class));
        assertEquals(count(State.DISMISSED), rejected.read("$.data.total", Long.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void requiresOperatorAuthorizationAndAllowsWriteBlockedOperatorsToRead(boolean byId) throws Exception {
        var reporter = account(UserRole.USER);
        String report = report(reporter, content(account(UserRole.USER)), State.OPEN, PENDING_TIME);
        String path = byId ? PATH + "/" + report : PATH;
        var noBody = HttpRequest.BodyPublishers.noBody();
        problem(send(path, null, noBody), 401, "AUTHENTICATION_REQUIRED");
        problem(send(path, token(reporter), noBody), 403, "PERMISSION_DENIED");
        var adminView = ok(send(path, token(account(UserRole.ADMIN)), noBody));
        if (byId) assertEquals(List.of(), adminView.read("$.data.attachmentAssetIds", List.class));

        var moderator = account(UserRole.MODERATOR);
        String access = token(moderator);
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", moderator.getId());
        ok(send(path, access, noBody));
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", moderator.getId());
        problem(send(path, access, noBody), 403, "ACCOUNT_RESTRICTED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rechecksReportReadPermissionBeforeReadingPrivateData(boolean byId) throws Exception {
        var moderator = account(UserRole.MODERATOR);
        var reporter = account(UserRole.USER);
        String report = report(reporter, content(reporter), State.OPEN, PENDING_TIME);
        String access = token(moderator);
        doAnswer(call -> {
            var principal = call.callRealMethod();
            jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='MODERATOR'", moderator.getId());
            return principal;
        }).when(authentication).authenticate(access);

        problem(byId ? detail(report, access) : get("", access), 403, "PERMISSION_DENIED");
        verify(reports, never()).count(anyString());
        verify(reports, never()).findById(anyString());
        verify(reports, never()).attachments(anyList());
    }

    @Test
    void appliesOnlyContractQueryParametersAndRejectsRequestBodies() throws Exception {
        String access = token(account(UserRole.ADMIN));
        problem(get("?reporterId=another-user", access), 400, "MALFORMED_REQUEST");
        problem(get("?state=OPEN&state=OPEN", access), 400, "MALFORMED_REQUEST");
        problem(get("?state=open", access), 400, "MALFORMED_REQUEST");
        problem(get("?page=1.5", access), 400, "MALFORMED_REQUEST");
        problem(get("?page=0", access), 422, "VALIDATION_ERROR");
        problem(get("?pageSize=101", access), 422, "VALIDATION_ERROR");
        problem(send(PATH, access, HttpRequest.BodyPublishers.ofString("{}")), 400, "MALFORMED_REQUEST");
        verify(reports, never()).count(anyString());
    }

    @Test
    void rejectsInvalidDetailIdsAndReturnsNotFoundForMissingReports() throws Exception {
        String access = token(account(UserRole.ADMIN));
        String missing = "z".repeat(64);

        problem(detail("bad.id", access), 422, "VALIDATION_ERROR");
        problem(detail("a".repeat(65), access), 422, "VALIDATION_ERROR");
        problem(detail(missing, access), 404, "RESOURCE_NOT_FOUND");
        verify(reports, never()).findById("bad.id");
        verify(reports, never()).findById("a".repeat(65));
        verify(reports, never()).attachments(anyList());
    }

    @Test
    void detailRejectsQueryParametersAndRequestBodiesBeforeReadingTheReport() throws Exception {
        var reporter = account(UserRole.USER);
        String report = report(reporter, content(reporter), State.OPEN, PENDING_TIME);
        String access = token(account(UserRole.ADMIN));

        problem(detail(report + "?state=OPEN", access), 400, "MALFORMED_REQUEST");
        problem(send(PATH + "/" + report, access, HttpRequest.BodyPublishers.ofString("{}")), 400, "MALFORMED_REQUEST");
        verify(reports, never()).findById(anyString());
    }

    @Test
    void listAndDetailShareAccountAndSourceQuotasIndependentlyOfMyReports() throws Exception {
        var reporter = account(UserRole.USER);
        String report = report(reporter, content(reporter), State.OPEN, PENDING_TIME);
        String first = token(account(UserRole.ADMIN));
        String second = token(account(UserRole.MODERATOR));
        for (int i = 0; i < 3; i++) ok(get("", first));
        ok(detail(report, first));
        var accountLimit = detail(report, first);
        problem(accountLimit, 429, "RATE_LIMITED");
        assertTrue(Integer.parseInt(accountLimit.headers().firstValue("Retry-After").orElseThrow()) >= 1);
        ok(detail(report, second));
        ok(get("", second));

        var sourceLimit = get("", second);
        problem(sourceLimit, 429, "RATE_LIMITED");
        assertTrue(Integer.parseInt(sourceLimit.headers().firstValue("Retry-After").orElseThrow()) >= 1);
        ok(send("/api/v1/me/reports", first, HttpRequest.BodyPublishers.noBody()));
    }

    @Test
    void totalAndItemsShareTheSameSnapshotWhenAnOpenReportClosesMidRead() throws Exception {
        var reporter = account(UserRole.USER);
        String report = report(reporter, content(account(UserRole.USER)), State.OPEN, PENDING_TIME);
        long initialCount = count(State.OPEN);
        var changed = new AtomicBoolean(false);
        doAnswer(call -> {
            Long total = sqlSession.selectOne(AdminReportQueryMapper.class.getName() + ".count", Map.of("state", "OPEN"));
            if (changed.compareAndSet(false, true)) {
                CompletableFuture.runAsync(() -> jdbc.update("""
                        UPDATE user_report SET state='DISMISSED', version=2, resolved_at=UTC_TIMESTAMP(6) WHERE id=?
                        """, report)).get(8, TimeUnit.SECONDS);
            }
            return total;
        }).when(reports).count("OPEN");
        String access = token(account(UserRole.MODERATOR));

        var before = ok(get("", access));
        var after = ok(get("", access));

        assertEquals(initialCount, before.read("$.data.total", Long.class));
        assertTrue(before.read("$.data.items[*].id", List.class).contains(report));
        assertEquals(initialCount - 1, after.read("$.data.total", Long.class));
        assertFalse(after.read("$.data.items[*].id", List.class).contains(report));
    }

    @Test
    void mapsStorageFailureToTheDependencyErrorContract() throws Exception {
        doThrow(new DataAccessResourceFailureException("Test admin report query failure")).when(reports).count("OPEN");
        String report = id();
        doThrow(new DataAccessResourceFailureException("Test admin report detail failure")).when(reports).findById(report);
        String access = token(account(UserRole.ADMIN));

        problem(get("", access), 503, "DEPENDENCY_UNAVAILABLE");
        problem(detail(report, access), 503, "DEPENDENCY_UNAVAILABLE");
    }

    @Test
    void documentsReportReadPermissionAndTheAdminPageSchema() throws Exception {
        var doc = ok(send("/v3/api-docs", null, HttpRequest.BodyPublishers.noBody()));
        String operation = "$.paths['" + PATH + "'].get";
        assertEquals("listAdminReports", doc.read(operation + ".operationId", String.class));
        assertEquals(List.of("report:read"), doc.read(operation + "['x-permissions']", List.class));
        assertEquals(Set.of("page", "pageSize", "state"), Set.copyOf(doc.read(operation + ".parameters[*].name", List.class)));
        assertEquals(Set.of("items", "page", "pageSize", "total"), Set.copyOf(doc.read("$.components.schemas.AdminReportPage.required", List.class)));
        assertEquals("#/components/schemas/AdminReport",
                doc.read("$.components.schemas.AdminReportPage.properties.items.items['$ref']", String.class));
        assertEquals(3, doc.read("$.components.schemas.AdminReport.properties.attachmentAssetIds.maxItems", Integer.class));

        String detail = "$.paths['" + PATH + "/{reportId}'].get";
        assertEquals("getAdminReport", doc.read(detail + ".operationId", String.class));
        assertEquals(List.of("report:read"), doc.read(detail + "['x-permissions']", List.class));
        assertEquals(List.of("reportId"), doc.read(detail + ".parameters[*].name", List.class));
        assertEquals("path", doc.read(detail + ".parameters[0].in", String.class));
        assertEquals(Boolean.TRUE, doc.read(detail + ".parameters[0].required", Boolean.class));
        assertEquals(64, doc.read(detail + ".parameters[0].schema.maxLength", Integer.class));
        String responseRef = doc.read(detail + ".responses['200'].content['application/json'].schema['$ref']", String.class);
        String responseSchema = responseRef.substring(responseRef.lastIndexOf('/') + 1);
        assertEquals("#/components/schemas/AdminReport",
                doc.read("$.components.schemas['" + responseSchema + "'].properties.data['$ref']", String.class));
    }

    private UserAccount account(UserRole role) {
        var account = LoginTestSupport.account();
        account.setId(id());
        account.setEmail("admin-report-" + account.getId() + "@example.test");
        account.setEmailNormalized(account.getEmail());
        userIds.add(account.getId());
        users.insert(account);
        jdbc.update("INSERT INTO app_user_role(user_id, role_code, created_at) VALUES (?, ?, UTC_TIMESTAMP(6))",
                account.getId(), role.name());
        return account;
    }

    private String content(UserAccount author) {
        String content = id();
        jdbc.update("""
                INSERT INTO community_content(id, author_id, kind, lifecycle, publication_status, version, created_at, updated_at)
                VALUES (?, ?, 'ARTICLE', 'ACTIVE', 'TAKEN_DOWN', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, content, author.getId());
        return content;
    }

    private String report(UserAccount reporter, String target, State state, LocalDateTime createdAt) {
        return report(reporter, "CONTENT", target, state, createdAt);
    }

    private String report(UserAccount reporter, String targetType, String target, State state, LocalDateTime createdAt) {
        String report = id();
        boolean open = state == State.OPEN;
        jdbc.update("""
                INSERT INTO user_report(id, reporter_id, target_type, content_id, comment_id, reason_code, description,
                    state, version, created_at, resolved_at, resolution)
                VALUES (?, ?, ?, ?, ?, 'SPAM', '举报理由', ?, ?, ?, ?, ?)
                """, report, reporter.getId(), targetType, "CONTENT".equals(targetType) ? target : null,
                "COMMENT".equals(targetType) ? target : null, state.name(), open ? 1 : 2, createdAt,
                open ? null : createdAt.plusHours(1), open ? null : "已处理");
        return report;
    }

    private String asset(UserAccount reporter) {
        String asset = id();
        jdbc.update("""
                INSERT INTO media_asset(id, owner_id, scene, file_name, declared_content_type, declared_size_bytes,
                    storage_key, state, actual_content_type, actual_size_bytes, created_at)
                VALUES (?, ?, 'REPORT_ATTACHMENT', 'evidence.png', 'image/png', 100, ?, 'READY', 'image/png', 100, UTC_TIMESTAMP(6))
                """, asset, reporter.getId(), "test-admin-report/" + asset);
        return asset;
    }

    private Long count(State state) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_report WHERE state=?", Long.class, state.name());
    }

    private HttpResponse<String> get(String query, String token) throws Exception {
        return send(PATH + query, token, HttpRequest.BodyPublishers.noBody());
    }

    private HttpResponse<String> detail(String reportId, String token) throws Exception {
        return send(PATH + "/" + reportId, token, HttpRequest.BodyPublishers.noBody());
    }

    private HttpResponse<String> send(String path, String token, HttpRequest.BodyPublisher body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).method("GET", body);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static DocumentContext ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return JsonPath.parse(response.body());
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.parse(response.body()).read("$.code", String.class));
    }

    private String token(UserAccount account) {
        return sessions.create(account.getId(), account.getAuthVersion(), "admin-report-it").accessToken();
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
