package com.freecontent.community.content.browse.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.content.browse.config.PublicContentCursorProperties;
import com.freecontent.community.content.browse.cursor.PublicContentCursor;
import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import com.freecontent.community.content.mapper.ContentBookmarkReadMapper;
import com.freecontent.community.content.mapper.ContentLikeReadMapper;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.PublicUserMapper;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.freecontent.community.user.model.PublicUserProfile;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.processing.enabled=false",
        "app.media.upload-cleanup.enabled=false"})
class FollowingFeedFlowIT {
    private static final String PATH = "/api/v1/feeds/following";
    private static final String PREFIX = "test:following-feed:" + UUID.randomUUID() + ":";
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 3, 8, 0);
    private static final int USER_LIMIT = 8;
    private static final int SOURCE_LIMIT = 12;

    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PublicContentCursor cursors;
    @Autowired private PublicContentCursorProperties cursorProperties;
    @Autowired private JsonMapper json;
    @MockitoSpyBean private PublicContentMapper reads;
    @MockitoSpyBean private PublicUserMapper profiles;
    @MockitoSpyBean private ContentLikeReadMapper likes;
    @MockitoSpyBean private ContentBookmarkReadMapper bookmarks;
    private final List<String> accounts = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.following-feed.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.following-feed.user-per-minute-limit", () -> USER_LIMIT);
        registry.add("app.content.following-feed.ip-per-minute-limit", () -> SOURCE_LIMIT);
        registry.add("app.content.public-query.redis-key-prefix", () -> PREFIX);
        registry.add("app.user.follow.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        var keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String userId : accounts) ContentTestSupport.clearWorkspace(jdbc, userId);
        for (String userId : accounts) {
            jdbc.update("DELETE FROM user_notification WHERE recipient_id=?", userId);
            jdbc.update("DELETE FROM user_follow WHERE follower_id=? OR followed_id=?", userId, userId);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", userId);
            users.deleteById(userId);
        }
    }

    @Test
    void returnsOnlyCurrentlyPublicCardsFromAuthorsIFollow() throws Exception {
        var viewer = account();
        var following = account();
        var unrelated = account();
        follow(viewer, following);
        follow(unrelated, viewer);
        var included = publish(following, id(), BASE);
        var takenDown = publish(following, id(), BASE.plusSeconds(1));
        jdbc.update("UPDATE community_content SET publication_status='TAKEN_DOWN' WHERE id=?", takenDown.id());
        publish(unrelated, id(), BASE.plusSeconds(2));
        publish(viewer, id(), BASE.plusSeconds(3));

        var response = get("", token(viewer));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var page = JsonPath.parse(response.body());
        assertEquals(List.of(included.id()), page.read("$.data.items[*].id"));
        assertEquals(following.getId(), page.read("$.data.items[0].author.id", String.class));
        assertEquals(1, page.read("$.data.items[0].author.publishedContentCount", Integer.class));
        assertFalse(page.read("$.data.hasMore", Boolean.class));
        assertNull(page.read("$.data.nextCursor"));
    }

    @Test
    void eachPageUsesCurrentFollowsAndVisibilityWithinTheInitialPublicationBound() throws Exception {
        var viewer = account();
        var firstAuthor = account();
        var steadyAuthor = account();
        var newlyFollowedAuthor = account();
        follow(viewer, firstAuthor);
        follow(viewer, steadyAuthor);
        var highest = publish(firstAuthor, "z_" + id(), BASE);
        var tied = publish(steadyAuthor, "a_" + id(), BASE);
        publish(firstAuthor, id(), BASE.minusSeconds(1));
        var withdrawn = publish(steadyAuthor, id(), BASE.minusSeconds(2));
        var newlyVisible = publish(newlyFollowedAuthor, id(), BASE.minusSeconds(3));
        String token = token(viewer);

        var first = page("?limit=1", token);
        assertEquals(List.of(highest.id()), ids(first));
        assertTrue(first.read("$.data.hasMore", Boolean.class));
        String snapshot = first.read("$.data.snapshotAt", String.class);
        var afterSnapshot = LocalDateTime.ofInstant(Instant.parse(snapshot).plusSeconds(1), ZoneOffset.UTC);
        publish(steadyAuthor, id(), afterSnapshot);
        jdbc.update("UPDATE community_content SET publication_status='WITHDRAWN' WHERE id=?", withdrawn.id());
        String followingPath = "/api/v1/me/following/";
        var removed = send("DELETE", followingPath + firstAuthor.getId(), token, HttpRequest.BodyPublishers.noBody());
        assertEquals(204, removed.statusCode(), removed.body());
        var added = send("PUT", followingPath + newlyFollowedAuthor.getId(), token, HttpRequest.BodyPublishers.noBody());
        assertEquals(204, added.statusCode(), added.body());

        var second = page("?limit=1&cursor=" + first.read("$.data.nextCursor", String.class), token);
        assertEquals(List.of(tied.id()), ids(second));
        assertTrue(second.read("$.data.hasMore", Boolean.class));
        assertEquals(snapshot, second.read("$.data.snapshotAt", String.class));
        var third = page("?limit=1&cursor=" + second.read("$.data.nextCursor", String.class), token);
        assertEquals(List.of(newlyVisible.id()), ids(third));
        assertEquals(snapshot, third.read("$.data.snapshotAt", String.class));
        assertFalse(third.read("$.data.hasMore", Boolean.class));
        assertNull(third.read("$.data.nextCursor"));
    }

    @Test
    void replacingThePublishedRevisionUsesTheLatestCardAtItsOriginalPosition() throws Exception {
        var viewer = account();
        var author = account();
        follow(viewer, author);
        var older = publish(author, id(), BASE);
        var newer = publish(author, id(), BASE.plusSeconds(1));
        String approved = insertRevision(older.id(), 2, "APPROVED", "重新公开的稿件");
        String draft = insertRevision(older.id(), 3, "DRAFT", "私有工作稿");
        jdbc.update("""
                UPDATE community_content SET published_revision_id=?, published_at=?, working_revision_id=? WHERE id=?
                """, approved, BASE.plusSeconds(2), draft, older.id());

        var feed = page("", token(viewer));

        assertEquals(List.of(newer.id(), older.id()), ids(feed));
        assertEquals(approved, feed.read("$.data.items[1].publishedRevisionId", String.class));
        assertEquals("重新公开的稿件", feed.read("$.data.items[1].title", String.class));
        assertFalse(feed.read("$.data.items[1]", Map.class).containsKey("bodyMarkdown"));
        assertFalse(feed.read("$.data.items[1].author", Map.class).containsKey("email"));
    }

    @Test
    void cardsUseTheCurrentViewersInteractionsAndLoadPageDataInBatches() throws Exception {
        var viewer = account();
        var other = account();
        var author = account();
        follow(viewer, author);
        follow(other, author);
        var first = publish(author, id(), BASE.plusSeconds(1));
        var second = publish(author, id(), BASE);
        jdbc.update("INSERT INTO content_like(user_id, content_id, created_at) VALUES (?, ?, UTC_TIMESTAMP(6))",
                viewer.getId(), first.id());
        jdbc.update("""
                INSERT INTO content_like_stat(content_id, like_count, created_at, updated_at)
                VALUES (?, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, first.id());
        jdbc.update("INSERT INTO content_bookmark(id, user_id, content_id, created_at) VALUES (?, ?, ?, UTC_TIMESTAMP(6))",
                id(), viewer.getId(), first.id());

        var feed = page("", token(viewer));

        assertEquals(List.of(first.id(), second.id()), ids(feed));
        assertEquals(List.of(true, false), feed.read("$.data.items[*].viewerHasLiked"));
        assertEquals(List.of(true, false), feed.read("$.data.items[*].viewerHasBookmarked"));
        assertEquals(List.of(1, 0), feed.read("$.data.items[*].likeCount"));
        verify(reads).findAssetsForRevisions(List.of(first.revision(), second.revision()));
        verify(reads).findTagsForRevisions(List.of(first.revision(), second.revision()));
        verify(reads).countVisibleByAuthors(List.of(author.getId()));
        verify(profiles).findByIds(List.of(author.getId()));
        verify(likes).find(List.of(first.id(), second.id()), viewer.getId());
        verify(bookmarks).find(List.of(first.id(), second.id()), viewer.getId());
        verify(reads, never()).findById(anyString());
        verify(profiles, never()).findById(anyString());
        var otherFeed = page("", token(other));
        assertEquals(List.of(false, false), otherFeed.read("$.data.items[*].viewerHasBookmarked"));
        assertEquals(List.of(false, false), otherFeed.read("$.data.items[*].viewerHasLiked"));
        assertEquals(List.of(1, 0), otherFeed.read("$.data.items[*].likeCount"));
    }

    @Test
    void anEmptyFilteredPageStillAdvancesFromItsLastScannedCandidate() throws Exception {
        var viewer = account();
        var author = account();
        follow(viewer, author);
        publish(author, id(), BASE.plusSeconds(1));
        var older = publish(author, id(), BASE);
        String token = token(viewer);
        doReturn(List.of()).when(profiles).findByIds(anyList());

        var first = page("?limit=1", token);
        assertEquals(List.of(), ids(first));
        assertTrue(first.read("$.data.hasMore", Boolean.class));
        String next = first.read("$.data.nextCursor", String.class);
        doReturn(List.of(new PublicUserProfile(author.getId(),
                author.getDisplayName(), author.getBio(), author.getCreatedAt(), null, 1)))
                .when(profiles).findByIds(List.of(author.getId()));
        var second = page("?limit=1&cursor=" + next, token);
        assertEquals(List.of(older.id()), ids(second));
        assertFalse(second.read("$.data.hasMore", Boolean.class));
    }

    @Test
    void cursorsAreBoundToTheViewerLimitAndEndpoint() throws Exception {
        var viewer = account();
        var author = account();
        follow(viewer, author);
        publish(author, id(), BASE);
        publish(author, id(), BASE.plusSeconds(1));
        String token = token(viewer);
        String cursor = page("?limit=1", token).read("$.data.nextCursor", String.class);
        String changed = cursor.substring(0, 3) + (cursor.charAt(3) == 'A' ? 'B' : 'A') + cursor.substring(4);
        var publicQuery = cursors.open(null, 1, null, null, viewer.getId());
        String publicCursor = cursors.next(publicQuery, BASE.toInstant(ZoneOffset.UTC), id());

        problem(get("?limit=1&cursor=" + changed, token), 400, "CURSOR_INVALID");
        problem(get("?limit=2&cursor=" + cursor, token), 400, "CURSOR_INVALID");
        problem(get("?limit=1&cursor=" + cursor, token(account())), 400, "CURSOR_INVALID");
        problem(get("?limit=1&cursor=" + publicCursor, token), 400, "CURSOR_INVALID");
    }

    @Test
    void anAuthenticExpiredFeedCursorReturnsGone() throws Exception {
        var viewer = account();
        var clock = Clock.fixed(Instant.now().minusSeconds(cursorProperties.ttlSeconds() + 10L), ZoneOffset.UTC);
        var old = new PublicContentCursor(cursorProperties, json, clock);
        var query = old.openForFollowing(null, 1, viewer.getId());

        var cursor = old.nextForFollowing(query, query.snapshotAt(), id());

        problem(get("?limit=1&cursor=" + cursor, token(viewer)), 410, "CURSOR_EXPIRED");
    }

    @Test
    void followingFeedPermitsWriteBlockedViewersAndRequiresAValidAccount() throws Exception {
        var viewer = account();
        var author = account();
        follow(viewer, author);
        var card = publish(author, id(), BASE);
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", viewer.getId());
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", author.getId());
        String token = token(viewer);

        problem(get("", null), 401, "AUTHENTICATION_REQUIRED");
        assertEquals(List.of(card.id()), ids(page("", token)));
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", viewer.getId());
        problem(get("", token), 403, "ACCOUNT_RESTRICTED");
        jdbc.update("UPDATE app_user SET account_status='ACTIVE', email_verified=FALSE WHERE id=?", viewer.getId());
        problem(get("", token), 403, "PERMISSION_DENIED");
    }

    @Test
    void listQueryRejectsBadInputAndReturnsAnEmptyTerminalPageWhenFollowingNoOne() throws Exception {
        String token = token(account());
        problem(get("?limit=1&limit=2", token), 400, "MALFORMED_REQUEST");
        problem(get("?limit=abc", token), 400, "MALFORMED_REQUEST");
        problem(get("?authorId=other", token), 400, "MALFORMED_REQUEST");
        problem(send("GET", PATH, token, HttpRequest.BodyPublishers.ofString("{}")), 400, "MALFORMED_REQUEST");
        problem(get("?limit=0", token), 422, "VALIDATION_ERROR");
        problem(get("?limit=51", token), 422, "VALIDATION_ERROR");
        problem(get("?cursor=" + "a".repeat(4097), token), 422, "VALIDATION_ERROR");
        var page = page("", token);
        assertEquals(List.of(), ids(page));
        assertFalse(page.read("$.data.hasMore", Boolean.class));
        assertNull(page.read("$.data.nextCursor"));
    }

    @Test
    void anUnavailableBatchDependencyReturns503() throws Exception {
        var viewer = account();
        var author = account();
        follow(viewer, author);
        publish(author, id(), BASE);
        doThrow(new TransientDataAccessResourceException("injected private SQL failure"))
                .when(reads).findAssetsForRevisions(anyList());

        var response = get("", token(viewer));

        problem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("injected private"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "source"})
    void dedicatedFeedBudgetsDoNotConsumePublicBrowseQuota(String scope) throws Exception {
        var viewer = account();
        String token = token(viewer);
        boolean userQuota = scope.equals("user");
        String key = PREFIX + "{content-following-feed}:" + scope + ":" + TokenDigest.sha256(
                userQuota ? viewer.getId() : "127.0.0.1");
        redis.opsForValue().set(key, Integer.toString(userQuota ? USER_LIMIT : SOURCE_LIMIT), Duration.ofMinutes(1));

        var response = get("", token);

        problem(response, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
        var publicList = send("GET", "/api/v1/contents?limit=1", token, HttpRequest.BodyPublishers.noBody());
        assertEquals(200, publicList.statusCode(), publicList.body());
    }

    private UserAccount account() {
        var user = LoginTestSupport.account();
        user.setId(id());
        user.setEmail("feed-" + user.getId() + "@example.test");
        user.setEmailNormalized(user.getEmail());
        accounts.add(user.getId());
        users.insert(user);
        roles.insertDefaultRole(user.getId(), user.getCreatedAt());
        return user;
    }

    private void follow(UserAccount viewer, UserAccount author) {
        jdbc.update("INSERT INTO user_follow(id, follower_id, followed_id, created_at) VALUES (?, ?, ?, UTC_TIMESTAMP(6))",
                id(), viewer.getId(), author.getId());
    }

    private Seed publish(UserAccount author, String contentId, LocalDateTime firstPublishedAt) {
        jdbc.update("""
                INSERT INTO community_content(id, author_id, kind, lifecycle, publication_status, version, created_at, updated_at)
                VALUES (?, ?, 'ARTICLE', 'ACTIVE', 'UNPUBLISHED', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, contentId, author.getId());
        String revisionId = insertRevision(contentId, 1, "APPROVED", "公开文章");
        jdbc.update("""
                UPDATE community_content SET published_revision_id=?, publication_status='PUBLISHED',
                       first_published_at=?, published_at=? WHERE id=?
                """, revisionId, firstPublishedAt, firstPublishedAt, contentId);
        return new Seed(contentId, revisionId);
    }

    private String insertRevision(String contentId, int revision, String state, String title) {
        String revisionId = id();
        jdbc.update("""
                INSERT INTO content_revision(id, content_id, revision_number, status, title, body_markdown, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, '测试正文', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, revisionId, contentId, revision, state, title);
        return revisionId;
    }

    private String token(UserAccount user) {
        return sessions.create(user.getId(), user.getAuthVersion(), "following-feed-it").accessToken();
    }

    private HttpResponse<String> get(String query, String token) throws Exception {
        return send("GET", PATH + query, token, HttpRequest.BodyPublishers.noBody());
    }

    private HttpResponse<String> send(String method, String path, String token, HttpRequest.BodyPublisher body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).method(method, body);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private DocumentContext page(String query, String token) throws Exception {
        var response = get(query, token);
        assertEquals(200, response.statusCode(), response.body());
        return JsonPath.parse(response.body());
    }

    private static List<String> ids(DocumentContext page) {
        return page.read("$.data.items[*].id");
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.parse(response.body()).read("$.code", String.class));
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record Seed(String id, String revision) { }
}
