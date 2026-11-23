package com.freecontent.community.content.comment.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.common.pagination.CursorTokenCodec;
import com.freecontent.community.content.browse.config.PublicContentCursorProperties;
import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import com.freecontent.community.content.comment.cursor.ContentCommentsCursor;
import com.freecontent.community.content.comment.cursor.ContentCommentsCursor.Kind;
import com.freecontent.community.content.interaction.cursor.BookmarkCursor;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.PublicUserMapper;
import com.freecontent.community.user.mapper.UserAccountMapper;
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
class PublicCommentPageFlowIT {
    private static final String PREFIX = "test:root-comment-query:" + UUID.randomUUID() + ":";
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 3, 8, 0);
    private static final int USER_LIMIT = 8;
    private static final int SOURCE_LIMIT = 18;

    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PublicContentCursorProperties cursorProperties;
    @Autowired private BookmarkCursor bookmarkCursor;
    @Autowired private JsonMapper jsonMapper;
    @MockitoSpyBean private PublicContentMapper contents;
    @MockitoSpyBean private PublicUserMapper profiles;
    private final List<String> userIds = new ArrayList<>();
    private final List<String> commentIds = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.comment-query.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.comment-query.user-per-minute-limit", () -> USER_LIMIT);
        registry.add("app.content.comment-query.ip-per-minute-limit", () -> SOURCE_LIMIT);
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
    void guestsSeeOnlyTopLevelCommentsInDescendingOrder() throws Exception {
        var author = account();
        var reader = account();
        String contentId = publish(author);
        String older = root(contentId, author, BASE);
        String newer = root(contentId, reader, BASE.plusSeconds(1));
        insert(contentId, reader, older, older, "VISIBLE", "楼内回复", BASE.plusSeconds(2), id());

        var response = get(contentId, "", null);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var page = JsonPath.parse(response.body());
        assertEquals(List.of(newer, older), ids(page));
        assertEquals(List.of(0, 1), page.read("$.data.items[*].replyCount"));
        assertEquals(reader.getId(), page.read("$.data.items[0].author.id", String.class));
        assertFalse(page.read("$.data.hasMore", Boolean.class));
        assertNull(page.read("$.data.nextCursor"));
    }

    @Test
    void guestsSeeOnlyVisibleRepliesInTheirThreadInAscendingOrder() throws Exception {
        var author = account();
        var replier = account();
        String content = publish(author);
        String root = root(content, author, BASE);
        String first = insert(content, replier, root, root, "VISIBLE", "直接回复", BASE.plusSeconds(1), id());
        String nested = insert(content, author, root, first, "VISIBLE", "楼中楼", BASE.plusSeconds(3), id());
        insert(content, author, root, root, "HIDDEN", "隐藏回复", BASE.plusSeconds(2), id());
        String anotherRoot = root(content, author, BASE.plusSeconds(4));
        insert(content, replier, anotherRoot, anotherRoot, "VISIBLE", "其他楼层", BASE.plusSeconds(5), id());

        var response = getReplies(root, "", null);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var page = JsonPath.parse(response.body());
        assertEquals(List.of(first, nested), ids(page));
        assertEquals(root, page.read("$.data.items[1].rootCommentId", String.class));
        assertEquals(first, page.read("$.data.items[1].replyToCommentId", String.class));
        assertEquals(List.of(0, 0), page.read("$.data.items[*].replyCount"));
        assertFalse(page.read("$.data.hasMore", Boolean.class));
        assertNull(page.read("$.data.nextCursor"));
        var authors = List.of(replier.getId(), author.getId());
        verify(profiles).findByIds(authors);
        verify(contents).countVisibleByAuthors(authors);
        verify(profiles, never()).findById(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETED", "HIDDEN"})
    void placeholderRootKeepsVisibleRepliesAndDisappearsWithoutAny(String state) throws Exception {
        var author = account();
        String content = publish(author);
        String root = root(content, author, BASE);
        var empty = repliesPage(root, "", null);
        assertEquals(List.of(), ids(empty));
        assertNull(cursor(empty));

        String reply = insert(content, author, root, root, "VISIBLE", "已发布回复", BASE.plusSeconds(1), id());
        jdbc.update("UPDATE content_comment SET state=? WHERE id=?", state, root);
        assertEquals(List.of(reply), ids(repliesPage(root, "", null)));
        jdbc.update("UPDATE content_comment SET state='HIDDEN' WHERE id=?", reply);
        problem(getReplies(root, "", null), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void replyKeysetUsesAscendingIdForTiesAndTheOriginalSnapshot() throws Exception {
        var author = account();
        String content = publish(author);
        String root = root(content, author, BASE.minusSeconds(2));
        String older = insert(content, author, root, root, "VISIBLE", "最早", BASE.minusSeconds(1), id());
        String lower = insert(content, author, root, root, "VISIBLE", "同时间较小ID", BASE, "a_" + id());
        String higher = insert(content, author, root, root, "VISIBLE", "同时间较大ID", BASE, "z_" + id());
        String later = insert(content, author, root, higher, "VISIBLE", "后一个回复", BASE.plusSeconds(1), id());

        var first = repliesPage(root, "?limit=2", null);
        assertEquals(List.of(older, lower), ids(first));
        assertTrue(first.read("$.data.hasMore", Boolean.class));
        String snapshot = first.read("$.data.snapshotAt", String.class);
        jdbc.update("UPDATE content_comment SET state='HIDDEN' WHERE id=?", lower);
        var outside = LocalDateTime.ofInstant(Instant.parse(snapshot).plusSeconds(1), ZoneOffset.UTC);
        insert(content, author, root, root, "VISIBLE", "首批之后的回复", outside, id());

        var second = repliesPage(root, "?limit=2&cursor=" + cursor(first), null);
        assertEquals(List.of(higher, later), ids(second));
        assertEquals(snapshot, second.read("$.data.snapshotAt", String.class));
        assertFalse(second.read("$.data.hasMore", Boolean.class));
        assertNull(cursor(second));
    }

    @Test
    void rejectsNonRootOrMissingTargetsAndRechecksTheContentOnEveryPage() throws Exception {
        var author = account();
        String content = publish(author);
        String root = root(content, author, BASE);
        String leaf = insert(content, author, root, root, "VISIBLE", "回复", BASE.plusSeconds(1), id());
        insert(content, author, root, leaf, "VISIBLE", "楼中楼", BASE.plusSeconds(2), id());
        problem(getReplies(leaf, "", null), 404, "RESOURCE_NOT_FOUND");
        problem(getReplies(id(), "", null), 404, "RESOURCE_NOT_FOUND");
        var first = repliesPage(root, "?limit=1", null);
        assertEquals(List.of(leaf), ids(first));

        jdbc.update("UPDATE community_content SET publication_status='TAKEN_DOWN' WHERE id=?", content);
        problem(getReplies(root, "?limit=1&cursor=" + cursor(first), null), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void replyCursorsAreBoundToTheRouteRootUserAndLimit() throws Exception {
        var author = account();
        var other = account();
        String content = publish(author);
        String root = insert(content, author, null, null, "VISIBLE", "根评论", BASE, content);
        String anotherRoot = root(content, author, BASE.plusSeconds(1));
        insert(content, author, root, root, "VISIBLE", "第一页", BASE.plusSeconds(2), id());
        String remaining = insert(content, author, root, root, "VISIBLE", "第二页", BASE.plusSeconds(3), id());
        String token = token(author);
        String replies = cursor(repliesPage(root, "?limit=1", token));
        String roots = cursor(page(content, "?limit=1", token));
        String continuation = "?limit=1&cursor=" + replies;
        problem(getReplies(anotherRoot, continuation, token), 400, "CURSOR_INVALID");
        problem(getReplies(root, continuation, token(other)), 400, "CURSOR_INVALID");
        problem(getReplies(root, continuation, null), 400, "CURSOR_INVALID");
        problem(getReplies(root, "?limit=2&cursor=" + replies, token), 400, "CURSOR_INVALID");
        problem(getReplies(root, "?limit=1&cursor=" + roots, token), 400, "CURSOR_INVALID");
        problem(get(content, continuation, token), 400, "CURSOR_INVALID");
        assertEquals(List.of(remaining), ids(repliesPage(root, continuation, token(author))));
    }

    @Test
    void earlierIssuedTopLevelTokensRemainValidAndExpiredReplyTokensReturn410() throws Exception {
        var author = account();
        String content = publish(author);
        String older = root(content, author, BASE);
        String newer = root(content, author, BASE.plusSeconds(1));
        Instant snapshot = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String token = token(author);
        var legacy = new CursorTokenCodec(cursorProperties.keyBase64(),
                "v1:GET:/api/v1/contents/{contentId}/comments:createdAt,id:DESC", jsonMapper);
        String existing = legacy.encode(Map.of("contentId", content, "viewerId", author.getId(), "limit", 1,
                "snapshotAt", snapshot.toString(), "expiresAt", snapshot.plusSeconds(60).toString(),
                "lastAt", BASE.plusSeconds(1).toInstant(ZoneOffset.UTC).toString(), "lastId", newer));
        assertEquals(List.of(older), ids(page(content, "?limit=1&cursor=" + existing, token)));

        Clock past = Clock.fixed(snapshot.minusSeconds(cursorProperties.ttlSeconds() + 10L), ZoneOffset.UTC);
        var issuer = new ContentCommentsCursor(cursorProperties, jsonMapper, past);
        var query = issuer.open(null, older, null, 20, Kind.REPLIES);
        String expired = issuer.next(query, query.snapshotAt(), id());
        problem(getReplies(older, "?cursor=" + expired, null), 410, "CURSOR_EXPIRED");
    }

    @Test
    void replyRouteChecksOptionalCredentialsAndSharesTheReadBudget() throws Exception {
        var author = account();
        String content = publish(author);
        String root = root(content, author, BASE);
        String token = token(author);
        problem(getReplies(root, "", "invalid-token"), 401, "AUTHENTICATION_REQUIRED");
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", author.getId());
        assertEquals(200, getReplies(root, "", token).statusCode());
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", author.getId());
        problem(getReplies(root, "", token), 401, "AUTHENTICATION_REQUIRED");
        var key = quotaKey("source", "127.0.0.1");
        assertEquals("1", redis.opsForValue().get(key));
        problem(getReplies(root, "?unknown=1", null), 400, "MALFORMED_REQUEST");
        problem(getReplies(root, "?limit=51", null), 422, "VALIDATION_ERROR");
        assertEquals("1", redis.opsForValue().get(key));

        redis.opsForValue().set(key, Integer.toString(SOURCE_LIMIT), Duration.ofMinutes(1));
        var limited = getReplies(root, "", null);
        problem(limited, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()) > 0);
        problem(get(content, "", null), 429, "RATE_LIMITED");
    }

    @Test
    void keepsOnlyPlaceholdersWithVisibleRepliesBeforeApplyingThePageLimit() throws Exception {
        var author = account();
        var replier = account();
        String content = publish(author);
        String visible = root(content, author, BASE);
        String placeholder = insert(content, author, null, null, "DELETED", "被删除的正文", BASE.plusSeconds(1), id());
        String direct = insert(content, replier, placeholder, placeholder, "HIDDEN", "隐藏回复", BASE.plusSeconds(2), id());
        insert(content, replier, placeholder, direct, "VISIBLE", "仍可见的楼中楼", BASE.plusSeconds(3), id());
        String emptyHidden = insert(content, author, null, null, "HIDDEN", "不返回的楼层", BASE.plusSeconds(4), id());
        insert(content, replier, emptyHidden, emptyHidden, "HIDDEN", "仅有隐藏回复", BASE.plusSeconds(5), id());

        var first = page(content, "?limit=1", null);
        assertEquals(List.of(placeholder), ids(first));
        assertTrue(first.read("$.data.hasMore", Boolean.class));
        assertEquals("DELETED_PLACEHOLDER", first.read("$.data.items[0].state", String.class));
        assertNull(first.read("$.data.items[0].body"));
        assertNull(first.read("$.data.items[0].author"));
        assertEquals(1, first.read("$.data.items[0].replyCount", Integer.class));
        var second = page(content, "?limit=1&cursor=" + cursor(first), null);
        assertEquals(List.of(visible), ids(second));
        assertFalse(second.read("$.data.hasMore", Boolean.class));
        assertNull(second.read("$.data.nextCursor"));
    }

    @Test
    void tieBreakingAndSnapshotRemainStableAcrossPageChanges() throws Exception {
        var author = account();
        String content = publish(author);
        String higher = insert(content, author, null, null, "VISIBLE", "同一时间较大ID", BASE, "z_" + id());
        String lower = insert(content, author, null, null, "VISIBLE", "同一时间较小ID", BASE, "a_" + id());
        String older = root(content, author, BASE.minusSeconds(1));
        var first = page(content, "?limit=1", null);
        assertEquals(List.of(higher), ids(first));
        String snapshot = first.read("$.data.snapshotAt", String.class);

        var later = LocalDateTime.ofInstant(Instant.parse(snapshot).plusSeconds(1), ZoneOffset.UTC);
        root(content, author, later);
        jdbc.update("UPDATE content_comment SET state='HIDDEN' WHERE id IN (?,?)", higher, older);

        var last = page(content, "?limit=1&cursor=" + cursor(first), null);
        assertEquals(List.of(lower), ids(last));
        assertEquals(snapshot, last.read("$.data.snapshotAt", String.class));
        assertFalse(last.read("$.data.hasMore", Boolean.class));
        assertNull(last.read("$.data.nextCursor"));
    }

    @Test
    void cursorsRejectChangedScopePageSizeAndEndpointAndRejectTampering() throws Exception {
        var author = account();
        var other = account();
        String content = publish(author);
        String otherContent = publish(other);
        root(content, author, BASE);
        root(content, other, BASE.plusSeconds(1));
        String token = token(author);
        var first = page(content, "?limit=1", token);
        String cursor = cursor(first);
        String changed = cursor.substring(0, 3) + (cursor.charAt(3) == 'A' ? 'B' : 'A') + cursor.substring(4);
        var bookmarkQuery = bookmarkCursor.open(null, 1, author.getId());
        String foreign = bookmarkCursor.next(bookmarkQuery, bookmarkQuery.snapshotAt(), id());

        problem(get(content, "?limit=1&cursor=" + changed, token), 400, "CURSOR_INVALID");
        problem(get(otherContent, "?limit=1&cursor=" + cursor, token), 400, "CURSOR_INVALID");
        problem(get(content, "?limit=2&cursor=" + cursor, token), 400, "CURSOR_INVALID");
        problem(get(content, "?limit=1&cursor=" + cursor, token(other)), 400, "CURSOR_INVALID");
        problem(get(content, "?limit=1&cursor=" + cursor, null), 400, "CURSOR_INVALID");
        problem(get(content, "?limit=1&cursor=" + foreign, token), 400, "CURSOR_INVALID");
        assertEquals(1, ids(page(content, "?limit=1&cursor=" + cursor, token(author))).size());
    }

    @Test
    void authenticExpiredCursorReturns410() throws Exception {
        var author = account();
        String content = publish(author);
        Clock past = Clock.fixed(Instant.now().minusSeconds(cursorProperties.ttlSeconds() + 10L), ZoneOffset.UTC);
        var issuer = new ContentCommentsCursor(cursorProperties, jsonMapper, past);
        var query = issuer.open(null, content, null, 20);
        var expired = issuer.next(query, query.snapshotAt(), id());

        problem(get(content, "?cursor=" + expired, null), 410, "CURSOR_EXPIRED");
    }

    @Test
    void emptyPageDefaultAndMaximumAreBounded() throws Exception {
        var author = account();
        String content = publish(author);
        var empty = page(content, "", null);
        assertEquals(List.of(), ids(empty));
        assertFalse(empty.read("$.data.hasMore", Boolean.class));
        assertNull(empty.read("$.data.nextCursor"));
        for (int i = 0; i < 51; i++) root(content, author, BASE.plusSeconds(i));

        assertEquals(20, ids(page(content, "", null)).size());
        var max = page(content, "?limit=50", null);
        assertEquals(50, ids(max).size());
        var tail = page(content, "?limit=50&cursor=" + cursor(max), null);
        assertEquals(1, ids(tail).size());
        assertFalse(tail.read("$.data.hasMore", Boolean.class));
    }

    @Test
    void batchLoadsOnlyDistinctVisibleAuthorsFromTheReturnedPage() throws Exception {
        var author = account();
        var second = account();
        var beyond = account();
        String content = publish(author);
        root(content, author, BASE.plusSeconds(3));
        root(content, author, BASE.plusSeconds(2));
        root(content, second, BASE.plusSeconds(1));
        root(content, beyond, BASE);

        var first = page(content, "?limit=3", null);

        assertEquals(List.of(1, 1, 0), first.read("$.data.items[*].author.publishedContentCount"));
        assertTrue(first.read("$.data.hasMore", Boolean.class));
        var ids = List.of(author.getId(), second.getId());
        verify(profiles).findByIds(ids);
        verify(contents).countVisibleByAuthors(ids);
        verify(profiles, never()).findById(anyString());
        verify(contents, never()).countVisibleByAuthor(anyString());
    }

    @Test
    void missingHiddenOrInvalidPublishedRevisionReturns404() throws Exception {
        var author = account();
        String content = publish(author);
        root(content, author, BASE);
        problem(get(id(), "", null), 404, "RESOURCE_NOT_FOUND");
        jdbc.update("UPDATE community_content SET publication_status='WITHDRAWN' WHERE id=?", content);
        problem(get(content, "", null), 404, "RESOURCE_NOT_FOUND");
        jdbc.update("UPDATE community_content SET publication_status='PUBLISHED' WHERE id=?", content);
        jdbc.update("UPDATE content_revision SET status='REJECTED' WHERE content_id=?", content);
        problem(get(content, "", null), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void optionalAuthenticationRejectsInvalidTokensAndAllowsWriteBlockedReaders() throws Exception {
        var author = account();
        String content = publish(author);
        String token = token(author);
        problem(get(content, "", "invalid-token"), 401, "AUTHENTICATION_REQUIRED");
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", author.getId());
        assertEquals(200, get(content, "", token).statusCode());
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", author.getId());
        var rejected = get(content, "", token);
        problem(rejected, 401, "AUTHENTICATION_REQUIRED");
        assertTrue(rejected.headers().firstValue("WWW-Authenticate").orElseThrow().startsWith("Bearer"));
    }

    @Test
    void malformedRequestsAreRejectedBeforeTheQuotaIsConsumed() throws Exception {
        String pathId = id();
        problem(get("bad.id", "", null), 422, "VALIDATION_ERROR");
        problem(get(pathId, "?limit=0", null), 422, "VALIDATION_ERROR");
        problem(get(pathId, "?limit=51", null), 422, "VALIDATION_ERROR");
        problem(get(pathId, "?limit=word", null), 400, "MALFORMED_REQUEST");
        problem(get(pathId, "?limit=1&limit=2", null), 400, "MALFORMED_REQUEST");
        problem(get(pathId, "?unknown=1", null), 400, "MALFORMED_REQUEST");
        problem(get(pathId, "?cursor=" + "a".repeat(4097), null), 422, "VALIDATION_ERROR");
        var request = getRequest(path(pathId, ""), null).method("GET", HttpRequest.BodyPublishers.ofString("{}")).build();
        problem(http.send(request, HttpResponse.BodyHandlers.ofString()), 400, "MALFORMED_REQUEST");
        assertNull(redis.opsForValue().get(quotaKey("source", "127.0.0.1")));
    }

    @Test
    void listAndDetailShareTheSourceAndUserQuota() throws Exception {
        var author = account();
        var other = account();
        String content = publish(author);
        String root = root(content, author, BASE);
        String token = token(author);
        String detail = "/api/v1/comments/" + root;
        redis.opsForValue().set(quotaKey("user", author.getId()), Integer.toString(USER_LIMIT), Duration.ofMinutes(1));

        var limited = get(content, "", token);
        problem(limited, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()) > 0);
        problem(http.send(getRequest(detail, token).build(), HttpResponse.BodyHandlers.ofString()), 429, "RATE_LIMITED");
        assertEquals(200, get(content, "", null).statusCode());
        redis.opsForValue().set(quotaKey("source", "127.0.0.1"), Integer.toString(SOURCE_LIMIT), Duration.ofMinutes(1));
        problem(get(content, "", token(other)), 429, "RATE_LIMITED");
        problem(http.send(getRequest(detail, null).build(), HttpResponse.BodyHandlers.ofString()), 429, "RATE_LIMITED");
    }

    @Test
    void publicProjectionFailureReturns503RatherThanAnEmptyList() throws Exception {
        var author = account();
        String content = publish(author);
        root(content, author, BASE);
        doThrow(new TransientDataAccessResourceException("private SQL detail")).when(profiles).findByIds(anyList());

        var response = get(content, "", null);

        problem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("private SQL detail"));
    }

    @Test
    void generatedOpenApiDeclaresOptionalAuthenticationAndCommentPageCursorRules() throws Exception {
        var response = http.send(getRequest("/v3/api-docs", null).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        var doc = JsonPath.parse(response.body());
        String operation = "$.paths['/api/v1/contents/{contentId}/comments'].get";
        assertEquals("listContentComments", doc.read(operation + ".operationId", String.class));
        assertEquals(List.of(Map.of(), Map.of("bearerAuth", List.of())), doc.read(operation + ".security", List.class));
        String replies = "$.paths['/api/v1/comments/{commentId}/replies'].get";
        assertEquals("listCommentReplies", doc.read(replies + ".operationId", String.class));
        assertEquals(List.of(Map.of(), Map.of("bearerAuth", List.of())), doc.read(replies + ".security", List.class));
        String schema = "$.components.schemas.CommentPage";
        assertEquals(Set.of("items", "hasMore", "nextCursor", "snapshotAt"),
                Set.copyOf(doc.<List<String>>read(schema + ".required")));
        assertEquals("#/components/schemas/Comment", doc.read(schema + ".properties.items.items['$ref']", String.class));
        assertEquals(true, doc.read(schema + ".allOf[0].if.properties.hasMore.const", Boolean.class));
        assertEquals(1, doc.read(schema + ".allOf[0].then.properties.nextCursor.minLength", Integer.class));
        assertEquals("null", doc.read(schema + ".allOf[0].else.properties.nextCursor.type", String.class));
    }

    private UserAccount account() {
        var user = LoginTestSupport.account();
        user.setId(id());
        user.setEmail("root-comments-" + user.getId() + "@example.test");
        user.setEmailNormalized(user.getEmail());
        userIds.add(user.getId());
        users.insert(user);
        roles.insertDefaultRole(user.getId(), user.getCreatedAt());
        return user;
    }

    private String publish(UserAccount author) {
        String contentId = id();
        String revisionId = id();
        jdbc.update("""
                INSERT INTO community_content(id, author_id, kind, lifecycle, publication_status, version, created_at, updated_at)
                VALUES (?, ?, 'ARTICLE', 'ACTIVE', 'UNPUBLISHED', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, contentId, author.getId());
        jdbc.update("""
                INSERT INTO content_revision(id, content_id, revision_number, status, title, body_markdown, created_at, updated_at)
                VALUES (?, ?, 1, 'APPROVED', '顶级评论测试', '公开正文', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, revisionId, contentId);
        jdbc.update("""
                UPDATE community_content SET published_revision_id=?, publication_status='PUBLISHED',
                    first_published_at=UTC_TIMESTAMP(6), published_at=UTC_TIMESTAMP(6) WHERE id=?
                """, revisionId, contentId);
        return contentId;
    }

    private String root(String contentId, UserAccount author, LocalDateTime createdAt) {
        return insert(contentId, author, null, null, "VISIBLE", "可见根评论", createdAt, id());
    }

    private String insert(String contentId, UserAccount author, String rootId, String replyTo, String state,
                          String body, LocalDateTime createdAt, String id) {
        jdbc.update("""
                INSERT INTO content_comment(id, content_id, author_id, root_comment_id, reply_to_comment_id,
                                            body, state, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, contentId, author.getId(), rootId, replyTo, body, state, createdAt, createdAt);
        commentIds.add(id);
        return id;
    }

    private HttpResponse<String> get(String contentId, String query, String token) throws Exception {
        return http.send(getRequest(path(contentId, query), token).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getReplies(String root, String query, String token) throws Exception {
        return http.send(getRequest("/api/v1/comments/" + root + "/replies" + query, token).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder getRequest(String path, String token) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request;
    }

    private DocumentContext page(String contentId, String query, String token) throws Exception {
        var response = get(contentId, query, token);
        assertEquals(200, response.statusCode(), response.body());
        return JsonPath.parse(response.body());
    }

    private DocumentContext repliesPage(String rootId, String query, String token) throws Exception {
        var response = getReplies(rootId, query, token);
        assertEquals(200, response.statusCode(), response.body());
        return JsonPath.parse(response.body());
    }

    private String token(UserAccount user) {
        return sessions.create(user.getId(), user.getAuthVersion(), "root-comment-query-it").accessToken();
    }

    private static String path(String contentId, String query) {
        return "/api/v1/contents/" + contentId + "/comments" + query;
    }

    private static String quotaKey(String scope, String value) {
        return PREFIX + "{content-comment-query}:" + scope + ":" + TokenDigest.sha256(value);
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.parse(response.body()).read("$.code", String.class));
    }

    private static String cursor(DocumentContext page) {
        return page.read("$.data.nextCursor", String.class);
    }

    private static List<String> ids(DocumentContext page) {
        return page.read("$.data.items[*].id");
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
