package com.freecontent.community.user.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.common.pagination.CursorTokenCodec;
import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.user.config.UserFollowQueryProperties;
import com.freecontent.community.user.cursor.MyFollowingCursor;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.PublicUserMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.processing.enabled=false",
        "app.media.upload-cleanup.enabled=false"})
class MyFollowingFlowIT {
    private static final String PATH = "/api/v1/me/following";
    private static final String PREFIX = "test:my-following:" + UUID.randomUUID() + ":";
    private static final LocalDateTime OLD = LocalDateTime.of(2026, 11, 3, 8, 0);
    private static final int USER_LIMIT = 8;
    private static final int SOURCE_LIMIT = 12;

    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private UserFollowQueryProperties properties;
    @Autowired private JsonMapper jsonMapper;
    @MockitoSpyBean private PublicUserMapper profiles;
    @MockitoSpyBean private PublicContentMapper contents;

    private final List<String> accounts = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.user.follow-query.redis-key-prefix", () -> PREFIX);
        registry.add("app.user.follow-query.user-per-minute-limit", () -> USER_LIMIT);
        registry.add("app.user.follow-query.ip-per-minute-limit", () -> SOURCE_LIMIT);
        registry.add("app.user.follow.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        var keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String user : accounts) {
            ContentTestSupport.clearWorkspace(jdbc, user);
            jdbc.update("DELETE FROM user_follow WHERE follower_id=? OR followed_id=?", user, user);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", user);
            users.deleteById(user);
        }
    }

    @Test
    void returnsOnlyMyFollowingInRelationshipOrderAndOnlyPublicFields() throws Exception {
        var viewer = account();
        var other = account();
        var first = account();
        var second = account();
        var unrelated = account();
        insert(viewer, second, OLD, id());
        insert(viewer, first, OLD.plusSeconds(1), id());
        insert(other, first, OLD, id());
        insert(other, unrelated, OLD.plusSeconds(2), id());

        var response = get("", token(viewer));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var json = JsonPath.parse(response.body());
        assertEquals(List.of(first.getId(), second.getId()), json.read("$.data.items[*].id"));
        assertEquals(Set.of("id", "displayName", "bio", "avatarUrl", "createdAt", "publishedContentCount", "followerCount"),
                json.read("$.data.items[0]", Map.class).keySet());
        assertEquals(2L, json.read("$.data.items[0].followerCount", Long.class));
        assertNull(json.read("$.data.nextCursor"));
        assertFalse(json.read("$.data.hasMore", Boolean.class));
    }

    @Test
    void tiesUseTheRelationshipIdAndTheSnapshotExcludesLaterFollows() throws Exception {
        var viewer = account();
        var newer = account();
        var higher = account();
        var lower = account();
        var older = account();
        var later = account();
        insert(viewer, newer, OLD.plusNanos(1_000), id());
        insert(viewer, higher, OLD, "z_" + id());
        insert(viewer, lower, OLD, "a_" + id());
        insert(viewer, older, OLD.minusNanos(1_000), id());
        String token = token(viewer);

        var first = page("?limit=2", token);
        assertEquals(List.of(newer.getId(), higher.getId()), first.read("$.data.items[*].id"));
        assertEquals(newer.getCreatedAt().toInstant(ZoneOffset.UTC),
                Instant.parse(first.read("$.data.items[0].createdAt", String.class)));
        assertTrue(first.read("$.data.hasMore", Boolean.class));
        String snapshot = first.read("$.data.snapshotAt", String.class);
        String cursor = first.read("$.data.nextCursor", String.class);
        jdbc.update("DELETE FROM user_follow WHERE follower_id=? AND followed_id=?", viewer.getId(), higher.getId());
        var afterSnapshot = LocalDateTime.ofInstant(Instant.parse(snapshot).plusSeconds(1), ZoneOffset.UTC);
        insert(viewer, later, afterSnapshot, id());

        var second = page("?limit=2&cursor=" + cursor, token);
        assertEquals(List.of(lower.getId(), older.getId()), second.read("$.data.items[*].id"));
        assertEquals(snapshot, second.read("$.data.snapshotAt", String.class));
        assertFalse(second.read("$.data.hasMore", Boolean.class));
        assertNull(second.read("$.data.nextCursor"));
    }

    @Test
    void defaultLimitIsTwentyAndAnEmptyPageHasNoCursor() throws Exception {
        var viewer = account();
        String token = token(viewer);
        var empty = page("", token);
        assertEquals(List.of(), empty.read("$.data.items"));
        assertFalse(empty.read("$.data.hasMore", Boolean.class));
        assertNull(empty.read("$.data.nextCursor"));

        for (int i = 0; i < 21; i++) {
            insert(viewer, account(), OLD.plusSeconds(i), id());
        }
        var first = page("", token);
        assertEquals(20, first.read("$.data.items", List.class).size());
        assertTrue(first.read("$.data.hasMore", Boolean.class));
        String cursor = first.read("$.data.nextCursor", String.class);
        var last = page("?cursor=" + cursor + "&limit=20", token);
        assertEquals(1, last.read("$.data.items", List.class).size());
        assertFalse(last.read("$.data.hasMore", Boolean.class));
        var max = page("?limit=50", token);
        assertEquals(21, max.read("$.data.items", List.class).size());
    }

    @Test
    void cursorRejectsTamperingAnotherAccountAnotherLimitAndAnotherEndpoint() throws Exception {
        var viewer = account();
        var other = account();
        insert(viewer, account(), OLD, id());
        insert(viewer, account(), OLD, id());
        String token = token(viewer);
        var first = page("?limit=1", token);
        String cursor = first.read("$.data.nextCursor", String.class);
        String changed = cursor.substring(0, 3) + (cursor.charAt(3) == 'A' ? 'B' : 'A') + cursor.substring(4);
        String foreign = new CursorTokenCodec(properties.cursorKeyBase64(), "v1:GET:/api/v1/me/bookmarks", jsonMapper)
                .encode(Map.of("userId", viewer.getId(), "limit", 1));

        problem(get("?limit=1&cursor=" + changed, token), 400, "CURSOR_INVALID");
        problem(get("?limit=2&cursor=" + cursor, token), 400, "CURSOR_INVALID");
        problem(get("?limit=1&cursor=" + cursor, token(other)), 400, "CURSOR_INVALID");
        problem(get("?limit=1&cursor=" + foreign, token), 400, "CURSOR_INVALID");
    }

    @Test
    void expiredAuthenticCursorReturnsGone() throws Exception {
        var viewer = account();
        var past = Clock.fixed(Instant.now().minusSeconds(properties.cursorTtlSeconds() + 10L), ZoneOffset.UTC);
        var issuer = new MyFollowingCursor(properties, jsonMapper, past);
        var query = issuer.open(null, viewer.getId(), 1);
        var cursor = issuer.next(query, query.snapshotAt(), id());

        problem(get("?limit=1&cursor=" + cursor, token(viewer)), 410, "CURSOR_EXPIRED");
    }

    @Test
    void validationDistinguishesSyntaxFromValueErrors() throws Exception {
        String token = token(account());
        problem(get("?limit=1&limit=2", token), 400, "MALFORMED_REQUEST");
        problem(get("?limit=twenty", token), 400, "MALFORMED_REQUEST");
        problem(get("?targetId=someone", token), 400, "MALFORMED_REQUEST");
        problem(get("", token, HttpRequest.BodyPublishers.ofString("{}")), 400, "MALFORMED_REQUEST");
        problem(get("?limit=0", token), 422, "VALIDATION_ERROR");
        problem(get("?limit=51", token), 422, "VALIDATION_ERROR");
        problem(get("?cursor=" + "a".repeat(4097), token), 422, "VALIDATION_ERROR");
        problem(get("?cursor=", token), 400, "CURSOR_INVALID");
    }

    @Test
    void writeBlockedUsersCanReadExistingFollowsWhileDisabledUsersCannot() throws Exception {
        var viewer = account();
        var target = account();
        insert(viewer, target, OLD, id());
        String token = token(viewer);
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", target.getId());
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", viewer.getId());

        problem(get("", null), 401, "AUTHENTICATION_REQUIRED");
        assertEquals(List.of(target.getId()), page("", token).read("$.data.items[*].id"));
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", viewer.getId());
        problem(get("", token), 403, "ACCOUNT_RESTRICTED");
    }

    @Test
    void loadsThePageOfProfilesAndVisibleWorkCountsInBatches() throws Exception {
        var viewer = account();
        var first = account();
        var second = account();
        var beyondPage = account();
        seedArticle(first.getId(), true);
        seedArticle(first.getId(), false);
        insert(viewer, first, OLD.plusSeconds(2), id());
        insert(viewer, second, OLD.plusSeconds(1), id());
        insert(viewer, beyondPage, OLD, id());

        var page = page("?limit=2", token(viewer));

        assertEquals(List.of(1, 0), page.read("$.data.items[*].publishedContentCount"));
        assertTrue(page.read("$.data.hasMore", Boolean.class));
        var ids = List.of(first.getId(), second.getId());
        verify(profiles).findByIds(ids);
        verify(contents).countVisibleByAuthors(ids);
        verify(profiles, never()).findById(anyString());
        verify(contents, never()).countVisibleByAuthor(anyString());
    }

    @Test
    void failureToLoadWorkCountsReturnsDependencyUnavailable() throws Exception {
        var viewer = account();
        insert(viewer, account(), OLD, id());
        doThrow(new TransientDataAccessResourceException("injected count failure"))
                .when(contents).countVisibleByAuthors(anyList());

        var response = get("", token(viewer));

        problem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("injected count failure"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "source"})
    void eitherReadBudgetReturns429WithoutBlockingCancellation(String scope) throws Exception {
        var viewer = account();
        var target = account();
        insert(viewer, target, OLD, id());
        boolean user = scope.equals("user");
        String quotaKey = PREFIX + "{user-follow-query}:" + scope + ":" + TokenDigest.sha256(
                user ? viewer.getId() : "127.0.0.1");
        redis.opsForValue().set(quotaKey, Integer.toString(user ? USER_LIMIT : SOURCE_LIMIT), Duration.ofMinutes(1));
        String token = token(viewer);

        var response = get("", token);

        problem(response, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
        var cancellation = request("/" + target.getId(), token).DELETE().build();
        var cancelled = http.send(cancellation, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, cancelled.statusCode(), cancelled.body());
    }

    private void seedArticle(String authorId, boolean published) {
        String contentId = id();
        jdbc.update("""
                INSERT INTO community_content(id, author_id, kind, lifecycle, publication_status, version, created_at, updated_at)
                VALUES (?, ?, 'ARTICLE', 'ACTIVE', 'UNPUBLISHED', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, contentId, authorId);
        if (!published) return;
        String revisionId = id();
        jdbc.update("""
                INSERT INTO content_revision(id, content_id, revision_number, status, title, body_markdown, created_at, updated_at)
                VALUES (?, ?, 1, 'APPROVED', '公开作品', '公开正文', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, revisionId, contentId);
        jdbc.update("""
                UPDATE community_content SET published_revision_id=?, publication_status='PUBLISHED',
                      first_published_at=UTC_TIMESTAMP(6), published_at=UTC_TIMESTAMP(6) WHERE id=?
                """, revisionId, contentId);
    }

    private DocumentContext page(String query, String token) throws Exception {
        var response = get(query, token);
        assertEquals(200, response.statusCode(), response.body());
        return JsonPath.parse(response.body());
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.parse(response.body()).read("$.code", String.class));
    }

    private UserAccount account() {
        var user = LoginTestSupport.account();
        user.setId(id());
        user.setEmail("following-" + user.getId() + "@example.test");
        user.setEmailNormalized(user.getEmail());
        accounts.add(user.getId());
        users.insert(user);
        roles.insertDefaultRole(user.getId(), user.getCreatedAt());
        return user;
    }

    private void insert(UserAccount viewer, UserAccount target, LocalDateTime createdAt, String id) {
        jdbc.update("INSERT INTO user_follow(id, follower_id, followed_id, created_at) VALUES (?, ?, ?, ?)",
                id, viewer.getId(), target.getId(), createdAt);
    }

    private String token(UserAccount user) {
        return sessions.create(user.getId(), user.getAuthVersion(), "my-following-it").accessToken();
    }

    private HttpResponse<String> get(String query, String token) throws Exception {
        return get(query, token, HttpRequest.BodyPublishers.noBody());
    }

    private HttpResponse<String> get(String query, String token, HttpRequest.BodyPublisher body) throws Exception {
        return http.send(request(query, token).method("GET", body).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String pathSuffix, String token) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH + pathSuffix))
                .timeout(Duration.ofSeconds(20));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
