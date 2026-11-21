package com.freecontent.community.user.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.notification.mapper.NotificationWriteMapper;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserFollowMapper;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.processing.enabled=false",
        "app.media.upload-cleanup.enabled=false"})
class UserFollowFlowIT {
    private static final String PREFIX = "test:user-follow:" + UUID.randomUUID() + ":";
    private static final String PATH = "/api/v1/me/following/";
    private static final int USER_LIMIT = 8;
    private static final int SOURCE_LIMIT = 12;

    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private SqlSession sqlSession;
    @MockitoSpyBean private NotificationWriteMapper notifications;
    @MockitoSpyBean private UserFollowMapper follows;

    private final List<String> fixtures = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.user.follow.redis-key-prefix", () -> PREFIX);
        registry.add("app.user.public-query.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.public-query.redis-key-prefix", () -> PREFIX);
        registry.add("app.user.follow.user-per-minute-limit", () -> USER_LIMIT);
        registry.add("app.user.follow.ip-per-minute-limit", () -> SOURCE_LIMIT);
    }

    @AfterEach
    void cleanup() {
        var keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String userId : fixtures) {
            ContentTestSupport.clearWorkspace(jdbc, userId);
            jdbc.update("DELETE FROM user_follow WHERE follower_id=? OR followed_id=?", userId, userId);
            jdbc.update("DELETE FROM user_notification WHERE recipient_id=?", userId);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", userId);
            users.deleteById(userId);
        }
    }

    @Test
    void firstFollowCreatesTheRelationshipAndAnUnreadNotification() throws Exception {
        var follower = account(UserAccount.Status.ACTIVE);
        var target = account(UserAccount.Status.ACTIVE);

        var response = follow(target.getId(), token(follower));

        assertEquals(204, response.statusCode(), response.body());
        assertEquals("", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        String relationshipId = jdbc.queryForObject(
                "SELECT id FROM user_follow WHERE follower_id=? AND followed_id=?",
                String.class, follower.getId(), target.getId());
        assertEquals(1L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_notification WHERE recipient_id=? AND source_event_id=?
                    AND type='NEW_FOLLOWER' AND read_at IS NULL AND target_type IS NULL AND target_id IS NULL
                """, Long.class, target.getId(), relationshipId));
    }

    @Test
    void cancellationRemovesOnlyTheCallersRelationshipAndUpdatesCounts() throws Exception {
        var actor = account(UserAccount.Status.ACTIVE);
        var anotherFollower = account(UserAccount.Status.ACTIVE);
        var target = account(UserAccount.Status.ACTIVE);
        String postId = publishArticle(target.getId());
        String token = token(actor);
        assertEquals(204, follow(target.getId(), token).statusCode());
        assertEquals(204, follow(target.getId(), token(anotherFollower)).statusCode());
        assertEquals(204, follow(anotherFollower.getId(), token).statusCode());
        var notices = jdbc.queryForList("SELECT id FROM user_notification WHERE recipient_id=? ORDER BY id",
                String.class, target.getId());

        var response = unfollow(target.getId(), token);

        assertEquals(204, response.statusCode(), response.body());
        assertEquals("", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(List.of(anotherFollower.getId()), jdbc.queryForList(
                "SELECT follower_id FROM user_follow WHERE followed_id=?", String.class, target.getId()));
        assertWriteCounts(anotherFollower.getId(), 1);
        assertEquals(notices, jdbc.queryForList("SELECT id FROM user_notification WHERE recipient_id=? ORDER BY id",
                String.class, target.getId()));
        assertEquals(1, getJson("/api/v1/users/" + target.getId()).read("$.data.followerCount", Integer.class));
        assertEquals(1, getJson("/api/v1/contents/" + postId).read("$.data.author.followerCount", Integer.class));
    }

    @Test
    void cancellationsStayIdempotentWhenTheTargetIsDisabledOrMissing() throws Exception {
        var actor = account(UserAccount.Status.ACTIVE);
        var target = account(UserAccount.Status.ACTIVE);
        String token = token(actor);
        assertEquals(204, follow(target.getId(), token).statusCode());
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", target.getId());

        var requests = IntStream.range(0, 4).mapToObj(index -> http.sendAsync(
                relationshipRequest("DELETE", target.getId(), token, HttpRequest.BodyPublishers.noBody()),
                HttpResponse.BodyHandlers.ofString())).toList();
        for (var request : requests) {
            var response = request.get(20, TimeUnit.SECONDS);
            assertEquals(204, response.statusCode(), response.body());
        }

        assertEquals(204, unfollow(target.getId(), token).statusCode());
        assertEquals(204, unfollow(id(), token).statusCode());
        assertEquals(204, unfollow(actor.getId(), token).statusCode());
        assertEquals(0, getJson("/api/v1/users/" + target.getId()).read("$.data.followerCount", Integer.class));
    }

    @Test
    void invalidInputAndRestrictedActorsCannotCancelTheRelationship() throws Exception {
        var actor = account(UserAccount.Status.ACTIVE);
        var target = account(UserAccount.Status.ACTIVE);
        String token = token(actor);
        assertEquals(204, follow(target.getId(), token).statusCode());

        problem(unfollow(target.getId(), null), 401, "AUTHENTICATION_REQUIRED");
        problem(unfollow("bad.id", token), 422, "VALIDATION_ERROR");
        problem(unfollow(target.getId() + "?extra=true", token), 400, "MALFORMED_REQUEST");
        problem(http.send(relationshipRequest("DELETE", target.getId(), token, HttpRequest.BodyPublishers.ofString("{}")),
                HttpResponse.BodyHandlers.ofString()), 400, "MALFORMED_REQUEST");
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", actor.getId());
        problem(unfollow(target.getId(), token), 403, "ACCOUNT_RESTRICTED");
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", actor.getId());
        problem(unfollow(target.getId(), token), 403, "ACCOUNT_RESTRICTED");
        assertWriteCounts(target.getId(), 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "source"})
    void aCancellationConsumesTheQuotaSharedWithFollow(String quota) throws Exception {
        var actor = account(UserAccount.Status.ACTIVE);
        var target = account(UserAccount.Status.ACTIVE);
        String token = token(actor);
        assertEquals(204, follow(target.getId(), token).statusCode());
        boolean userQuota = quota.equals("user");
        String quotaKey = PREFIX + "{user-follow}:" + quota + ":" + TokenDigest.sha256(
                userQuota ? actor.getId() : "127.0.0.1");
        redis.opsForValue().set(quotaKey, Integer.toString((userQuota ? USER_LIMIT : SOURCE_LIMIT) - 1),
                Duration.ofMinutes(1));

        assertEquals(204, unfollow(target.getId(), token).statusCode());
        problem(follow(target.getId(), token), 429, "RATE_LIMITED");
        var replay = unfollow(target.getId(), token);
        problem(replay, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(replay.headers().firstValue("Retry-After").orElseThrow()) > 0);
    }

    @Test
    void databaseFailureRollsBackCancellation() throws Exception {
        var actor = account(UserAccount.Status.ACTIVE);
        var target = account(UserAccount.Status.ACTIVE);
        String token = token(actor);
        assertEquals(204, follow(target.getId(), token).statusCode());
        doAnswer(call -> {
            assertEquals(1, deleteRelationship(actor.getId(), target.getId()));
            throw new TransientDataAccessResourceException("injected private cancellation failure");
        }).when(follows).delete(actor.getId(), target.getId());

        var response = unfollow(target.getId(), token);

        problem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("injected private"));
        assertWriteCounts(target.getId(), 1);
    }

    @Test
    void followWaitsForInFlightCancellationThenReestablishesTheRelationship() throws Exception {
        var actor = account(UserAccount.Status.ACTIVE);
        var target = account(UserAccount.Status.ACTIVE);
        String token = token(actor);
        assertEquals(204, follow(target.getId(), token).statusCode());
        var relationshipDeleted = new CountDownLatch(1);
        var finishCancellation = new CountDownLatch(1);
        doAnswer(call -> {
            assertEquals(1, deleteRelationship(actor.getId(), target.getId()));
            relationshipDeleted.countDown();
            assertTrue(finishCancellation.await(10, TimeUnit.SECONDS));
            return null;
        }).when(follows).delete(actor.getId(), target.getId());

        var cancellation = http.sendAsync(relationshipRequest("DELETE", target.getId(), token,
                HttpRequest.BodyPublishers.noBody()), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(relationshipDeleted.await(5, TimeUnit.SECONDS));
            var reFollow = http.sendAsync(relationshipRequest("PUT", target.getId(), token,
                    HttpRequest.BodyPublishers.noBody()), HttpResponse.BodyHandlers.ofString());
            assertThrows(TimeoutException.class, () -> reFollow.get(200, TimeUnit.MILLISECONDS));
            finishCancellation.countDown();
            assertEquals(204, cancellation.get(20, TimeUnit.SECONDS).statusCode());
            assertEquals(204, reFollow.get(20, TimeUnit.SECONDS).statusCode());
            assertEquals(1, getJson("/api/v1/users/" + target.getId()).read("$.data.followerCount", Integer.class));
            assertEquals(2L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_notification WHERE recipient_id=? AND type='NEW_FOLLOWER'",
                    Long.class, target.getId()));
        } finally {
            finishCancellation.countDown();
        }
    }

    private int deleteRelationship(String followerId, String targetId) {
        return sqlSession.delete(UserFollowMapper.class.getName() + ".delete",
                Map.of("follower", followerId, "target", targetId));
    }

    @Test
    void publicProfilesAndContentShowTheNumberOfDistinctFollowers() throws Exception {
        var target = account(UserAccount.Status.ACTIVE);
        var first = account(UserAccount.Status.ACTIVE);
        var second = account(UserAccount.Status.ACTIVE);
        String postId = publishArticle(target.getId());
        String firstToken = token(first);

        assertEquals(204, follow(target.getId(), firstToken).statusCode());
        assertEquals(204, follow(target.getId(), firstToken).statusCode());
        assertEquals(204, follow(target.getId(), token(second)).statusCode());

        String profilePath = "/api/v1/users/" + target.getId();
        assertEquals(2, getJson(profilePath).read("$.data.followerCount", Integer.class));
        assertEquals(2, getJson("/api/v1/contents/" + postId).read("$.data.author.followerCount", Integer.class));
        assertEquals(2, getJson(profilePath + "/contents").read("$.data.items[0].author.followerCount", Integer.class));
        assertEquals(0, getJson("/api/v1/users/" + first.getId()).read("$.data.followerCount", Integer.class));
    }

    @Test
    void concurrentAndRepeatedFollowsCreateOnlyOneRelationshipAndNotice() throws Exception {
        var follower = account(UserAccount.Status.ACTIVE);
        var target = account(UserAccount.Status.WRITE_BLOCKED);
        String token = token(follower);
        var requests = IntStream.range(0, 5).mapToObj(index -> http.sendAsync(
                relationshipRequest("PUT", target.getId(), token, HttpRequest.BodyPublishers.noBody()),
                HttpResponse.BodyHandlers.ofString())).toList();

        for (var request : requests) {
            var response = request.get(20, TimeUnit.SECONDS);
            assertEquals(204, response.statusCode(), response.body());
        }
        var createdAt = jdbc.queryForMap("SELECT id, created_at FROM user_follow WHERE followed_id=?", target.getId());
        assertEquals(204, follow(target.getId(), token).statusCode());
        assertEquals(createdAt, jdbc.queryForMap("SELECT id, created_at FROM user_follow WHERE followed_id=?", target.getId()));
        assertWriteCounts(target.getId(), 1);
    }

    @Test
    void missingAndDisabledTargetsReturnTheSameError() throws Exception {
        var follower = account(UserAccount.Status.ACTIVE);
        var target = account(UserAccount.Status.DISABLED);
        String token = token(follower);

        problem(follow(id(), token), 404, "RESOURCE_NOT_FOUND");
        problem(follow(target.getId(), token), 404, "RESOURCE_NOT_FOUND");
        assertWriteCounts(target.getId(), 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WRITE_BLOCKED", "DISABLED"})
    void restrictedActorCannotFollow(String state) throws Exception {
        var follower = account(UserAccount.Status.valueOf(state));
        var target = account(UserAccount.Status.ACTIVE);

        problem(follow(target.getId(), token(follower)), 403, "ACCOUNT_RESTRICTED");
        assertWriteCounts(target.getId(), 0);
    }

    @Test
    void missingOrUnverifiedIdentityCannotFollow() throws Exception {
        var follower = account(UserAccount.Status.ACTIVE);
        var target = account(UserAccount.Status.ACTIVE);
        jdbc.update("UPDATE app_user SET email_verified=FALSE WHERE id=?", follower.getId());

        problem(follow(target.getId(), null), 401, "AUTHENTICATION_REQUIRED");
        problem(follow(target.getId(), token(follower)), 403, "PERMISSION_DENIED");
        assertWriteCounts(target.getId(), 0);
    }

    @Test
    void selfFollowAndInvalidIdsAreRejected() throws Exception {
        var follower = account(UserAccount.Status.ACTIVE);
        String token = token(follower);

        problem(follow(follower.getId(), token), 422, "VALIDATION_ERROR");
        problem(follow("bad.id", token), 422, "VALIDATION_ERROR");
        problem(follow("a".repeat(65), token), 422, "VALIDATION_ERROR");
        assertWriteCounts(follower.getId(), 0);
    }

    @Test
    void queryParametersAndRequestBodyAreRejected() throws Exception {
        var follower = account(UserAccount.Status.ACTIVE);
        var target = account(UserAccount.Status.ACTIVE);
        String token = token(follower);

        problem(follow(target.getId() + "?extra=true", token), 400, "MALFORMED_REQUEST");
        problem(http.send(relationshipRequest("PUT", target.getId(), token, HttpRequest.BodyPublishers.ofString("{}")),
                HttpResponse.BodyHandlers.ofString()), 400, "MALFORMED_REQUEST");
        assertWriteCounts(target.getId(), 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "source"})
    void eitherQuotaCanIndependentlyRejectTheRequest(String quota) throws Exception {
        var follower = account(UserAccount.Status.ACTIVE);
        var target = account(UserAccount.Status.ACTIVE);
        String quotaKey = PREFIX + "{user-follow}:" + quota + ":" + TokenDigest.sha256(
                quota.equals("user") ? follower.getId() : "127.0.0.1");
        redis.opsForValue().set(quotaKey, Integer.toString(quota.equals("user") ? USER_LIMIT : SOURCE_LIMIT), Duration.ofMinutes(1));

        var response = follow(target.getId(), token(follower));
        problem(response, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
        assertWriteCounts(target.getId(), 0);
    }

    @Test
    void notificationWriteFailureRollsBackTheRelationAndNotice() throws Exception {
        var follower = account(UserAccount.Status.ACTIVE);
        var target = account(UserAccount.Status.ACTIVE);
        doAnswer(call -> {
            assertEquals(1, sqlSession.insert(NotificationWriteMapper.class.getName() + ".insertNewFollower",
                    Map.of("id", call.getArgument(0), "recipient", call.getArgument(1),
                            "followId", call.getArgument(2), "body", call.getArgument(3), "now", call.getArgument(4))));
            throw new TransientDataAccessResourceException("injected private notification failure");
        }).when(notifications).insertNewFollower(anyString(), anyString(), anyString(), anyString(), any());

        var response = follow(target.getId(), token(follower));
        problem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("injected private"));
        assertWriteCounts(target.getId(), 0);
    }

    private void assertWriteCounts(String targetId, long expected) {
        assertEquals(expected, jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_follow WHERE followed_id=?", Long.class, targetId));
        assertEquals(expected, jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_notification WHERE recipient_id=? AND type='NEW_FOLLOWER'", Long.class, targetId));
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.parse(response.body()).read("$.code", String.class));
    }

    private String publishArticle(String authorId) {
        String contentId = id();
        String revisionId = id();
        jdbc.update("""
                INSERT INTO community_content(id, author_id, kind, lifecycle, publication_status, version, created_at, updated_at)
                VALUES (?, ?, 'ARTICLE', 'ACTIVE', 'UNPUBLISHED', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, contentId, authorId);
        jdbc.update("""
                INSERT INTO content_revision(id, content_id, revision_number, status, title, body_markdown, created_at, updated_at)
                VALUES (?, ?, 1, 'APPROVED', '关注测试', '公开正文', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, revisionId, contentId);
        jdbc.update("""
                UPDATE community_content SET published_revision_id=?, publication_status='PUBLISHED',
                     first_published_at=UTC_TIMESTAMP(6), published_at=UTC_TIMESTAMP(6) WHERE id=?
                """, revisionId, contentId);
        return contentId;
    }

    private com.jayway.jsonpath.DocumentContext getJson(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JsonPath.parse(response.body());
    }

    private UserAccount account(UserAccount.Status status) {
        var account = LoginTestSupport.account();
        String id = id();
        account.setId(id);
        account.setEmail("follow-" + id + "@example.test");
        account.setEmailNormalized(account.getEmail());
        account.setAccountStatus(status);
        fixtures.add(id);
        users.insert(account);
        roles.insertDefaultRole(id, account.getCreatedAt());
        return account;
    }

    private String token(UserAccount account) {
        return sessions.create(account.getId(), account.getAuthVersion(), "user-follow-it").accessToken();
    }

    private HttpResponse<String> follow(String userId, String token) throws Exception {
        return http.send(relationshipRequest("PUT", userId, token, HttpRequest.BodyPublishers.noBody()),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> unfollow(String userId, String token) throws Exception {
        return http.send(relationshipRequest("DELETE", userId, token, HttpRequest.BodyPublishers.noBody()),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest relationshipRequest(String method, String userId, String token, HttpRequest.BodyPublisher body) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH + userId))
                .timeout(Duration.ofSeconds(20)).method(method, body);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request.build();
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
