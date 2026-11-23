package com.freecontent.community.content.comment.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.content.comment.mapper.CommentMapper;
import com.freecontent.community.content.support.ContentTestSupport;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.processing.enabled=false",
        "app.media.upload-cleanup.enabled=false"})
class CommentDeletionFlowIT {
    private static final String PREFIX = "test:comment-delete:" + UUID.randomUUID() + ":";
    private static final String COMMENTS = "/api/v1/comments/";
    private static final int USER_LIMIT = 8;

    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private SqlSession sqlSession;
    @MockitoSpyBean private CommentMapper comments;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    private final List<String> accountIds = new ArrayList<>();
    private final List<String> commentIds = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.comment.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.comment.user-per-minute-limit", () -> USER_LIMIT);
        registry.add("app.content.comment.ip-per-minute-limit", () -> 20);
        registry.add("app.content.comment-query.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.public-query.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        var keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String userId : accountIds) jdbc.update("DELETE FROM comment_creation_request WHERE created_by=?", userId);
        for (int i = commentIds.size() - 1; i >= 0; i--) {
            jdbc.update("DELETE FROM content_comment WHERE id=?", commentIds.get(i));
        }
        for (String userId : accountIds) ContentTestSupport.clearWorkspace(jdbc, userId);
        for (String userId : accountIds) {
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", userId);
            users.deleteById(userId);
        }
    }

