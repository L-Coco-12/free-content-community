package com.freecontent.community.report.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.report.mapper.ReportMapper;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.processing.enabled=false",
        "app.media.upload-cleanup.enabled=false"})
class MyReportsFlowIT {
    private static final String PATH = "/api/v1/me/reports";
    private static final String PREFIX = "test:my-reports:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private SqlSessionTemplate sqlSession;
    @MockitoSpyBean private ReportMapper reports;
    private final List<String> userIds = new ArrayList<>();
    private final List<String> commentIds = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.report.query.redis-key-prefix", () -> PREFIX);
        registry.add("app.report.query.user-per-minute-limit", () -> 3);
        registry.add("app.report.query.ip-per-minute-limit", () -> 5);
    }

    @AfterEach
    void cleanup() {
        var keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String userId : userIds) jdbc.update("DELETE FROM user_report WHERE reporter_id=?", userId);
        for (String commentId : commentIds) jdbc.update("DELETE FROM content_comment WHERE id=?", commentId);
        for (String userId : userIds) {
            ContentTestSupport.clearWorkspace(jdbc, userId);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", userId);
            users.deleteById(userId);
        }
    }

    @Test
    void listsOnlyTheCallersReportsWithDefaultPagination() throws Exception {
        var owner = account();
        var other = account();
        String content = content(other);
        String ownReport = openReport(owner, content);
        openReport(other, content);

        var response = list("", token(owner));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var json = JsonPath.parse(response.body());
        assertEquals(List.of(ownReport), json.read("$.data.items[*].id", List.class));
        assertEquals(1, json.read("$.data.page", Integer.class));
        assertEquals(20, json.read("$.data.pageSize", Integer.class));
        assertEquals(1, json.read("$.data.total", Integer.class));
        assertEquals(Set.of("id", "targetType", "targetId", "reasonCode", "description", "state", "version",
                "createdAt", "resolvedAt", "resolution"), json.read("$.data.items[0]", Map.class).keySet());
        assertNull(json.read("$.data.items[0].resolvedAt"));
        assertNull(json.read("$.data.items[0].resolution"));
    }

    @Test
    void readsTheCurrentResultOfAnOwnedReportAfterItsContentWasTakenDown() throws Exception {
        var reporter = account();
        String target = content(account());
        String report = openReport(reporter, target);
        jdbc.update("UPDATE community_content SET publication_status='TAKEN_DOWN' WHERE id=?", target);
        jdbc.update("""
                UPDATE user_report SET state='RESOLVED', version=2, resolved_at='2026-11-04 09:15:30',
                    resolution='已下架违规作品' WHERE id=?
                """, report);

        var response = detail(report, token(reporter));

        var json = ok(response);
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(Set.of("id", "targetType", "targetId", "reasonCode", "description", "state", "version",
                "createdAt", "resolvedAt", "resolution"), json.read("$.data", Map.class).keySet());
        assertEquals(report, json.read("$.data.id", String.class));
        assertEquals("CONTENT", json.read("$.data.targetType", String.class));
        assertEquals(target, json.read("$.data.targetId", String.class));
        assertEquals("RESOLVED", json.read("$.data.state", String.class));
        assertEquals(2, json.read("$.data.version", Integer.class));
        assertEquals("2026-11-04T09:15:30Z", json.read("$.data.resolvedAt", String.class));
        assertEquals("已下架违规作品", json.read("$.data.resolution", String.class));
    }

    @Test
    void concealsReportsFromEveryoneExceptTheirReporter() throws Exception {
        var reporter = account();
        var targetAuthor = account();
        String report = openReport(reporter, content(targetAuthor));
        String authorAccess = token(targetAuthor);

        var foreign = detail(report, authorAccess);
        var missing = detail(id(), authorAccess);

        problem(foreign, 404, "RESOURCE_NOT_FOUND");
        problem(missing, 404, "RESOURCE_NOT_FOUND");
        assertEquals(JsonPath.parse(missing.body()).read("$.detail", String.class),
                JsonPath.parse(foreign.body()).read("$.detail", String.class));
    }

    @Test
    void allowsWriteBlockedReportersToReadAndRejectsUnauthenticatedReads() throws Exception {
        var reporter = account();
        String report = openReport(reporter, content(account()));
        String access = token(reporter);
        problem(detail(report, null), 401, "AUTHENTICATION_REQUIRED");

        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", reporter.getId());
        assertEquals(report, ok(detail(report, access)).read("$.data.id", String.class));

        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", reporter.getId());
        problem(detail(report, access), 403, "ACCOUNT_RESTRICTED");
    }

    @Test
    void rejectsOutOfContractReportIds() throws Exception {
        String access = token(account());
        problem(detail("bad.id", access), 422, "VALIDATION_ERROR");
        problem(detail("a".repeat(65), access), 422, "VALIDATION_ERROR");
        problem(detail("a".repeat(64), access), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void rejectsQueryParametersAndRequestBodiesOnTheDetailRoute() throws Exception {
        var reporter = account();
        String report = openReport(reporter, content(account()));
        String access = token(reporter);
        problem(detail(report + "?reporterId=another-user", access), 400, "MALFORMED_REQUEST");

        var withBody = request(PATH + "/" + report, access).header("Content-Type", "application/json")
                .method("GET", HttpRequest.BodyPublishers.ofString("{}")).build();
        problem(http.send(withBody, HttpResponse.BodyHandlers.ofString()), 400, "MALFORMED_REQUEST");
    }

    @Test
    void detailAndListUseTheSameReadQuota() throws Exception {
        var reporter = account();
        String report = openReport(reporter, content(account()));
        String access = token(reporter);
        ok(list("", access));
        ok(detail(report, access));
        ok(detail(report, access));

        var limited = detail(report, access);

        problem(limited, 429, "RATE_LIMITED");
        assertTrue(Integer.parseInt(limited.headers().firstValue("Retry-After").orElseThrow()) >= 1);
    }

    @Test
    void mapsDetailStorageFailureToTheDependencyError() throws Exception {
        var reporter = account();
        String report = openReport(reporter, content(account()));
        doThrow(new DataAccessResourceFailureException("Test report detail failure"))
                .when(reports).findOwnedRecord(report, reporter.getId());

        problem(detail(report, token(reporter)), 503, "DEPENDENCY_UNAVAILABLE");
    }

    @Test
    void ordersByCreatedTimeThenIdAndReturnsAnEmptyPageBeyondTheEnd() throws Exception {
        var owner = account();
        String access = token(owner);
        String first = openReport(owner, content(owner));
        String second = openReport(owner, content(owner));
        String older = openReport(owner, content(owner));
        jdbc.update("UPDATE user_report SET created_at='2026-11-04 08:00:00' WHERE id IN (?, ?)", first, second);
        jdbc.update("UPDATE user_report SET created_at='2026-11-03 08:00:00' WHERE id=?", older);
        var expected = new ArrayList<>(List.of(first, second));
        expected.sort(Comparator.reverseOrder());

        var pageOne = ok(list("?page=1&pageSize=2", access));
        var pageTwo = ok(list("?page=2&pageSize=2", access));
        String huge = "9999999999999999999999999";
        var pastEnd = ok(list("?page=" + huge + "&pageSize=100", access));

        assertEquals(expected, pageOne.read("$.data.items[*].id", List.class));
        assertEquals(List.of(older), pageTwo.read("$.data.items[*].id", List.class));
        assertEquals(2, pageTwo.read("$.data.page", Integer.class));
        assertEquals(2, pageTwo.read("$.data.pageSize", Integer.class));
        assertEquals(3, pageTwo.read("$.data.total", Integer.class));
        assertEquals(List.of(), pastEnd.read("$.data.items", List.class));
        assertEquals(huge, String.valueOf(pastEnd.read("$.data.page", Object.class)));
        assertEquals(100, pastEnd.read("$.data.pageSize", Integer.class));
        assertEquals(3, pastEnd.read("$.data.total", Integer.class));
    }

    @Test
    void includesTheCurrentResolutionOfAReportAgainstAHiddenComment() throws Exception {
        var reporter = account();
        var author = account();
        String comment = id();
        jdbc.update("""
                INSERT INTO content_comment(id, content_id, author_id, body, state, created_at, updated_at)
                VALUES (?, ?, ?, '已隐藏的评论', 'HIDDEN', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, comment, content(author), author.getId());
        commentIds.add(comment);
        jdbc.update("""
                INSERT INTO user_report(id, reporter_id, target_type, comment_id, reason_code, description, state,
                                        version, created_at, resolved_at, resolution)
                VALUES (?, ?, 'COMMENT', ?, 'ABUSE', '补充说明', 'RESOLVED', 2,
                        '2026-11-03 08:00:00', '2026-11-04 09:15:30', '已隐藏评论')
                """, id(), reporter.getId(), comment);

        var page = ok(list("", token(reporter)));

        assertEquals("COMMENT", page.read("$.data.items[0].targetType", String.class));
        assertEquals(comment, page.read("$.data.items[0].targetId", String.class));
        assertEquals("RESOLVED", page.read("$.data.items[0].state", String.class));
        assertEquals(2, page.read("$.data.items[0].version", Integer.class));
        assertEquals("2026-11-03T08:00:00Z", page.read("$.data.items[0].createdAt", String.class));
        assertEquals("2026-11-04T09:15:30Z", page.read("$.data.items[0].resolvedAt", String.class));
        assertEquals("已隐藏评论", page.read("$.data.items[0].resolution", String.class));
    }

    @Test
    void returnsTheSameSnapshotForTotalAndItemsDuringConcurrentSubmission() throws Exception {
        var owner = account();
        String existing = openReport(owner, content(owner));
        String newTarget = content(owner);
        var counted = new CountDownLatch(1);
        var inserted = new CountDownLatch(1);
        doAnswer(invocation -> {
            Long total = sqlSession.selectOne(ReportMapper.class.getName() + ".countOwned", Map.of("reporter", owner.getId()));
            counted.countDown();
            if (!inserted.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for a new report");
            return total;
        }).when(reports).countOwned(owner.getId());
        var inFlight = http.sendAsync(request(PATH, token(owner)).GET().build(), HttpResponse.BodyHandlers.ofString());

        try {
            assertTrue(counted.await(8, TimeUnit.SECONDS));
            openReport(owner, newTarget);
        } finally {
            inserted.countDown();
        }
        var page = ok(inFlight.get(15, TimeUnit.SECONDS));
        assertEquals(1, page.read("$.data.total", Integer.class));
        assertEquals(List.of(existing), page.read("$.data.items[*].id", List.class));
    }

    @Test
    void permitsReadsForWriteBlockedAccountsAndEnforcesSessionAndAccountRestrictions() throws Exception {
        var owner = account();
        String access = token(owner);
        problem(list("", null), 401, "AUTHENTICATION_REQUIRED");

        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", owner.getId());
        var empty = ok(list("", access));
        assertEquals(List.of(), empty.read("$.data.items", List.class));
        assertEquals(0, empty.read("$.data.total", Integer.class));

        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", owner.getId());
        problem(list("", access), 403, "ACCOUNT_RESTRICTED");
        jdbc.update("UPDATE app_user SET account_status='ACTIVE', email_verified=FALSE WHERE id=?", owner.getId());
        problem(list("", access), 403, "PERMISSION_DENIED");
        jdbc.update("UPDATE app_user SET email_verified=TRUE, auth_version=auth_version+1 WHERE id=?", owner.getId());
        problem(list("", access), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void rejectsUndeclaredAndRepeatedQueryParametersAndRequestBodies() throws Exception {
        String access = token(account());
        problem(list("?reporterId=another-user", access), 400, "MALFORMED_REQUEST");
        problem(list("?page=1&page=2", access), 400, "MALFORMED_REQUEST");
        var withBody = request(PATH, access).header("Content-Type", "application/json")
                .method("GET", HttpRequest.BodyPublishers.ofString("{}")).build();
        problem(http.send(withBody, HttpResponse.BodyHandlers.ofString()), 400, "MALFORMED_REQUEST");
    }

    @Test
    void distinguishesMalformedPagingFromOutOfRangeValues() throws Exception {
        String access = token(account());
        for (String query : List.of("?page=", "?page=1.5", "?pageSize=1e2")) {
            problem(list(query, access), 400, "MALFORMED_REQUEST");
        }
        for (String query : List.of("?page=0", "?page=-1", "?pageSize=0", "?pageSize=101")) {
            problem(list(query, access), 422, "VALIDATION_ERROR");
        }
    }

    @Test
    void appliesIndependentAccountAndSourceRateLimits() throws Exception {
        String first = token(account());
        String second = token(account());
        for (int i = 0; i < 3; i++) ok(list("", first));
        var accountLimit = list("", first);
        problem(accountLimit, 429, "RATE_LIMITED");
        assertTrue(Integer.parseInt(accountLimit.headers().firstValue("Retry-After").orElseThrow()) >= 1);

        ok(list("", second));
        ok(list("", second));
        var sourceLimit = list("", second);
        problem(sourceLimit, 429, "RATE_LIMITED");
        assertTrue(Integer.parseInt(sourceLimit.headers().firstValue("Retry-After").orElseThrow()) >= 1);
    }

    @Test
    void reportsStorageFailureUsingTheDependencyErrorContract() throws Exception {
        var owner = account();
        doThrow(new DataAccessResourceFailureException("Test report count failure")).when(reports).countOwned(owner.getId());

        problem(list("", token(owner)), 503, "DEPENDENCY_UNAVAILABLE");
    }

    @Test
    void documentsTheOffsetPageAndPrivateReportRecord() throws Exception {
        var document = ok(http.send(request("/v3/api-docs", null).GET().build(), HttpResponse.BodyHandlers.ofString()));
        String operation = "$.paths['" + PATH + "'].get";
        assertEquals("listMyReports", document.read(operation + ".operationId", String.class));
        assertEquals(Set.of("page", "pageSize"), Set.copyOf(document.read(operation + ".parameters[*].name", List.class)));
        assertEquals(Set.of("items", "page", "pageSize", "total"),
                Set.copyOf(document.read("$.components.schemas.ReportPage.required", List.class)));
        assertEquals("#/components/schemas/ReportRecord",
                document.read("$.components.schemas.ReportPage.properties.items.items['$ref']", String.class));

        String detail = "$.paths['" + PATH + "/{reportId}'].get";
        assertEquals("getMyReport", document.read(detail + ".operationId", String.class));
        assertEquals(List.of("reportId"), document.read(detail + ".parameters[*].name", List.class));
        assertEquals("path", document.read(detail + ".parameters[0].in", String.class));
        assertEquals(Boolean.TRUE, document.read(detail + ".parameters[0].required", Boolean.class));
        assertEquals(64, document.read(detail + ".parameters[0].schema.maxLength", Integer.class));
        String responseRef = document.read(detail + ".responses['200'].content['application/json'].schema['$ref']", String.class);
        String responseSchema = responseRef.substring(responseRef.lastIndexOf('/') + 1);
        assertEquals("#/components/schemas/ReportRecord",
                document.read("$.components.schemas['" + responseSchema + "'].properties.data['$ref']", String.class));
    }

    private UserAccount account() {
        var account = LoginTestSupport.account();
        account.setId(id());
        account.setEmail("my-reports-" + account.getId() + "@example.test");
        account.setEmailNormalized(account.getEmail());
        userIds.add(account.getId());
        users.insert(account);
        roles.insertDefaultRole(account.getId(), account.getCreatedAt());
        return account;
    }

    private String content(UserAccount author) {
        String content = id();
        jdbc.update("""
                INSERT INTO community_content(id, author_id, kind, lifecycle, publication_status, version, created_at, updated_at)
                VALUES (?, ?, 'ARTICLE', 'ACTIVE', 'UNPUBLISHED', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, content, author.getId());
        return content;
    }

    private String openReport(UserAccount reporter, String content) {
        String report = id();
        jdbc.update("""
                INSERT INTO user_report(id, reporter_id, target_type, content_id, reason_code, description, created_at)
                VALUES (?, ?, 'CONTENT', ?, 'SPAM', '举报说明', UTC_TIMESTAMP(6))
                """, report, reporter.getId(), content);
        return report;
    }

    private HttpResponse<String> list(String query, String token) throws Exception {
        return http.send(request(PATH + query, token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> detail(String report, String token) throws Exception {
        return http.send(request(PATH + "/" + report, token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path, String token) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request;
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
        return sessions.create(account.getId(), account.getAuthVersion(), "my-reports-it").accessToken();
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
