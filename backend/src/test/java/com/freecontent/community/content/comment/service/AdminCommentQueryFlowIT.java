package com.freecontent.community.content.comment.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.content.comment.mapper.AdminCommentQueryMapper;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.freecontent.community.user.model.UserRole;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.math.BigInteger;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ibatis.session.SqlSession;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.processing.enabled=false",
        "app.media.upload-cleanup.enabled=false"})
class AdminCommentQueryFlowIT {
    private static final String PATH = "/api/v1/admin/comments";
    private static final String PREFIX = "test:comment-admin-query:" + UUID.randomUUID() + ":";
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 3, 8, 0);
    private static final int USER_LIMIT = 8;
    private static final int SOURCE_LIMIT = 16;

    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private SqlSession sqlSession;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @MockitoSpyBean private AdminCommentQueryMapper comments;
    private final List<String> userIds = new ArrayList<>();
    private final List<String> commentIds = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.comment-admin-query.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.comment-admin-query.user-per-minute-limit", () -> USER_LIMIT);
        registry.add("app.content.comment-admin-query.ip-per-minute-limit", () -> SOURCE_LIMIT);
        registry.add("app.content.comment-query.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.comment.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        var keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (int i = commentIds.size() - 1; i >= 0; i--) {
            jdbc.update("DELETE FROM content_comment WHERE id=?", commentIds.get(i));
        }
        for (String userId : userIds) {
            ContentTestSupport.clearWorkspace(jdbc, userId);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", userId);
            users.deleteById(userId);
        }
    }

    @Test
    void moderatorCanReadHiddenCommentWithItsOriginalBodyAndVersion() throws Exception {
        var moderator = account(UserRole.MODERATOR);
        var author = account(UserRole.USER);
        String content = content(author);
        String id = insert(id(), content, author, null, "HIDDEN", "运营可见的原评论", 4, BASE);

        var response = get("?contentId=" + content, token(moderator));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var json = JsonPath.parse(response.body());
        assertEquals(1, json.read("$.data.page", Integer.class));
        assertEquals(20, json.read("$.data.pageSize", Integer.class));
        assertEquals(1, json.read("$.data.total", Integer.class));
        Map<?, ?> comment = json.read("$.data.items[0]", Map.class);
        assertEquals(Set.of("id", "contentId", "authorId", "body", "state", "version", "createdAt"), comment.keySet());
        assertEquals(id, comment.get("id"));
        assertEquals(content, comment.get("contentId"));
        assertEquals(author.getId(), comment.get("authorId"));
        assertEquals("运营可见的原评论", comment.get("body"));
        assertEquals("HIDDEN", comment.get("state"));
        assertEquals(4, comment.get("version"));
        assertEquals("2026-11-03T08:00:00Z", comment.get("createdAt"));
    }

    @Test
    void moderatorCanReadDeletedReplyByIdEvenWhenContentIsNotPublic() throws Exception {
        var moderator = account(UserRole.MODERATOR);
        var author = account(UserRole.USER);
        String content = content(author);
        String root = insert(id(), content, author, null, "VISIBLE", "根评论", 1, BASE);
        String reply = insert(id(), content, author, root, "DELETED", "被删除的回复原文", 6, BASE.plusSeconds(1));

        var response = detail(reply, token(moderator));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var json = JsonPath.parse(response.body());
        Map<?, ?> data = json.read("$.data", Map.class);
        assertEquals(Set.of("id", "contentId", "authorId", "body", "state", "version", "createdAt"), data.keySet());
        assertEquals(reply, data.get("id"));
        assertEquals(content, data.get("contentId"));
        assertEquals(author.getId(), data.get("authorId"));
        assertEquals("被删除的回复原文", data.get("body"));
        assertEquals("DELETED", data.get("state"));
        assertEquals(6, data.get("version"));
        assertEquals("2026-11-03T08:00:01Z", data.get("createdAt"));
        verify(comments).findById(reply);
        verify(comments, never()).count(any(), any(), any());
        verify(comments, never()).page(any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void bothDetailAndListReflectTheLatestStateAndVersionAfterOwnerDeletes() throws Exception {
        var admin = account(UserRole.ADMIN);
        var owner = account(UserRole.USER);
        String content = content(owner);
        String commentId = insert(id(), content, owner, null, "HIDDEN", "内部原文", 2, BASE);
        String adminToken = token(admin);
        var first = detail(commentId, adminToken);
        assertEquals(200, first.statusCode(), first.body());
        assertEquals("HIDDEN", JsonPath.parse(first.body()).read("$.data.state", String.class));

        var deleted = send("DELETE", "/api/v1/comments/" + commentId, token(owner), HttpRequest.BodyPublishers.noBody());
        assertEquals(204, deleted.statusCode(), deleted.body());

        var latest = detail(commentId, adminToken);
        assertEquals(200, latest.statusCode(), latest.body());
        var json = JsonPath.parse(latest.body());
        assertEquals(3, json.read("$.data.version", Integer.class));
        assertEquals("DELETED", json.read("$.data.state", String.class));
        assertEquals("内部原文", json.read("$.data.body", String.class));
        Map<?, ?> listItem = page("?contentId=" + content, adminToken).read("$.data.items[0]", Map.class);
        assertEquals(listItem, json.read("$.data", Map.class));
    }

    @Test
    void detailOnlyAllowsAuthorizedOperatorsAndRejectsOtherMethods() throws Exception {
        var author = account(UserRole.USER);
        var moderator = account(UserRole.MODERATOR);
        String content = content(author);
        String commentId = insert(id(), content, author, null, "HIDDEN", "运营原文", 1, BASE);
        String token = token(moderator);
        problem(detail(commentId, null), 401, "AUTHENTICATION_REQUIRED");
        problem(detail(commentId, token(author)), 403, "PERMISSION_DENIED");
        problem(send("DELETE", PATH + "/" + commentId, token, HttpRequest.BodyPublishers.noBody()), 403, "PERMISSION_DENIED");
        problem(send("POST", PATH + "/" + commentId + "/hides/extra", token, HttpRequest.BodyPublishers.noBody()),
                403, "PERMISSION_DENIED");
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", moderator.getId());
        assertEquals(200, detail(commentId, token).statusCode());
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", moderator.getId());
        problem(detail(commentId, token), 403, "ACCOUNT_RESTRICTED");
    }

    @Test
    void detailRejectsMalformedInputsBeforeQuotaAndReturns404ForMissingId() throws Exception {
        String token = token(account(UserRole.ADMIN));
        String missing = id();
        problem(detail("bad.id", token), 422, "VALIDATION_ERROR");
        problem(detail("x".repeat(65), token), 422, "VALIDATION_ERROR");
        problem(detail(missing + "?state=VISIBLE", token), 400, "MALFORMED_REQUEST");
        problem(send("GET", PATH + "/" + missing, token, HttpRequest.BodyPublishers.ofString("{}")), 400, "MALFORMED_REQUEST");
        assertNull(redis.opsForValue().get(quotaKey("source", "127.0.0.1")));
        verify(comments, never()).findById(any());

        problem(detail(missing, token), 404, "RESOURCE_NOT_FOUND");
        assertEquals("1", redis.opsForValue().get(quotaKey("source", "127.0.0.1")));
    }

    @Test
    void successfulDetailReadConsumesTheQuotaSharedWithTheList() throws Exception {
        var admin = account(UserRole.ADMIN);
        String content = content(admin);
        String commentId = insert(id(), content, admin, null, "DELETED", "原文", 2, BASE);
        String token = token(admin);
        redis.opsForValue().set(quotaKey("user", admin.getId()), Integer.toString(USER_LIMIT - 1), Duration.ofMinutes(1));

        assertEquals(200, detail(commentId, token).statusCode());
        var limited = get("?contentId=" + content, token);
        problem(limited, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()) > 0);
    }

    @Test
    void detailDatabaseFailureReturns503WithoutRawSql() throws Exception {
        String commentId = id();
        doThrow(new TransientDataAccessResourceException("private SQL detail")).when(comments).findById(commentId);

        var response = detail(commentId, token(account(UserRole.ADMIN)));

        problem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("private SQL detail"));
    }

    @Test
    void allExactFiltersCombineAndDeletedRepliesRemainReviewable() throws Exception {
        var admin = account(UserRole.ADMIN);
        var author = account(UserRole.USER);
        var another = account(UserRole.USER);
        String content = content(author);
        String foreign = content(another);
        String root = insert(id(), content, author, null, "VISIBLE", "根楼层", 1, BASE);
        String deletedReply = insert(id(), content, author, root, "DELETED", "原文 <b>纯文本</b>", 3, BASE.plusSeconds(1));
        insert(id(), content, another, root, "DELETED", "其他作者", 2, BASE.plusSeconds(2));
        insert(id(), foreign, author, null, "DELETED", "其他作品", 2, BASE.plusSeconds(3));
        insert(id(), content, author, root, "HIDDEN", "其他状态", 2, BASE.plusSeconds(4));

        String token = token(admin);
        var result = page("?contentId=" + content + "&authorId=" + author.getId() + "&state=DELETED", token);
        assertEquals(List.of(deletedReply), ids(result));
        assertEquals(1, result.read("$.data.total", Integer.class));
        assertEquals("原文 <b>纯文本</b>", result.read("$.data.items[0].body", String.class));
        assertEquals(3, result.read("$.data.items[0].version", Integer.class));
        assertEquals(4, page("?contentId=" + content, token).read("$.data.total", Integer.class));
    }

    @Test
    void pagesSortByCreationTimeAndIdRatherThanMutationTime() throws Exception {
        var moderator = account(UserRole.MODERATOR);
        var author = account(UserRole.USER);
        String content = content(author);
        String oldest = insert(id(), content, author, null, "VISIBLE", "旧评论", 2, BASE.minusSeconds(1));
        String low = insert("a_" + id(), content, author, null, "VISIBLE", "同时间较小ID", 1, BASE);
        String high = insert("z_" + id(), content, author, null, "HIDDEN", "同时间较大ID", 2, BASE);
        String newest = insert(id(), content, author, null, "DELETED", "最新评论", 2, BASE.plusSeconds(1));
        jdbc.update("UPDATE content_comment SET updated_at=UTC_TIMESTAMP(6) WHERE id=?", oldest);
        String query = "?contentId=" + content + "&pageSize=2";
        String token = token(moderator);

        var first = page(query, token);
        var second = page(query + "&page=2", token);
        assertEquals(List.of(newest, high), ids(first));
        assertEquals(List.of(low, oldest), ids(second));
        assertEquals(4, first.read("$.data.total", Integer.class));
        assertEquals(4, second.read("$.data.total", Integer.class));
        assertEquals(2, second.read("$.data.page", Integer.class));
    }

    @Test
    void hugePageNumbersReturnAnEmptyPageWithoutOverflowingSqlOffset() throws Exception {
        var admin = account(UserRole.ADMIN);
        String content = content(admin);
        insert(id(), content, admin, null, "VISIBLE", "原评论", 1, BASE);
        String pageNumber = "99999999999999999999999999999999";
        var result = page("?contentId=" + content + "&pageSize=100&page=" + pageNumber, token(admin));

        assertEquals(List.of(), ids(result));
        assertEquals(1, result.read("$.data.total", Integer.class));
        assertEquals(100, result.read("$.data.pageSize", Integer.class));
        assertEquals(new BigInteger(pageNumber), result.read("$.data.page", BigInteger.class));
        verify(comments, never()).page(any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void authorizationOnlyAllowsOperatorsOnThisExactMethodAndPath() throws Exception {
        var member = account(UserRole.USER);
        var moderator = account(UserRole.MODERATOR);
        var admin = account(UserRole.ADMIN);
        String token = token(admin);
        problem(get("", null), 401, "AUTHENTICATION_REQUIRED");
        problem(get("", token(member)), 403, "PERMISSION_DENIED");
        problem(send("GET", PATH + "/" + id() + "/history", token, HttpRequest.BodyPublishers.noBody()),
                403, "PERMISSION_DENIED");
        problem(send("POST", PATH, token, HttpRequest.BodyPublishers.noBody()), 403, "PERMISSION_DENIED");
        assertEquals(200, get("?contentId=" + id(), token).statusCode());
        String moderatorToken = token(moderator);
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", moderator.getId());
        assertEquals(200, get("?contentId=" + id(), moderatorToken).statusCode());
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", moderator.getId());
        problem(get("", moderatorToken), 403, "ACCOUNT_RESTRICTED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void revokedRoleAfterAuthenticationCannotExposeRawCommentData(boolean byId) throws Exception {
        var moderator = account(UserRole.MODERATOR);
        var author = account(UserRole.USER);
        String content = content(author);
        String commentId = insert(id(), content, author, null, "HIDDEN", "需授权读取的原文", 1, BASE);
        String token = token(moderator);
        doAnswer(call -> {
            var beforeChange = call.callRealMethod();
            jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='MODERATOR'", moderator.getId());
            return beforeChange;
        }).when(authentication).authenticate(token);

        var response = byId ? detail(commentId, token) : get("?contentId=" + content, token);
        problem(response, 403, "PERMISSION_DENIED");
        verify(comments, never()).count(any(), any(), any());
        verify(comments, never()).findById(any());
    }

    @Test
    void invalidIdsAndBoundsReturn422AndMalformedInputReturns400BeforeQuota() throws Exception {
        String token = token(account(UserRole.ADMIN));
        problem(get("?page=0", token), 422, "VALIDATION_ERROR");
        problem(get("?pageSize=101", token), 422, "VALIDATION_ERROR");
        problem(get("?page=1.5", token), 400, "MALFORMED_REQUEST");
        problem(get("?state=visible", token), 400, "MALFORMED_REQUEST");
        problem(get("?contentId=bad.id", token), 422, "VALIDATION_ERROR");
        problem(get("?authorId=" + "x".repeat(65), token), 422, "VALIDATION_ERROR");
        problem(get("?page=1&page=2", token), 400, "MALFORMED_REQUEST");
        problem(get("?sort=unknown", token), 400, "MALFORMED_REQUEST");
        problem(send("GET", PATH, token, HttpRequest.BodyPublishers.ofString("{}")), 400, "MALFORMED_REQUEST");
        assertNull(redis.opsForValue().get(quotaKey("source", "127.0.0.1")));
        verify(comments, never()).count(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "source"})
    void eachAdminReadBudgetReturns429IndependentlyOfPublicReads(String scope) throws Exception {
        var actor = account(UserRole.ADMIN);
        boolean user = scope.equals("user");
        String key = quotaKey(scope, user ? actor.getId() : "127.0.0.1");
        redis.opsForValue().set(key, Integer.toString(user ? USER_LIMIT : SOURCE_LIMIT), Duration.ofMinutes(1));

        var response = get("", token(actor));

        problem(response, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
        problem(detail(id(), token(actor)), 429, "RATE_LIMITED");
        problem(send("GET", "/api/v1/comments/" + id(), null, HttpRequest.BodyPublishers.noBody()),
                404, "RESOURCE_NOT_FOUND");
        verify(comments, never()).count(any(), any(), any());
    }

    @Test
    void totalAndItemsUseTheSameSnapshotWhenACommentChangesBetweenQueries() throws Exception {
        var moderator = account(UserRole.MODERATOR);
        var author = account(UserRole.USER);
        String content = content(author);
        String older = insert(id(), content, author, null, "VISIBLE", "较早", 1, BASE);
        String newer = insert(id(), content, author, null, "VISIBLE", "较新", 1, BASE.plusSeconds(1));
        var changed = new AtomicBoolean(false);
        doAnswer(call -> {
            Long total = sqlSession.selectOne(AdminCommentQueryMapper.class.getName() + ".count",
                    Map.of("content", content, "author", author.getId(), "state", "VISIBLE"));
            if (changed.compareAndSet(false, true)) {
                CompletableFuture.runAsync(() -> jdbc.update("UPDATE content_comment SET state='HIDDEN' WHERE id=?", newer))
                        .get(8, TimeUnit.SECONDS);
            }
            return total;
        }).when(comments).count(content, author.getId(), "VISIBLE");
        String query = "?contentId=" + content + "&authorId=" + author.getId() + "&state=VISIBLE";
        String token = token(moderator);

        var before = page(query, token);
        assertEquals(2, before.read("$.data.total", Integer.class));
        assertEquals(List.of(newer, older), ids(before));
        var after = page(query, token);
        assertEquals(1, after.read("$.data.total", Integer.class));
        assertEquals(List.of(older), ids(after));
    }

    @Test
    void readDependencyFailureReturns503WithoutRawSqlOrBody() throws Exception {
        var admin = account(UserRole.ADMIN);
        String content = content(admin);
        insert(id(), content, admin, null, "DELETED", "本次响应不应含评论原文", 2, BASE);
        doThrow(new TransientDataAccessResourceException("private SQL detail"))
                .when(comments).page(content, null, null, 0L, 20);

        var response = get("?contentId=" + content, token(admin));

        problem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("private SQL detail"));
        assertFalse(response.body().contains("本次响应不应含评论原文"));
    }

    @Test
    void openApiDeclaresProtectedDetailAndListWithTheSharedAdminCommentSchema() throws Exception {
        var response = send("GET", "/v3/api-docs", null, HttpRequest.BodyPublishers.noBody());
        assertEquals(200, response.statusCode(), response.body());
        var doc = JsonPath.parse(response.body());
        String operation = "$.paths['/api/v1/admin/comments'].get";
        assertEquals("listAdminComments", doc.read(operation + ".operationId", String.class));
        assertEquals(List.of(Map.of("bearerAuth", List.of())), doc.read(operation + ".security", List.class));
        assertEquals("#/components/schemas/AdminComment", doc.read(
                "$.components.schemas.AdminCommentPage.properties.items.items['$ref']", String.class));
        assertEquals(Set.of("id", "contentId", "authorId", "body", "state", "version", "createdAt"),
                Set.copyOf(doc.<List<String>>read("$.components.schemas.AdminComment.required")));
        assertEquals(List.of("VISIBLE", "HIDDEN", "DELETED"),
                doc.read("$.components.schemas.AdminComment.properties.state.enum", List.class));
        String detail = "$.paths['/api/v1/admin/comments/{commentId}'].get";
        assertEquals("getAdminComment", doc.read(detail + ".operationId", String.class));
        assertEquals(List.of(Map.of("bearerAuth", List.of())), doc.read(detail + ".security", List.class));
        String envelope = doc.read(detail + ".responses['200'].content['application/json'].schema['$ref']", String.class);
        String envelopeName = envelope.substring(envelope.lastIndexOf('/') + 1);
        assertEquals("#/components/schemas/AdminComment",
                doc.read("$.components.schemas['" + envelopeName + "'].properties.data['$ref']", String.class));
    }

    private DocumentContext page(String query, String token) throws Exception {
        var response = get(query, token);
        assertEquals(200, response.statusCode(), response.body());
        return JsonPath.parse(response.body());
    }

    private static List<String> ids(DocumentContext page) {
        return page.read("$.data.items[*].id");
    }

    private static String quotaKey(String scope, String value) {
        return PREFIX + "{content-comment-admin-query}:" + scope + ":" + TokenDigest.sha256(value);
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.parse(response.body()).read("$.code", String.class));
    }

    private UserAccount account(UserRole role) {
        var user = LoginTestSupport.account();
        user.setId(id());
        user.setEmail("comment-admin-" + user.getId() + "@example.test");
        user.setEmailNormalized(user.getEmail());
        userIds.add(user.getId());
        users.insert(user);
        if (role == UserRole.USER) {
            roles.insertDefaultRole(user.getId(), user.getCreatedAt());
        } else {
            jdbc.update("INSERT INTO app_user_role(user_id, role_code, created_at) VALUES (?, ?, UTC_TIMESTAMP(6))",
                    user.getId(), role.name());
        }
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

    private String insert(String id, String content, UserAccount author, String root, String state, String body,
                          int version, LocalDateTime createdAt) {
        jdbc.update("""
                INSERT INTO content_comment(id, content_id, author_id, root_comment_id, reply_to_comment_id,
                                            body, state, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, content, author.getId(), root, root, body, state, version, createdAt, createdAt);
        commentIds.add(id);
        return id;
    }

    private HttpResponse<String> get(String query, String token) throws Exception {
        return send("GET", PATH + query, token, HttpRequest.BodyPublishers.noBody());
    }

    private HttpResponse<String> detail(String commentId, String token) throws Exception {
        return send("GET", PATH + "/" + commentId, token, HttpRequest.BodyPublishers.noBody());
    }

    private HttpResponse<String> send(String method, String path, String token, HttpRequest.BodyPublisher body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).method(method, body);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(UserAccount user) {
        return sessions.create(user.getId(), user.getAuthVersion(), "admin-comment-query-it").accessToken();
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
