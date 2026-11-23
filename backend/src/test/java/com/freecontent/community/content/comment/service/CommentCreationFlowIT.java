package com.freecontent.community.content.comment.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.content.comment.mapper.CommentMapper;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.notification.mapper.NotificationWriteMapper;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.processing.enabled=false",
        "app.media.upload-cleanup.enabled=false"})
class CommentCreationFlowIT {
    private static final String PREFIX = "test:comment-create:" + UUID.randomUUID() + ":";
    private static final int USER_LIMIT = 8;
    private static final int COMMENT_QUERY_USER_LIMIT = 8;
    private static final int COMMENT_QUERY_SOURCE_LIMIT = 20;
    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private SqlSession sqlSession;
    @MockitoSpyBean private NotificationWriteMapper notifications;
    @MockitoSpyBean private CommentMapper commentReads;
    private final List<String> accounts = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.comment.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.comment-query.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.comment-query.user-per-minute-limit", () -> COMMENT_QUERY_USER_LIMIT);
        registry.add("app.content.comment-query.ip-per-minute-limit", () -> COMMENT_QUERY_SOURCE_LIMIT);
        registry.add("app.content.comment.user-per-minute-limit", () -> USER_LIMIT);
        registry.add("app.content.comment.ip-per-minute-limit", () -> 30);
    }

    @AfterEach
    void cleanup() {
        var keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String account : accounts) jdbc.update("DELETE FROM comment_creation_request WHERE created_by=?", account);
        for (String account : accounts) {
            jdbc.update("""
                    DELETE FROM content_comment WHERE content_id IN (SELECT id FROM community_content WHERE author_id=?)
                    ORDER BY created_at DESC, id DESC
                    """, account);
        }
        for (String account : accounts) ContentTestSupport.clearWorkspace(jdbc, account);
        for (String account : accounts) {
            jdbc.update("DELETE FROM user_notification WHERE recipient_id=?", account);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", account);
            users.deleteById(account);
        }
    }

    @Test
    void createsAVisibleTopLevelCommentWithItsCanonicalLocation() throws Exception {
        var author = account();
        var reader = account();
        String content = publish(author);

        var response = comment(content, token(reader), id(), "{\"body\":\"  这篇文章说明了事务边界。  \"}");

        assertEquals(201, response.statusCode(), response.body());
        var data = JsonPath.parse(response.body());
        String commentId = data.read("$.data.id", String.class);
        assertEquals("/api/v1/comments/" + commentId, response.headers().firstValue("Location").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(content, data.read("$.data.contentId", String.class));
        assertEquals(reader.getId(), data.read("$.data.author.id", String.class));
        assertEquals("这篇文章说明了事务边界。", data.read("$.data.body", String.class));
        assertEquals("VISIBLE", data.read("$.data.state", String.class));
        assertNull(data.read("$.data.rootCommentId"));
        assertNull(data.read("$.data.replyToCommentId"));
        assertEquals(0, data.read("$.data.replyCount", Integer.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM content_comment WHERE id=? AND state='VISIBLE'", Long.class, commentId));
    }

    @Test
    void guestCanReadThePublicCommentFromTheCreatedLocation() throws Exception {
        var author = account();
        String contentId = publish(author);
        var created = comment(contentId, token(author), id(), "{\"body\":\"可公开读取的评论\"}");
        String commentId = createdId(created);
        String location = created.headers().firstValue("Location").orElseThrow();

        var response = get(location, null);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var json = JsonPath.parse(response.body());
        assertEquals(commentId, json.read("$.data.id", String.class));
        assertEquals(contentId, json.read("$.data.contentId", String.class));
        assertEquals(author.getId(), json.read("$.data.author.id", String.class));
        assertFalse(json.read("$.data.author", Map.class).containsKey("email"));
        assertEquals("可公开读取的评论", json.read("$.data.body", String.class));
        assertEquals("VISIBLE", json.read("$.data.state", String.class));
        assertEquals(0, json.read("$.data.replyCount", Integer.class));
    }

    @Test
    void rootCountsVisibleRepliesIncludingNestedOnesAndReplyKeepsItsDirectTarget() throws Exception {
        var author = account();
        String contentId = publish(author);
        String token = token(author);
        String root = createdId(comment(contentId, token, id(), "{\"body\":\"根评论\"}"));
        String originalReply = createdId(comment(contentId, token, id(), reply(root, "第一条回复")));
        String nested = createdId(comment(contentId, token, id(), reply(originalReply, "楼中楼")));
        String removed = createdId(comment(contentId, token, id(), reply(root, "隐藏回复")));
        jdbc.update("UPDATE content_comment SET state='HIDDEN' WHERE id=?", removed);

        assertEquals(2, readInt(publicCommentPath(root), "$.data.replyCount"));
        var response = get(publicCommentPath(nested), null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(root, jsonField(response, "$.data.rootCommentId"));
        assertEquals(originalReply, jsonField(response, "$.data.replyToCommentId"));
        assertEquals("楼中楼", jsonField(response, "$.data.body"));
        assertEquals(0, JsonPath.parse(response.body()).read("$.data.replyCount", Integer.class));
        jdbc.update("UPDATE content_comment SET state='DELETED' WHERE id=?", originalReply);
        problem(get(publicCommentPath(originalReply), null), 404, "RESOURCE_NOT_FOUND");
        assertEquals(1, readInt(publicCommentPath(root), "$.data.replyCount"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HIDDEN", "DELETED"})
    void rootPlaceholderMasksAuthorAndBodyAndDisappearsWhenTheLastReplyIsHidden(String state) throws Exception {
        var author = account();
        var replier = account();
        String contentId = publish(author);
        String token = token(author);
        String root = createdId(comment(contentId, token, id(), "{\"body\":\"待掩码的原始正文\"}"));
        String reply = createdId(comment(contentId, token(replier), id(), reply(root, "公开回复")));
        jdbc.update("UPDATE content_comment SET state=? WHERE id=?", state, root);

        var response = get(publicCommentPath(root), token);
        assertEquals(200, response.statusCode(), response.body());
        assertFalse(response.body().contains("待掩码的原始正文"));
        var json = JsonPath.parse(response.body());
        assertEquals(Set.of("id", "contentId", "rootCommentId", "replyToCommentId", "author", "body",
                "state", "replyCount", "createdAt"), json.read("$.data", Map.class).keySet());
        assertEquals(root, json.read("$.data.id", String.class));
        assertEquals(contentId, json.read("$.data.contentId", String.class));
        assertEquals("DELETED_PLACEHOLDER", json.read("$.data.state", String.class));
        assertNull(json.read("$.data.rootCommentId"));
        assertNull(json.read("$.data.replyToCommentId"));
        assertNull(json.read("$.data.author"));
        assertNull(json.read("$.data.body"));
        assertEquals(1, json.read("$.data.replyCount", Integer.class));

        jdbc.update("UPDATE content_comment SET state='HIDDEN' WHERE id=?", reply);
        problem(get(publicCommentPath(root), null), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void currentPublicRevisionGatesBothCommentsAndPlaceholders() throws Exception {
        var author = account();
        String contentId = publish(author);
        String token = token(author);
        String root = createdId(comment(contentId, token, id(), "{\"body\":\"根楼层\"}"));
        String reply = createdId(comment(contentId, token, id(), reply(root, "回复")));
        jdbc.update("UPDATE content_comment SET state='DELETED' WHERE id=?", root);
        jdbc.update("UPDATE community_content SET publication_status='TAKEN_DOWN' WHERE id=?", contentId);

        problem(get(publicCommentPath(root), null), 404, "RESOURCE_NOT_FOUND");
        problem(get(publicCommentPath(reply), token), 404, "RESOURCE_NOT_FOUND");
        jdbc.update("UPDATE community_content SET publication_status='PUBLISHED' WHERE id=?", contentId);
        jdbc.update("UPDATE content_revision SET status='REJECTED' WHERE content_id=?", contentId);
        problem(get(publicCommentPath(root), null), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void suppliedTokenMustAuthenticateAndWriteBlockedViewersCanStillRead() throws Exception {
        var author = account();
        var viewer = account();
        String contentId = publish(author);
        String commentId = createdId(comment(contentId, token(author), id(), "{\"body\":\"公开评论\"}"));
        String path = publicCommentPath(commentId);
        String viewerToken = token(viewer);
        var invalid = get(path, "not-a-token");
        problem(invalid, 401, "AUTHENTICATION_REQUIRED");
        assertTrue(invalid.headers().firstValue("WWW-Authenticate").orElseThrow().startsWith("Bearer"));
        problem(get(path, "A".repeat(43)), 401, "AUTHENTICATION_REQUIRED");
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", viewer.getId());
        assertEquals(200, get(path, viewerToken).statusCode());
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", viewer.getId());
        problem(get(path, viewerToken), 401, "AUTHENTICATION_REQUIRED");
        jdbc.update("UPDATE app_user SET account_status='ACTIVE', email_verified=FALSE WHERE id=?", viewer.getId());
        problem(get(path, viewerToken), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void malformedReadInputsDoNotConsumeQuotaAndMissingCommentsReturn404() throws Exception {
        String missing = publicCommentPath(id());
        String sourceKey = commentQueryKey("source", "127.0.0.1");
        problem(get(publicCommentPath("bad.id"), null), 422, "VALIDATION_ERROR");
        problem(get(publicCommentPath("x".repeat(65)), null), 422, "VALIDATION_ERROR");
        problem(get(missing + "?limit=1", null), 400, "MALFORMED_REQUEST");
        var withBody = getRequest(missing, null).method("GET", HttpRequest.BodyPublishers.ofString("{}")).build();
        problem(http.send(withBody, HttpResponse.BodyHandlers.ofString()), 400, "MALFORMED_REQUEST");
        assertNull(redis.opsForValue().get(sourceKey));

        problem(get(missing, null), 404, "RESOURCE_NOT_FOUND");
        assertEquals("1", redis.opsForValue().get(sourceKey));
    }

    @Test
    void readBudgetsAreScopedBySourceAndViewerAndSeparateFromWrites() throws Exception {
        var author = account();
        var viewer = account();
        String contentId = publish(author);
        String token = token(author);
        String commentId = createdId(comment(contentId, token, id(), "{\"body\":\"公开评论\"}"));
        String path = publicCommentPath(commentId);
        redis.opsForValue().set(commentQueryKey("user", author.getId()), Integer.toString(COMMENT_QUERY_USER_LIMIT),
                Duration.ofMinutes(1));

        var userLimited = get(path, token);
        problem(userLimited, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(userLimited.headers().firstValue("Retry-After").orElseThrow()) > 0);
        assertEquals(200, get(path, null).statusCode());
        redis.opsForValue().set(commentQueryKey("source", "127.0.0.1"), Integer.toString(COMMENT_QUERY_SOURCE_LIMIT),
                Duration.ofMinutes(1));
        problem(get(path, null), 429, "RATE_LIMITED");
        problem(get(path, token(viewer)), 429, "RATE_LIMITED");
        createdId(comment(contentId, token, id(), "{\"body\":\"查询配额独立\"}"));
    }

    @Test
    void commentReadDatabaseFailureReturns503WithoutLeakingDetails() throws Exception {
        String id = id();
        doThrow(new TransientDataAccessResourceException("private SQL detail")).when(commentReads).findPublic(id);

        var response = get(publicCommentPath(id), null);

        problem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("private SQL detail"));
    }

    @Test
    void repliesStayInTheOriginalThreadAndNotifyOnlyTheActualOtherAuthor() throws Exception {
        var first = account();
        var second = account();
        var third = account();
        String content = publish(first);
        String root = createdId(comment(content, token(first), id(), "{\"body\":\"首条评论\"}"));
        String secondToken = token(second);
        String key = id();
        String text = "<b>不执行 HTML</b>";
        var firstReply = comment(content, secondToken, key, reply(root, text));
        String replyId = createdId(firstReply);
        String nextReply = createdId(comment(content, token(third), id(), reply(replyId, "回复这条回复")));
        String selfReply = createdId(comment(content, secondToken, id(), reply(replyId, "补充自己的评论")));

        assertEquals(root, jsonField(firstReply, "$.data.rootCommentId"));
        assertEquals(root, jsonField(firstReply, "$.data.replyToCommentId"));
        assertEquals(text, jsonField(firstReply, "$.data.body"));
        assertEquals(root, jdbc.queryForObject("SELECT root_comment_id FROM content_comment WHERE id=?", String.class, nextReply));
        assertEquals(replyId, jdbc.queryForObject("SELECT reply_to_comment_id FROM content_comment WHERE id=?", String.class, nextReply));
        assertEquals(root, jdbc.queryForObject("SELECT root_comment_id FROM content_comment WHERE id=?", String.class, selfReply));
        var replay = comment(content, secondToken, key, reply(root, "  " + text + "  "));
        assertEquals(replyId, createdId(replay));
        assertEquals(firstReply.headers().firstValue("Location"), replay.headers().firstValue("Location"));
        assertEquals(JsonPath.<Map<String, Object>>read(firstReply.body(), "$.data"),
                JsonPath.<Map<String, Object>>read(replay.body(), "$.data"));
        assertEquals(List.of(replyId), replyNotifications(first.getId()));
        assertEquals(List.of(nextReply), replyNotifications(second.getId()));
        assertEquals(List.of(), replyNotifications(third.getId()));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE recipient_id=? AND body LIKE ?",
                Long.class, first.getId(), "%" + text + "%"));
        assertEquals(4L, countComments(content));
    }

    @Test
    void sameKeyScopesByActorAndBindsThePathAndNormalizedBody() throws Exception {
        var author = account();
        var another = account();
        String first = publish(author);
        String second = publish(author);
        String token = token(author);
        String key = id();
        String original = createdId(comment(first, token, key, "{\"body\":\"原正文\"}"));

        problem(comment(first, token, key, "{\"body\":\"不同正文\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
        problem(comment(second, token, key, "{\"body\":\"原正文\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
        assertNotEquals(original, createdId(comment(first, token(another), key, "{\"body\":\"原正文\"}")));
    }

    @Test
    void expiredKeyCanBeUsedAgainAndReplayRetainsTheOriginalSnapshot() throws Exception {
        var author = account();
        String content = publish(author);
        String token = token(author);
        String key = id();
        var first = comment(content, token, key, "{\"body\":\"原正文\"}");
        String original = createdId(first);
        jdbc.update("UPDATE app_user SET display_name='已更新昵称' WHERE id=?", author.getId());
        var replay = comment(content, token, key, "{\"body\":\"原正文\"}");
        assertEquals(original, createdId(replay));
        assertEquals(jsonField(first, "$.data.author.displayName"), jsonField(replay, "$.data.author.displayName"));

        jdbc.update("UPDATE comment_creation_request SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE created_by=? AND key_hash=?",
                author.getId(), TokenDigest.sha256(key));
        var createdAgain = comment(content, token, key, "{\"body\":\"新评论\"}");
        assertNotEquals(original, createdId(createdAgain));
        assertEquals("已更新昵称", jsonField(createdAgain, "$.data.author.displayName"));
    }

    @Test
    void forbidsHiddenOrForeignReplyTargetsButKeepsVisibleRepliesOfADeletedRoot() throws Exception {
        var author = account();
        var other = account();
        String content = publish(author);
        String foreign = publish(other);
        String a = token(author);
        String b = token(other);
        String root = createdId(comment(content, a, id(), "{\"body\":\"根楼层\"}"));
        String visible = createdId(comment(content, b, id(), reply(root, "可见回复")));
        jdbc.update("UPDATE content_comment SET state='DELETED' WHERE id=?", root);

        problem(comment(content, a, id(), reply(root, "回复被删除楼层")), 404, "RESOURCE_NOT_FOUND");
        problem(comment(foreign, a, id(), reply(visible, "不能跨作品回复")), 404, "RESOURCE_NOT_FOUND");
        problem(comment(content, a, id(), reply(id(), "目标不存在")), 404, "RESOURCE_NOT_FOUND");
        var nested = comment(content, a, id(), reply(visible, "仍可回复这条可见评论"));
        createdId(nested);
        assertEquals(root, jsonField(nested, "$.data.rootCommentId"));
        assertEquals(visible, jsonField(nested, "$.data.replyToCommentId"));
        jdbc.update("UPDATE content_comment SET state='HIDDEN' WHERE id=?", visible);
        problem(comment(content, a, id(), reply(visible, "回复隐藏评论")), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void rechecksContentAndCreatedCommentVisibilityEvenWhenReplaying() throws Exception {
        var author = account();
        String content = publish(author);
        String token = token(author);
        String key = id();
        String input = "{\"body\":\"评论正文\"}";
        String created = createdId(comment(content, token, key, input));
        jdbc.update("UPDATE community_content SET publication_status='WITHDRAWN' WHERE id=?", content);
        problem(comment(content, token, key, input), 404, "RESOURCE_NOT_FOUND");
        problem(comment(content, token, id(), input), 404, "RESOURCE_NOT_FOUND");
        jdbc.update("UPDATE community_content SET publication_status='PUBLISHED' WHERE id=?", content);
        jdbc.update("UPDATE content_comment SET state='HIDDEN' WHERE id=?", created);
        problem(comment(content, token, key, input), 404, "RESOURCE_NOT_FOUND");
        assertEquals(1L, countComments(content));
    }

    @Test
    void invalidRequestsAndRestrictedAccountsCannotCreateComments() throws Exception {
        var author = account();
        String content = publish(author);
        String token = token(author);
        String key = id();
        String valid = "{\"body\":\"评论\"}";
        problem(comment(content, null, key, valid), 401, "AUTHENTICATION_REQUIRED");
        problem(comment(content, token, null, valid), 400, "MALFORMED_REQUEST");
        problem(comment(content, token, "short", valid), 422, "VALIDATION_ERROR");
        problem(comment("bad.id", token, key, valid), 422, "VALIDATION_ERROR");
        problem(comment(content, token, key, "{\"body\":\"　 \u00a0\"}"), 422, "VALIDATION_ERROR");
        problem(comment(content, token, key, "{\"body\":\"x\",\"replyToCommentId\":null}"), 400, "MALFORMED_REQUEST");
        problem(comment(content, token, key, "{\"body\":42}"), 400, "MALFORMED_REQUEST");
        problem(comment(content, token, key, "{\"body\":\"x\",\"extra\":true}"), 400, "MALFORMED_REQUEST");
        problem(comment(content, token, key, reply("bad.id", "x")), 422, "VALIDATION_ERROR");
        var duplicate = commentRequest(content, token, key, "application/json", HttpRequest.BodyPublishers.ofString(valid))
                .header("Idempotency-Key", id()).build();
        problem(http.send(duplicate, HttpResponse.BodyHandlers.ofString()), 400, "MALFORMED_REQUEST");
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", author.getId());
        problem(comment(content, token, key, valid), 403, "ACCOUNT_RESTRICTED");
        assertEquals(0L, countComments(content));
    }

    @Test
    void distinguishesCodePointLimitFromTransportSizeAndMediaType() throws Exception {
        var author = account();
        String content = publish(author);
        String token = token(author);
        String unicode = "😀".repeat(2000);
        var max = comment(content, token, id(), "{\"body\":\"" + unicode + "\"}");
        createdId(max);
        assertEquals(unicode, jsonField(max, "$.data.body"));
        problem(comment(content, token, id(), "{\"body\":\"" + unicode + "x\"}"), 422, "VALIDATION_ERROR");
        problem(comment(content, token, id(), "{\"body\":\"" + "x".repeat(33000) + "\"}"), 413, "PAYLOAD_TOO_LARGE");
        var chunked = HttpRequest.BodyPublishers.ofInputStream(
                () -> new ByteArrayInputStream((" ".repeat(33000) + "{\"body\":\"x\"}").getBytes(StandardCharsets.UTF_8)));
        var chunkedRequest = commentRequest(content, token, id(), "application/json", chunked).build();
        problem(http.send(chunkedRequest, HttpResponse.BodyHandlers.ofString()), 413, "PAYLOAD_TOO_LARGE");
        var plain = commentRequest(content, token, id(), "text/plain", HttpRequest.BodyPublishers.ofString("x")).build();
        problem(http.send(plain, HttpResponse.BodyHandlers.ofString()), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertEquals(1L, countComments(content));
    }

    @Test
    void newRequestsAreRateLimitedWhileCompletedReplaysReuseTheirResult() throws Exception {
        var author = account();
        String content = publish(author);
        String token = token(author);
        String key = id();
        String input = "{\"body\":\"原创评论\"}";
        String created = createdId(comment(content, token, key, input));
        String quotaKey = PREFIX + "{content-comment}:user:" + TokenDigest.sha256(author.getId());
        redis.opsForValue().set(quotaKey, Integer.toString(USER_LIMIT), Duration.ofMinutes(1));

        assertEquals(created, createdId(comment(content, token, key, input)));
        String freshKey = id();
        var limited = comment(content, token, freshKey, input);
        problem(limited, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()) > 0);
        assertEquals(0L, countReservations(author.getId(), freshKey));
        assertEquals(1L, countComments(content));
    }

    @Test
    void notificationFailureRollsBackTheReplyAndReservationAllowsRetry() throws Exception {
        var author = account();
        var reader = account();
        String content = publish(author);
        String root = createdId(comment(content, token(author), id(), "{\"body\":\"根楼层\"}"));
        String key = id();
        String token = token(reader);
        var failOnce = new AtomicBoolean(true);
        doAnswer(call -> {
            int inserted = writeReplyNotification(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
            if (failOnce.getAndSet(false)) throw new TransientDataAccessResourceException("private SQL failure");
            return inserted;
        }).when(notifications).insertCommentReply(anyString(), anyString(), anyString(), any());

        var failed = comment(content, token, key, reply(root, "失败后重试"));
        problem(failed, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(failed.body().contains("private SQL failure"));
        assertEquals(1L, countComments(content));
        assertEquals(List.of(), replyNotifications(author.getId()));
        assertEquals(0L, countReservations(reader.getId(), key));
        String recovered = createdId(comment(content, token, key, reply(root, "失败后重试")));
        assertEquals(List.of(recovered), replyNotifications(author.getId()));
        assertEquals(2L, countComments(content));
    }

    @Test
    void concurrentReplayReceivesInProgressWhileCommentAndNoticeAreUncommitted() throws Exception {
        var author = account();
        var reader = account();
        String content = publish(author);
        String root = createdId(comment(content, token(author), id(), "{\"body\":\"根楼层\"}"));
        String key = id();
        String token = token(reader);
        String body = reply(root, "并发重试保持同一评论");
        var noticeWritten = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        doAnswer(call -> {
            int inserted = writeReplyNotification(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
            noticeWritten.countDown();
            assertTrue(finish.await(12, TimeUnit.SECONDS));
            return inserted;
        }).when(notifications).insertCommentReply(anyString(), anyString(), anyString(), any());

        var request = commentRequest(content, token, key, "application/json", HttpRequest.BodyPublishers.ofString(body)).build();
        var original = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(noticeWritten.await(7, TimeUnit.SECONDS));
            var duplicate = comment(content, token, key, body);
            problem(duplicate, 409, "REQUEST_IN_PROGRESS");
            assertTrue(Long.parseLong(duplicate.headers().firstValue("Retry-After").orElseThrow()) > 0);
            assertEquals(1L, countComments(content));
            assertEquals(List.of(), replyNotifications(author.getId()));
        } finally {
            finish.countDown();
        }
        String created = createdId(original.get(12, TimeUnit.SECONDS));
        assertEquals(created, createdId(comment(content, token, key, body)));
        assertEquals(List.of(created), replyNotifications(author.getId()));
        assertEquals(2L, countComments(content));
    }

    @Test
    void publicDetailsAndAuthorCardsCountOnlyVisibleCommentsAndReplies() throws Exception {
        var author = account();
        var reader = account();
        String content = publish(author);
        String root = createdId(comment(content, token(author), id(), "{\"body\":\"根评论\"}"));
        createdId(comment(content, token(reader), id(), reply(root, "可见回复")));

        String details = "/api/v1/contents/" + content;
        String cards = "/api/v1/users/" + author.getId() + "/contents";
        assertEquals(2, readInt(details, "$.data.commentCount"));
        assertEquals(2, readInt(cards, "$.data.items[0].commentCount"));
        jdbc.update("UPDATE content_comment SET state='HIDDEN' WHERE id=?", root);
        assertEquals(1, readInt(details, "$.data.commentCount"));
        assertEquals(1, readInt(cards, "$.data.items[0].commentCount"));
    }

    @Test
    void documentsTheCanonicalCreatedResponseAndPublicAuthorSchema() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                .timeout(Duration.ofSeconds(30)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        var specification = JsonPath.parse(response.body());
        String success = "$.paths['/api/v1/contents/{contentId}/comments'].post.responses['201']";
        assertEquals("/api/v1/comments/{commentId}", specification.read(success + ".headers.Location.description", String.class));
        String envelope = specification.read(success + ".content['application/json'].schema['$ref']", String.class);
        String envelopeName = envelope.substring(envelope.lastIndexOf('/') + 1);
        assertEquals("#/components/schemas/Comment",
                specification.read("$.components.schemas['" + envelopeName + "'].properties.data['$ref']", String.class));
        List<Map<String, Object>> author = specification.read("$.components.schemas.Comment.properties.author.anyOf");
        assertTrue(author.contains(Map.of("$ref", "#/components/schemas/PublicUser")), author::toString);
        assertTrue(author.contains(Map.of("type", "null")), author::toString);
        assertEquals(false, specification.read("$.components.schemas.CreateCommentRequest.additionalProperties", Boolean.class));
        assertEquals(List.of(Map.of(), Map.of("bearerAuth", List.of())),
                specification.read("$.paths['/api/v1/comments/{commentId}'].get.security", List.class));
    }

    private int readInt(String path, String field) throws Exception {
        var response = get(path, null);
        assertEquals(200, response.statusCode(), response.body());
        return JsonPath.parse(response.body()).read(field, Integer.class);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(getRequest(path, token).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder getRequest(String path, String token) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request;
    }

    private static String publicCommentPath(String id) { return "/api/v1/comments/" + id; }

    private static String commentQueryKey(String scope, String value) {
        return PREFIX + "{content-comment-query}:" + scope + ":" + TokenDigest.sha256(value);
    }

    private int writeReplyNotification(String id, String recipient, String replyId, LocalDateTime now) {
        return sqlSession.insert(NotificationWriteMapper.class.getName() + ".insertCommentReply",
                Map.of("id", id, "recipient", recipient, "replyId", replyId, "now", now));
    }

    private long countComments(String content) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM content_comment WHERE content_id=?", Long.class, content);
    }

    private long countReservations(String actor, String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM comment_creation_request WHERE created_by=? AND key_hash=?",
                Long.class, actor, TokenDigest.sha256(key));
    }

    private List<String> replyNotifications(String recipient) {
        return jdbc.queryForList("""
                SELECT source_event_id FROM user_notification WHERE recipient_id=? AND type='COMMENT_REPLY'
                  AND target_type='COMMENT' AND target_id=source_event_id AND read_at IS NULL
                """, String.class, recipient);
    }

    private static String createdId(HttpResponse<String> response) {
        assertEquals(201, response.statusCode(), response.body());
        return jsonField(response, "$.data.id");
    }

    private static String reply(String replyTo, String body) {
        return "{\"body\":\"" + body + "\",\"replyToCommentId\":\"" + replyTo + "\"}";
    }

    private static String jsonField(HttpResponse<String> response, String field) {
        return JsonPath.parse(response.body()).read(field, String.class);
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, jsonField(response, "$.code"));
    }

    private UserAccount account() {
        var user = LoginTestSupport.account();
        user.setId(id());
        user.setEmail("comment-" + user.getId() + "@example.test");
        user.setEmailNormalized(user.getEmail());
        accounts.add(user.getId());
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
                VALUES (?, ?, 1, 'APPROVED', '评论测试', '公开正文', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, revision, content);
        jdbc.update("""
                UPDATE community_content SET published_revision_id=?, publication_status='PUBLISHED',
                        first_published_at=UTC_TIMESTAMP(6), published_at=UTC_TIMESTAMP(6) WHERE id=?
                """, revision, content);
        return content;
    }

    private String token(UserAccount user) {
        return sessions.create(user.getId(), user.getAuthVersion(), "comment-creation-it").accessToken();
    }

    private HttpResponse<String> comment(String content, String token, String key, String body) throws Exception {
        var request = commentRequest(content, token, key, "application/json", HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder commentRequest(String content, String token, String key, String mediaType, HttpRequest.BodyPublisher body) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/contents/" + content + "/comments"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", mediaType).POST(body);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        return request;
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
