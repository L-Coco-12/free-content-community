package com.freecontent.community.notification.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.notification.config.NotificationQueryProperties;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false",
        "app.media.processing.enabled=false"})
class MyNotificationsFlowIT {
    private static final String PREFIX = "test:my-notifications:" + UUID.randomUUID() + ":";
    private static final String PATH = "/api/v1/me/notifications";
    private static final String COUNT = "/unread-count";
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 11, 18, 3, 4, 5);
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private NotificationQueryProperties quota;
    private final List<String> fixtures = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.notification.query.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String userId : fixtures) {
            jdbc.update("DELETE FROM user_notification WHERE recipient_id=?", userId);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", userId);
            users.deleteById(userId);
        }
    }

    @Test
    void listsOnlyMySafeNotificationEvenWhenItsTargetIsMissing() throws Exception {
        var viewer = account();
        var other = account();
        String mine = insert(viewer, CREATED, null, "CONTENT", "unavailable_content");
        String theirs = insert(other, CREATED.plusSeconds(1), null, null, null);

        var response = get("", token(viewer));

        assertEquals(200, response.statusCode(), response.body());
        var payload = JsonPath.parse(response.body());
        assertEquals(List.of(mine), payload.read("$.data.items[*].id"));
        Map<String, Object> row = payload.read("$.data.items[0]");
        assertEquals(Set.of("id", "type", "title", "body", "targetType", "targetId", "readAt", "createdAt"), row.keySet());
        assertEquals("作品已下架，请整改后重新提交。", row.get("body"));
        assertEquals("unavailable_content", row.get("targetId"));
        assertNull(row.get("readAt"));
        assertFalse(response.body().contains(theirs));
        assertFalse(payload.read("$.data.hasMore", Boolean.class));
        assertNull(payload.read("$.data.nextCursor"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void unreadCountIncludesOnlyMyUnreadRowsBeyondOnePage() throws Exception {
        var viewer = account();
        var other = account();
        for (int i = 0; i < 51; i++) {
            insert(viewer, CREATED, null, "CONTENT", "unavailable_content");
        }
        insert(viewer, CREATED, CREATED.plusSeconds(1), null, null);
        insert(other, CREATED, null, null, null);
        String token = token(viewer);
        Instant before = Instant.now();

        var response = get(COUNT, token);

        Instant after = Instant.now();
        assertEquals(200, response.statusCode(), response.body());
        var payload = JsonPath.parse(response.body());
        Map<String, Object> data = payload.read("$.data");
        assertEquals(Set.of("count", "asOf"), data.keySet());
        assertEquals(51L, payload.read("$.data.count", Long.class));
        Instant asOf = Instant.parse(payload.read("$.data.asOf", String.class));
        assertFalse(asOf.isBefore(before));
        assertFalse(asOf.isAfter(after));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), payload.read("$.requestId", String.class));
    }

    @Test
    void unreadCountReflectsCurrentReadStateWithoutMarkingNotificationsRead() throws Exception {
        var viewer = account();
        String notification = insert(viewer, CREATED, null, null, null);
        String token = token(viewer);

        var unread = get(COUNT, token);
        assertEquals(200, unread.statusCode(), unread.body());
        assertEquals(1L, JsonPath.parse(unread.body()).read("$.data.count", Long.class));
        assertNull(jdbc.queryForObject("SELECT read_at FROM user_notification WHERE id=?", LocalDateTime.class, notification));

        jdbc.update("UPDATE user_notification SET read_at=? WHERE id=?", CREATED.plusSeconds(1), notification);
        var read = get(COUNT, token);
        assertEquals(200, read.statusCode(), read.body());
        assertEquals(0L, JsonPath.parse(read.body()).read("$.data.count", Long.class));
    }

    @Test
    void unreadCountRejectsQueryParametersAndBodyBeforeChargingQuota() throws Exception {
        var viewer = account();
        String token = token(viewer);
        problem(get(COUNT + "?recipientId=other", token), 400, "MALFORMED_REQUEST");

        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH + COUNT))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method("GET", HttpRequest.BodyPublishers.ofString("{}")).build();
        problem(http.send(request, HttpResponse.BodyHandlers.ofString()), 400, "MALFORMED_REQUEST");
        assertNull(redis.opsForValue().get(quotaKey(viewer)));
    }

    @Test
    void listAndUnreadCountShareOneQueryQuota() throws Exception {
        var viewer = account();
        if (quota.userPerMinuteLimit() > 1) {
            redis.opsForValue().set(quotaKey(viewer), Integer.toString(quota.userPerMinuteLimit() - 1), Duration.ofSeconds(40));
        }
        String token = token(viewer);

        var list = get("", token);
        assertEquals(200, list.statusCode(), list.body());
        var count = get(COUNT, token);

        problem(count, 429, "RATE_LIMITED");
        assertTrue(Integer.parseInt(count.headers().firstValue("Retry-After").orElseThrow()) > 0);
    }

    @Test
    void unreadCursorPagesTiesWithoutRepeatsAndExcludesRowsAfterSnapshot() throws Exception {
        var viewer = account();
        String token = token(viewer);
        String first = insert(viewer, CREATED, null, null, null);
        String second = insert(viewer, CREATED, null, null, null);
        String older = insert(viewer, CREATED.minusSeconds(1), null, null, null);
        String read = insert(viewer, CREATED.plusSeconds(1), CREATED.plusSeconds(2), null, null);
        var ties = List.of(first, second).stream().sorted(Comparator.reverseOrder()).toList();

        var initial = get("?limit=2&readState=UNREAD", token);
        assertEquals(200, initial.statusCode(), initial.body());
        var page = JsonPath.parse(initial.body());
        assertEquals(ties, page.read("$.data.items[*].id"));
        assertTrue(page.read("$.data.hasMore", Boolean.class));
        String snapshot = page.read("$.data.snapshotAt");
        String cursor = page.read("$.data.nextCursor");
        assertNotNull(cursor);
        insert(viewer, LocalDateTime.ofInstant(Instant.parse(snapshot), ZoneOffset.UTC).plusSeconds(1), null, null, null);

        var after = get("?limit=2&readState=UNREAD&cursor=" + cursor, token);
        assertEquals(200, after.statusCode(), after.body());
        var next = JsonPath.parse(after.body());
        assertEquals(List.of(older), next.read("$.data.items[*].id"));
        assertEquals(snapshot, next.read("$.data.snapshotAt"));
        assertFalse(next.read("$.data.hasMore", Boolean.class));
        assertNull(next.read("$.data.nextCursor"));

        var readPage = get("?readState=READ", token);
        assertEquals(200, readPage.statusCode(), readPage.body());
        assertEquals(List.of(read), JsonPath.read(readPage.body(), "$.data.items[*].id"));
        Map<String, Object> row = JsonPath.read(readPage.body(), "$.data.items[0]");
        assertTrue(row.containsKey("targetType"));
        assertTrue(row.containsKey("targetId"));
        assertNull(row.get("targetType"));
        assertNull(row.get("targetId"));
        assertEquals("2026-11-18T03:04:07Z", row.get("readAt"));
    }

    @Test
    void cursorCannotBeReusedByAnotherAccountOrWithDifferentFilters() throws Exception {
        var viewer = account();
        var other = account();
        String token = token(viewer);
        insert(viewer, CREATED, null, null, null);
        insert(viewer, CREATED.minusSeconds(1), null, null, null);

        var first = get("?limit=1", token);
        assertEquals(200, first.statusCode(), first.body());
        String cursor = JsonPath.read(first.body(), "$.data.nextCursor");

        problem(get("?limit=1&cursor=" + cursor, token(other)), 400, "CURSOR_INVALID");
        problem(get("?limit=2&cursor=" + cursor, token), 400, "CURSOR_INVALID");
        problem(get("?limit=1&readState=UNREAD&cursor=" + cursor, token), 400, "CURSOR_INVALID");
    }

    @ParameterizedTest
    @CsvSource({
            "limit=abc,400,MALFORMED_REQUEST", "limit=0,422,VALIDATION_ERROR",
            "limit=51,422,VALIDATION_ERROR", "readState=OTHER,422,VALIDATION_ERROR",
            "limit=1&limit=2,400,MALFORMED_REQUEST", "recipientId=other,400,MALFORMED_REQUEST",
            "cursor=invalid,400,CURSOR_INVALID"
    })
    void invalidParametersAreRejectedBeforeChargingQuota(String query, int status, String error) throws Exception {
        var viewer = account();
        problem(get("?" + query, token(viewer)), status, error);
        assertNull(redis.opsForValue().get(quotaKey(viewer)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", COUNT})
    void readingRequiresAuthenticationButRemainsAvailableWhenBusinessWritesAreBlocked(String suffix) throws Exception {
        var viewer = account();
        String token = token(viewer);
        problem(get(suffix, null), 401, "AUTHENTICATION_REQUIRED");
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", viewer.getId());
        var blocked = get(suffix, token);
        assertEquals(200, blocked.statusCode(), blocked.body());
        if (suffix.isEmpty()) {
            assertEquals(List.of(), JsonPath.read(blocked.body(), "$.data.items"));
        } else {
            assertEquals(0L, JsonPath.parse(blocked.body()).read("$.data.count", Long.class));
        }
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", viewer.getId());
        problem(get(suffix, token), 403, "ACCOUNT_RESTRICTED");
    }

    @Test
    void exhaustedNotificationQuotaReturnsRetryAfter() throws Exception {
        var viewer = account();
        redis.opsForValue().set(quotaKey(viewer), Integer.toString(quota.userPerMinuteLimit()), Duration.ofSeconds(40));

        var response = get("", token(viewer));

        problem(response, 429, "RATE_LIMITED");
        assertTrue(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
    }

    private String quotaKey(UserAccount viewer) {
        return PREFIX + "{notification-query}:user:" + TokenDigest.sha256(viewer.getId());
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.read(response.body(), "$.code"));
    }

    private UserAccount account() {
        var account = LoginTestSupport.account();
        account.setId(id());
        account.setEmail("notification-" + account.getId() + "@example.test");
        account.setEmailNormalized(account.getEmail());
        fixtures.add(account.getId());
        users.insert(account);
        roles.insertDefaultRole(account.getId(), account.getCreatedAt());
        return account;
    }

    private String insert(UserAccount recipient, LocalDateTime created, LocalDateTime read, String targetType, String targetId) {
        String notification = id();
        jdbc.update("""
                INSERT INTO user_notification(id,recipient_id,source_event_id,type,title,body,target_type,target_id,read_at,created_at)
                VALUES (?,?,?,'MODERATION_RESULT','作品处理结果','作品已下架，请整改后重新提交。',?,?,?,?)
                """, notification, recipient.getId(), id(), targetType, targetId, read, created);
        return notification;
    }

    private String token(UserAccount account) {
        return sessions.create(account.getId(), account.getAuthVersion(), "notification-it").accessToken();
    }

    private HttpResponse<String> get(String suffix, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH + suffix))
                .timeout(Duration.ofSeconds(20)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