    @Test
    void ownerCanDeleteTheirCommentAndRepeatWithoutChangingItsVersionAgain() throws Exception {
        var author = account();
        String contentId = publish(author);
        String commentId = insert(contentId, author, null, null, "VISIBLE", "保留的原评论");
        String token = token(author);

        var response = delete(commentId, token);

        assertEquals(204, response.statusCode(), response.body());
        assertEquals("", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var changed = jdbc.queryForMap("SELECT state, version, body, updated_at FROM content_comment WHERE id=?", commentId);
        assertEquals("DELETED", changed.get("state"));
        assertEquals(2, changed.get("version"));
        assertEquals("保留的原评论", changed.get("body"));
        assertEquals(204, delete(commentId, token).statusCode());
        assertEquals(changed, jdbc.queryForMap("SELECT state, version, body, updated_at FROM content_comment WHERE id=?", commentId));
    }

    @Test
    void deletingRootKeepsRepliesVisibleAndMasksItsBodyAndAuthorInPublicReads() throws Exception {
        var author = account();
        var replier = account();
        String contentId = publish(author);
        String root = insert(contentId, author, null, null, "VISIBLE", "根评论原文");
        String reply = insert(contentId, replier, root, root, "VISIBLE", "其他人的回复");

        assertEquals(204, delete(root, token(author)).statusCode());

        var rootView = read(COMMENTS + root);
        assertEquals("DELETED_PLACEHOLDER", rootView.read("$.data.state", String.class));
        assertNull(rootView.read("$.data.body"));
        assertNull(rootView.read("$.data.author"));
        assertEquals(1, rootView.read("$.data.replyCount", Integer.class));
        var page = read("/api/v1/contents/" + contentId + "/comments");
        assertEquals(List.of(root), page.read("$.data.items[*].id"));
        assertNull(page.read("$.data.items[0].body"));
        assertNull(page.read("$.data.items[0].author"));
        assertEquals(List.of(reply), read(COMMENTS + root + "/replies").read("$.data.items[*].id"));
        assertEquals("其他人的回复", read(COMMENTS + reply).read("$.data.body", String.class));
        assertEquals(1, read("/api/v1/contents/" + contentId).read("$.data.commentCount", Integer.class));
        assertEquals(1, version(reply));
    }

    @Test
    void deletingAReplyKeepsItsNestedRepliesAndReducesTheVisibleCount() throws Exception {
        var author = account();
        var replier = account();
        String contentId = publish(author);
        String root = insert(contentId, author, null, null, "VISIBLE", "根评论");
        String reply = insert(contentId, replier, root, root, "VISIBLE", "将删除的回复");
        String nested = insert(contentId, author, root, reply, "VISIBLE", "可见楼中楼");

        assertEquals(204, delete(reply, token(replier)).statusCode());

        problem(send("GET", COMMENTS + reply, null, HttpRequest.BodyPublishers.noBody()), 404, "RESOURCE_NOT_FOUND");
        var page = read(COMMENTS + root + "/replies");
        assertEquals(List.of(nested), page.read("$.data.items[*].id"));
        assertEquals(reply, page.read("$.data.items[0].replyToCommentId", String.class));
        assertEquals(1, read(COMMENTS + root).read("$.data.replyCount", Integer.class));
        assertEquals(2, read("/api/v1/contents/" + contentId).read("$.data.commentCount", Integer.class));
    }

    @Test
    void ownerCanDeleteAHiddenCommentEvenAfterItsContentIsTakenDown() throws Exception {
        var author = account();
        String contentId = publish(author);
        String commentId = insert(contentId, author, null, null, "HIDDEN", "隐藏记录");
        jdbc.update("UPDATE content_comment SET version=7 WHERE id=?", commentId);
        jdbc.update("UPDATE community_content SET publication_status='TAKEN_DOWN' WHERE id=?", contentId);

        assertEquals(204, delete(commentId, token(author)).statusCode());

        assertEquals(8, version(commentId));
        assertEquals("DELETED", state(commentId));
        assertEquals("隐藏记录", jdbc.queryForObject("SELECT body FROM content_comment WHERE id=?", String.class, commentId));
        problem(send("GET", COMMENTS + commentId, null, HttpRequest.BodyPublishers.noBody()), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void missingAndForeignCommentsHaveTheSameErrorEvenForAdministrators() throws Exception {
        var author = account();
        var admin = account();
        jdbc.update("INSERT INTO app_user_role(user_id, role_code, created_at) VALUES (?, 'ADMIN', UTC_TIMESTAMP(6))",
                admin.getId());
        String contentId = publish(author);
        String commentId = insert(contentId, author, null, null, "VISIBLE", "本人评论");
        String token = token(admin);

        problem(delete(id(), token), 404, "RESOURCE_NOT_FOUND");
        problem(delete(commentId, token), 404, "RESOURCE_NOT_FOUND");
        assertEquals("VISIBLE", state(commentId));
        assertEquals(204, delete(commentId, token(author)).statusCode());
        problem(delete(commentId, token), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void invalidInputAndRestrictedActorsCannotMutateAComment() throws Exception {
        var author = account();
        String contentId = publish(author);
        String commentId = insert(contentId, author, null, null, "VISIBLE", "尚未删除");
        String token = token(author);
        problem(delete(commentId, null), 401, "AUTHENTICATION_REQUIRED");
        problem(delete("bad.id", token), 422, "VALIDATION_ERROR");
        problem(delete(commentId + "?flag=true", token), 400, "MALFORMED_REQUEST");
        problem(send("DELETE", COMMENTS + commentId, token, HttpRequest.BodyPublishers.ofString("{}")),
                400, "MALFORMED_REQUEST");
        assertNull(redis.opsForValue().get(writeUserKey(author.getId())));

        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", author.getId());
        problem(delete(commentId, token), 403, "ACCOUNT_RESTRICTED");
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", author.getId());
        problem(delete(commentId, token), 403, "ACCOUNT_RESTRICTED");
        jdbc.update("UPDATE app_user SET account_status='ACTIVE', email_verified=FALSE WHERE id=?", author.getId());
        problem(delete(commentId, token), 403, "PERMISSION_DENIED");
        assertEquals("VISIBLE", state(commentId));
        assertEquals(1, version(commentId));
    }

    @Test
    void deletionRechecksAuthenticationVersionInsideItsWriteTransaction() throws Exception {
        var author = account();
        String contentId = publish(author);
        String commentId = insert(contentId, author, null, null, "VISIBLE", "账号验证后发生变化");
        String token = token(author);
        doAnswer(call -> {
            var beforeChange = call.callRealMethod();
            jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", author.getId());
            return beforeChange;
        }).when(authentication).authenticate(token);

        problem(delete(commentId, token), 401, "AUTHENTICATION_REQUIRED");
        assertEquals("VISIBLE", state(commentId));
        assertEquals(1, version(commentId));
    }

    @Test
    void concurrentDeletionsUpdateTheStateAndVersionOnce() throws Exception {
        var author = account();
        String content = publish(author);
        String commentId = insert(content, author, null, null, "VISIBLE", "并发删除");
        String token = token(author);
        var calls = IntStream.range(0, 5).mapToObj(index -> http.sendAsync(
                request("DELETE", COMMENTS + commentId, token, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString())).toList();

        for (var call : calls) {
            var response = call.get(15, TimeUnit.SECONDS);
            assertEquals(204, response.statusCode(), response.body());
        }
        assertEquals("DELETED", state(commentId));
        assertEquals(2, version(commentId));
    }

    @Test
    void deleteSharesWriteQuotaWithCreationWhilePublicReadingContinues() throws Exception {
        var author = account();
        String content = publish(author);
        String commentId = insert(content, author, null, null, "VISIBLE", "公开评论");
        String token = token(author);
        var key = writeUserKey(author.getId());
        redis.opsForValue().set(key, Integer.toString(USER_LIMIT), Duration.ofMinutes(1));

        var limited = delete(commentId, token);
        problem(limited, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()) > 0);
        problem(postComment(content, token, id()), 429, "RATE_LIMITED");
        assertEquals("公开评论", read(COMMENTS + commentId).read("$.data.body", String.class));
        assertEquals(1, version(commentId));
    }

    @Test
    void dependencyFailureRollsBackTheMutationAndAllowsRetry() throws Exception {
        var author = account();
        String content = publish(author);
        String commentId = insert(content, author, null, null, "VISIBLE", "等待重试");
        String token = token(author);
        var first = new AtomicBoolean(true);
        doAnswer(call -> {
            int updated = sqlSession.update(CommentMapper.class.getName() + ".markOwnedDeleted",
                    Map.of("id", commentId, "author", author.getId(), "now", call.getArgument(2, LocalDateTime.class)));
            if (first.getAndSet(false)) throw new TransientDataAccessResourceException("private SQL detail");
            return updated;
        }).when(comments).markOwnedDeleted(eq(commentId), eq(author.getId()), any());

        var failed = delete(commentId, token);
        problem(failed, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(failed.body().contains("private SQL detail"));
        assertEquals("VISIBLE", state(commentId));
        assertEquals(1, version(commentId));
        assertEquals(204, delete(commentId, token).statusCode());
        assertEquals("DELETED", state(commentId));
        assertEquals(2, version(commentId));
    }

    @Test
    void deletedCommentCannotBeReadFromItsCreationSnapshot() throws Exception {
        var author = account();
        String content = publish(author);
        String token = token(author);
        String key = id();
        var created = postComment(content, token, key);
        assertEquals(201, created.statusCode(), created.body());
        String commentId = JsonPath.parse(created.body()).read("$.data.id", String.class);

        assertEquals(204, delete(commentId, token).statusCode());
        problem(postComment(content, token, key), 404, "RESOURCE_NOT_FOUND");
        problem(send("GET", COMMENTS + commentId, null, HttpRequest.BodyPublishers.noBody()), 404, "RESOURCE_NOT_FOUND");
        assertEquals(0, read("/api/v1/contents/" + content).read("$.data.commentCount", Integer.class));
    }

    private String state(String commentId) {
        return jdbc.queryForObject("SELECT state FROM content_comment WHERE id=?", String.class, commentId);
    }

    private int version(String commentId) {
        return jdbc.queryForObject("SELECT version FROM content_comment WHERE id=?", Integer.class, commentId);
    }

    private DocumentContext read(String path) throws Exception {
        var response = send("GET", path, null, HttpRequest.BodyPublishers.noBody());
        assertEquals(200, response.statusCode(), response.body());
        return JsonPath.parse(response.body());
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.parse(response.body()).read("$.code", String.class));
    }

    private static String writeUserKey(String authorId) {
        return PREFIX + "{content-comment}:user:" + TokenDigest.sha256(authorId);
    }

    private UserAccount account() {
        var user = LoginTestSupport.account();
        user.setId(id());
        user.setEmail("comment-delete-" + user.getId() + "@example.test");
        user.setEmailNormalized(user.getEmail());
        accountIds.add(user.getId());
        users.insert(user);
        roles.insertDefaultRole(user.getId(), user.getCreatedAt());
        return user;
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
                VALUES (?, ?, 1, 'APPROVED', '评论删除测试', '公开正文', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, revision, content);
        jdbc.update("""
                UPDATE community_content SET published_revision_id=?, publication_status='PUBLISHED',
                    first_published_at=UTC_TIMESTAMP(6), published_at=UTC_TIMESTAMP(6) WHERE id=?
                """, revision, content);
        return content;
    }

    private String insert(String content, UserAccount author, String root, String target, String state, String body) {
        String id = id();
        jdbc.update("""
                INSERT INTO content_comment(id, content_id, author_id, root_comment_id, reply_to_comment_id,
                                            body, state, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, id, content, author.getId(), root, target, body, state);
        commentIds.add(id);
        return id;
    }

    private HttpResponse<String> delete(String commentId, String token) throws Exception {
        return send("DELETE", COMMENTS + commentId, token, HttpRequest.BodyPublishers.noBody());
    }

    private HttpResponse<String> postComment(String contentId, String token, String key) throws Exception {
        var request = request("POST", "/api/v1/contents/" + contentId + "/comments", token,
                HttpRequest.BodyPublishers.ofString("{\"body\":\"评论原文\"}"))
                .header("Content-Type", "application/json").header("Idempotency-Key", key).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 201) {
            commentIds.add(JsonPath.parse(response.body()).read("$.data.id", String.class));
        }
        return response;
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
        return sessions.create(user.getId(), user.getAuthVersion(), "comment-deletion-it").accessToken();
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
