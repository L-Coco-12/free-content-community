package com.freecontent.community.content.workspace.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.workspace.dto.CreateDraftRequest;
import com.freecontent.community.content.workspace.ratelimit.ContentDraftCreationRateLimiter;
import com.freecontent.community.content.workspace.service.transaction.ContentDraftCreationTransactionService;
import com.freecontent.community.content.workspace.mapper.ContentDraftCreationMapper;
import com.freecontent.community.content.workspace.mapper.ContentDraftCreationRequestMapper;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
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
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class ContentDraftCreationFlowIT {
    private static final String PREFIX = "test:draft-creation:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private StringRedisTemplate redis;
    @MockitoSpyBean private ContentDraftCreationMapper writes;
    @MockitoSpyBean private ContentDraftCreationRequestMapper requests;
    @MockitoSpyBean private ContentDraftCreationRateLimiter limiter;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @Autowired private ContentDraftCreationTransactionService transactions;
    @Autowired private JsonMapper json;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) {
        for (String scope : List.of("auth.login", "content.creation", "content.draft-creation")) {
            registry.add("app." + scope + ".redis-key-prefix", () -> PREFIX);
        }
    }

    @AfterEach
    void cleanup() {
        reset(redis, writes, requests, limiter, authentication);
        var keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) {
            jdbc.update("DELETE FROM content_creation_request WHERE created_by=?", owner);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                    + "AND table_name='content_draft_creation_request'", Integer.class) == 1) {
                jdbc.update("DELETE FROM content_draft_creation_request WHERE created_by=?", owner);
            }
            ContentTestSupport.clearReviews(jdbc, owner);
            ContentTestSupport.clearWorkspace(jdbc, owner);
        }
        for (String owner : owners) {
            jdbc.update("DELETE FROM community_tag WHERE created_by=?", owner);
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner);
            users.deleteById(owner);
        }
    }

    @Test
    void anExistingWorkingDraftIs409WithoutCreatingAnotherRevision() throws Exception {
        var author = author();
        var seed = content(author, "DRAFT");
        jdbc.update("UPDATE community_content SET working_revision_id=? WHERE id=?", seed.revision(), seed.content());
        problem(create(seed.content(), Map.of("expectedVersion", 7), id(), token(author)), 409, "DRAFT_ALREADY_EXISTS");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(7, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
    }

    @Test
    void copiesTheSelectedSnapshotAndReferencesIntoANewDraftWithoutChangingPublication() throws Exception {
        var author = author();
        var seed = content(author, "APPROVED");
        String later = id();
        insertRevision(seed.content(), later, 3, "CANCELLED", "较新历史标题", "较新历史正文");
        String first = asset(author), second = asset(author), tagOne = tag(author, "ACTIVE"), tagTwo = tag(author, "DISABLED");
        String markdown = "```md\n![code](asset:ignored)\n```\n\n![one](asset:" + first + ")\n![two](asset:" + second + ")";
        jdbc.update("UPDATE content_revision SET title=?,body_markdown=? WHERE id=?", "  历史标题😀  ", markdown, seed.revision());
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) "
                + "VALUES(?,?,'COVER',0),(?,?,'IMAGE',9),(?,?,'IMAGE',3)", seed.revision(), first, seed.revision(), first, seed.revision(), second);
        jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,4),(?,?,1)", seed.revision(), tagOne, seed.revision(), tagTwo);
        jdbc.update("UPDATE community_content SET published_revision_id=?,publication_status='PUBLISHED',"
                + "first_published_at='2026-11-03 08:00:00',published_at='2026-11-04 08:00:00' WHERE id=?", seed.revision(), seed.content());
        var before = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision());
        var publication = jdbc.queryForMap("SELECT published_revision_id,publication_status,first_published_at,published_at FROM community_content WHERE id=?", seed.content());
        var response = create(seed.content(), Map.of("expectedVersion", 7, "sourceRevisionId", seed.revision()), id(), token(author));
        assertEquals(201, response.statusCode(), response.body());
        assertEquals(seed.content(), JsonPath.read(response.body(), "$.data.id"));
        assertEquals(8, JsonPath.<Integer>read(response.body(), "$.data.version"));
        assertEquals("PUBLISHED", JsonPath.read(response.body(), "$.data.publicationStatus"));
        assertEquals(seed.revision(), JsonPath.read(response.body(), "$.data.publishedRevision.id"));
        assertNull(JsonPath.read(response.body(), "$.data.activeReviewId"));
        Map<String, Object> draft = JsonPath.read(response.body(), "$.data.workingRevision");
        assertNotEquals(seed.revision(), draft.get("id"));
        assertNotEquals(later, draft.get("id"));
        assertEquals(4, draft.get("revisionNumber"));
        assertEquals("DRAFT", draft.get("status"));
        assertEquals("  历史标题😀  ", draft.get("title"));
        assertEquals(markdown, draft.get("bodyMarkdown"));
        assertEquals(first, draft.get("coverAssetId"));
        assertEquals(List.of(second, first), draft.get("imageAssetIds"));
        assertEquals(List.of(tagTwo, tagOne), draft.get("tagIds"));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision()));
        assertEquals(publication, jdbc.queryForMap("SELECT published_revision_id,publication_status,first_published_at,published_at FROM community_content WHERE id=?", seed.content()));
        assertEquals(List.of(3, 9), jdbc.queryForList("SELECT position FROM content_revision_asset WHERE revision_id=? AND usage_type='IMAGE' ORDER BY position", Integer.class, draft.get("id")));
        assertEquals("/api/v1/creator/contents/" + seed.content() + "/revisions/" + draft.get("id"), response.headers().firstValue("Location").orElseThrow());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=? AND status='DRAFT'", Integer.class, seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED", "CANCELLED"})
    void omittedSourceCopiesTheHighestRevisionNumberAcrossTerminalStates(String state) throws Exception {
        var author = author();
        var seed = content(author, "APPROVED");
        String latest = id();
        insertRevision(seed.content(), latest, 5, state, "最新" + state, "最新正文");
        var response = create(seed.content(), Map.of("expectedVersion", 7), id(), token(author));
        assertEquals(201, response.statusCode(), response.body());
        assertEquals("最新" + state, JsonPath.read(response.body(), "$.data.workingRevision.title"));
        assertEquals(6, JsonPath.<Integer>read(response.body(), "$.data.workingRevision.revisionNumber"));
        assertEquals(state, jdbc.queryForObject("SELECT status FROM content_revision WHERE id=?", String.class, latest));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAITING_UPLOAD", "PROCESSING", "FAILED", "READY"})
    void copyingDraftReferencesDoesNotRequireReadyMediaOrActiveTags(String state) throws Exception {
        var author = author();
        var seed = content(author, "REJECTED");
        String image = asset(author), tag = tag(author, "DISABLED");
        jdbc.update("UPDATE media_asset SET state=? WHERE id=?", state, image);
        jdbc.update("UPDATE content_revision SET body_markdown=? WHERE id=?", "![image](asset:" + image + ")", seed.revision());
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'IMAGE',0)", seed.revision(), image);
        jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,0)", seed.revision(), tag);
        var response = create(seed.content(), Map.of("expectedVersion", 7, "sourceRevisionId", seed.revision()), id(), token(author));
        assertEquals(201, response.statusCode(), response.body());
        assertEquals(List.of(image), JsonPath.read(response.body(), "$.data.workingRevision.imageAssetIds"));
        assertEquals(List.of(tag), JsonPath.read(response.body(), "$.data.workingRevision.tagIds"));
        assertEquals(state, jdbc.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class, image));
    }

    @Test
    void successfulRetriesReturnTheOriginalSnapshotEvenAfterTheDraftHasChanged() throws Exception {
        var author = author();
        var seed = content(author, "REJECTED");
        String key = id(), token = token(author);
        var body = Map.<String, Object>of("expectedVersion", 7);
        var first = create(seed.content(), body, key, token);
        assertEquals(201, first.statusCode(), first.body());
        String draft = JsonPath.read(first.body(), "$.data.workingRevision.id");
        jdbc.update("UPDATE content_revision SET title='后来编辑的标题' WHERE id=?", draft);
        jdbc.update("UPDATE community_content SET version=version+1 WHERE id=?", seed.content());
        var replay = create(seed.content(), body, key, token);
        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(JsonPath.<Map<String, Object>>read(first.body(), "$.data"), JsonPath.<Map<String, Object>>read(replay.body(), "$.data"));
        assertEquals(first.headers().firstValue("Location"), replay.headers().firstValue("Location"));
        assertEquals("后来编辑的标题", jdbc.queryForObject("SELECT title FROM content_revision WHERE id=?", String.class, draft));
        assertEquals(9, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"VERSION", "SOURCE", "PATH"})
    void theSameKeyCannotBeReusedForAnotherNormalizedRequest(String change) throws Exception {
        var author = author();
        var seed = content(author, "REJECTED");
        String key = id(), token = token(author);
        assertEquals(201, create(seed.content(), Map.of("expectedVersion", 7), key, token).statusCode());
        String target = seed.content();
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("expectedVersion", 7);
        switch (change) {
            case "VERSION" -> body.put("expectedVersion", 8);
            case "SOURCE" -> body.put("sourceRevisionId", seed.revision());
            default -> target = content(author, "CANCELLED").content();
        }
        problem(create(target, body, key, token), 409, "IDEMPOTENCY_KEY_REUSED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOURCE", "USER"})
    void exhaustedDraftCreationBudgetsDoNotCreateAnythingAndReleaseTheClaim(String scope) throws Exception {
        var author = author();
        var seed = content(author, "REJECTED");
        String key = scope.equals("SOURCE") ? sourceKey() : userKey(author.getId());
        redis.opsForValue().set(key, scope.equals("SOURCE") ? "60" : "100", Duration.ofHours(1));
        var response = create(seed.content(), Map.of("expectedVersion", 7), id(), token(author));
        problem(response, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(7, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_draft_creation_request WHERE created_by=?", Integer.class, author.getId()));
        verifyNoInteractions(writes);
    }

    @Test
    void successfulReplayIsFreeEvenWhenTheQuotaIsNowExhausted() throws Exception {
        var author = author();
        var seed = content(author, "CANCELLED");
        String key = id(), token = token(author);
        var body = Map.<String, Object>of("expectedVersion", 7);
        var first = create(seed.content(), body, key, token);
        assertEquals(201, first.statusCode(), first.body());
        assertEquals("1", redis.opsForValue().get(sourceKey()));
        assertEquals("1", redis.opsForValue().get(userKey(author.getId())));
        redis.opsForValue().set(sourceKey(), "60", Duration.ofMinutes(1));
        redis.opsForValue().set(userKey(author.getId()), "100", Duration.ofHours(1));
        var replay = create(seed.content(), body, key, token);
        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(JsonPath.<Map<String, Object>>read(first.body(), "$.data"), JsonPath.<Map<String, Object>>read(replay.body(), "$.data"));
        assertEquals("60", redis.opsForValue().get(sourceKey()));
        assertEquals("100", redis.opsForValue().get(userKey(author.getId())));
    }

    @Test
    void creatingContentAndCreatingADraftHaveIndependentQuotaAndKeyNamespaces() throws Exception {
        var author = author();
        var seed = content(author, "APPROVED");
        String key = id(), token = token(author);
        assertEquals(201, create(seed.content(), Map.of("expectedVersion", 7), key, token).statusCode());
        redis.opsForValue().set(userKey(author.getId()), "100", Duration.ofHours(1));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/creator/contents"))
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"kind\":\"ARTICLE\"}")).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), response.body());
        assertEquals("1", redis.opsForValue().get(PREFIX + "{content-create}:user:" + TokenDigest.sha256(author.getId())));
        assertEquals("100", redis.opsForValue().get(userKey(author.getId())));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_creation_request WHERE created_by=?", Integer.class, author.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_draft_creation_request WHERE created_by=?", Integer.class, author.getId()));
    }

    private String sourceKey() { return PREFIX + "{content-draft-create}:source:" + TokenDigest.sha256("127.0.0.1"); }
    private String userKey(String user) { return PREFIX + "{content-draft-create}:user:" + TokenDigest.sha256(user); }

    @ParameterizedTest
    @ValueSource(strings = {"UNPUBLISHED", "PUBLISHED", "WITHDRAWN", "TAKEN_DOWN"})
    void creatingADraftKeepsEveryExistingPublicationState(String state) throws Exception {
        var author = author();
        var seed = content(author, state.equals("UNPUBLISHED") ? "CANCELLED" : "APPROVED");
        if (!state.equals("UNPUBLISHED")) jdbc.update("UPDATE community_content SET publication_status=?,published_revision_id=?,"
                + "first_published_at='2026-11-03 08:00:00',published_at='2026-11-04 08:00:00' WHERE id=?", state, seed.revision(), seed.content());
        var before = jdbc.queryForMap("SELECT publication_status,published_revision_id,first_published_at,published_at FROM community_content WHERE id=?", seed.content());
        var response = create(seed.content(), Map.of("expectedVersion", 7), id(), token(author));
        assertEquals(201, response.statusCode(), response.body());
        assertEquals(state, JsonPath.read(response.body(), "$.data.publicationStatus"));
        assertEquals(before, jdbc.queryForMap("SELECT publication_status,published_revision_id,first_published_at,published_at FROM community_content WHERE id=?", seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_REVIEW", "ORPHAN_DRAFT", "DELETED", "STALE", "MAX_CONTENT", "MAX_REVISION", "MISSING_CONTENT", "FOREIGN_CONTENT", "FOREIGN_SOURCE", "OWN_OTHER_SOURCE"})
    void conflictingOrInvisibleTargetsDoNotChangeBusinessData(String change) throws Exception {
        var author = author();
        var seed = content(author, change.equals("IN_REVIEW") ? "IN_REVIEW" : change.equals("ORPHAN_DRAFT") ? "DRAFT" : "REJECTED");
        String target = seed.content(), source = seed.revision();
        int version = 7, status = 409;
        String code;
        switch (change) {
            case "IN_REVIEW" -> {
                String review = ContentTestSupport.pending(jdbc, target, source);
                jdbc.update("UPDATE community_content SET working_revision_id=?,active_review_id=? WHERE id=?", source, review, target);
                code = "DRAFT_ALREADY_EXISTS";
            }
            case "ORPHAN_DRAFT" -> code = "DRAFT_ALREADY_EXISTS";
            case "DELETED" -> { jdbc.update("UPDATE community_content SET lifecycle='DELETED' WHERE id=?", target); code = "CONTENT_NOT_EDITABLE"; }
            case "STALE" -> { version = 6; code = "CONTENT_VERSION_CONFLICT"; }
            case "MAX_CONTENT" -> { version = Integer.MAX_VALUE; jdbc.update("UPDATE community_content SET version=? WHERE id=?", version, target); code = "CONTENT_VERSION_CONFLICT"; }
            case "MAX_REVISION" -> { jdbc.update("UPDATE content_revision SET revision_number=? WHERE id=?", Integer.MAX_VALUE, source); code = "CONTENT_VERSION_CONFLICT"; }
            case "MISSING_CONTENT" -> { target = id(); code = "RESOURCE_NOT_FOUND"; status = 404; }
            case "FOREIGN_CONTENT" -> { target = content(author(), "REJECTED").content(); code = "RESOURCE_NOT_FOUND"; status = 404; }
            case "FOREIGN_SOURCE" -> { source = content(author(), "REJECTED").revision(); code = "RESOURCE_NOT_FOUND"; status = 404; }
            default -> { source = content(author, "REJECTED").revision(); code = "RESOURCE_NOT_FOUND"; status = 404; }
        }
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        var response = create(target, Map.of("expectedVersion", version, "sourceRevisionId", source), id(), token(author));
        problem(response, status, code);
        if (code.equals("RESOURCE_NOT_FOUND")) assertFalse(JsonPath.<Map<String, Object>>read(response.body(), "$").containsKey("currentVersion"));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_draft_creation_request WHERE created_by=?", Integer.class, author.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT", "ASSETS", "TAGS", "ATTACH", "COMPLETE"})
    void writeFailuresRollBackTheDraftReferencesVersionAndSuccessRecord(String point) throws Exception {
        var author = author();
        var seed = content(author, "REJECTED");
        String asset = asset(author), tag = tag(author, "ACTIVE"), key = id(), token = token(author);
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0)", seed.revision(), asset);
        jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,0)", seed.revision(), tag);
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        var failure = new TransientDataAccessResourceException("sensitive SQL detail");
        switch (point) {
            case "INSERT" -> doThrow(failure).when(writes).insertRevision(any(), any());
            case "ASSETS" -> doThrow(failure).when(writes).copyAssets(anyString(), anyString());
            case "TAGS" -> doThrow(failure).when(writes).copyTags(anyString(), anyString());
            case "ATTACH" -> doReturn(0).when(writes).attachDraft(anyString(), anyString(), anyInt(), anyString(), any());
            default -> doReturn(0).when(requests).complete(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        }
        var result = create(seed.content(), Map.of("expectedVersion", 7), key, token);
        problem(result, point.equals("ATTACH") || point.equals("COMPLETE") ? 409 : 503,
                point.equals("ATTACH") ? "CONTENT_VERSION_CONFLICT" : point.equals("COMPLETE") ? "REQUEST_IN_PROGRESS" : "DEPENDENCY_UNAVAILABLE");
        assertFalse(result.body().contains("sensitive"));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_draft_creation_request WHERE created_by=?", Integer.class, author.getId()));
        reset(writes, requests);
        assertEquals(201, create(seed.content(), Map.of("expectedVersion", 7), key, token).statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SAME_KEY", "DIFFERENT_KEYS"})
    void concurrentRequestsNeverCreateTwoWorkingDrafts(String mode) throws Exception {
        var author = author();
        var seed = content(author, "REJECTED");
        String token = token(author), shared = id();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> work = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                String key = mode.equals("SAME_KEY") ? shared : id();
                work.add(pool.submit(() -> { start.await(); return create(seed.content(), Map.of("expectedVersion", 7), key, token); }));
            }
            start.countDown();
            var first = work.get(0).get(30, TimeUnit.SECONDS);
            var second = work.get(1).get(30, TimeUnit.SECONDS);
            assertTrue(first.statusCode() == 201 || second.statusCode() == 201);
            for (var response : List.of(first, second)) {
                if (response.statusCode() != 201) problem(response, 409, mode.equals("SAME_KEY") ? "REQUEST_IN_PROGRESS" : "CONTENT_VERSION_CONFLICT");
            }
            if (mode.equals("SAME_KEY")) assertEquals(201, create(seed.content(), Map.of("expectedVersion", 7), shared, token).statusCode());
            else assertEquals(1, List.of(first, second).stream().filter(value -> value.statusCode() == 201).count());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=? AND status IN ('DRAFT','IN_REVIEW')", Integer.class, seed.content()));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, seed.content()));
            assertEquals(8, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_draft_creation_request WHERE created_by=? AND state='SUCCEEDED'", Integer.class, author.getId()));
        } finally { pool.shutdownNow(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUSY", "EXPIRED_LEASE"})
    void activeLeasesBlockRetriesWhileExpiredLeasesCanBeResumed(String mode) throws Exception {
        var author = author();
        var seed = content(author, "CANCELLED");
        String key = id(), token = token(author);
        var body = Map.<String, Object>of("expectedVersion", 7);
        var claim = reserve(seed.content(), body, key, token);
        if (mode.equals("EXPIRED_LEASE")) jdbc.update("UPDATE content_draft_creation_request SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", claim.request().getId());
        var response = create(seed.content(), body, key, token);
        if (mode.equals("BUSY")) {
            problem(response, 409, "REQUEST_IN_PROGRESS");
            assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
            assertNull(redis.opsForValue().get(sourceKey()));
            assertEquals(claim.request().getLeaseToken(), jdbc.queryForObject("SELECT lease_token FROM content_draft_creation_request WHERE id=?", String.class, claim.request().getId()));
        } else {
            assertEquals(201, response.statusCode(), response.body());
            assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT state FROM content_draft_creation_request WHERE id=?", String.class, claim.request().getId()));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, seed.content()));
        }
    }

    @Test
    void anExpired24HourKeyCanStartANewIntentWithoutDeletingOldBusinessData() throws Exception {
        var author = author();
        var firstContent = content(author, "REJECTED");
        var nextContent = content(author, "CANCELLED");
        String key = id(), token = token(author);
        assertEquals(201, create(firstContent.content(), Map.of("expectedVersion", 7), key, token).statusCode());
        jdbc.update("UPDATE content_draft_creation_request SET created_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 25 HOUR),"
                + "expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR) WHERE created_by=? AND key_hash=?", author.getId(), TokenDigest.sha256(key));
        var next = create(nextContent.content(), Map.of("expectedVersion", 7), key, token);
        assertEquals(201, next.statusCode(), next.body());
        assertEquals(nextContent.content(), jdbc.queryForObject("SELECT content_id FROM content_draft_creation_request WHERE created_by=? AND key_hash=?", String.class, author.getId(), TokenDigest.sha256(key)));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, firstContent.content()));
        assertEquals(8, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, firstContent.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TOKEN_CHANGED", "LEASE_EXPIRED"})
    void staleClaimsCannotFinishOrReleaseSomebodyElsesReservation(String change) throws Exception {
        var author = author();
        var seed = content(author, "REJECTED");
        String replacement = id(), key = id();
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            call.callRealMethod();
            if (change.equals("TOKEN_CHANGED")) jdbc.update("UPDATE content_draft_creation_request SET lease_token=? WHERE created_by=?", replacement, author.getId());
            else jdbc.update("UPDATE content_draft_creation_request SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE created_by=?", author.getId());
            return null;
        }).when(limiter).acquire(eq(author.getId()), anyString());
        problem(create(seed.content(), Map.of("expectedVersion", 7), key, token(author)), 409, "REQUEST_IN_PROGRESS");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(7, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
        if (change.equals("TOKEN_CHANGED")) assertEquals(replacement, jdbc.queryForObject("SELECT lease_token FROM content_draft_creation_request WHERE created_by=?", String.class, author.getId()));
        else assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_draft_creation_request WHERE created_by=?", Integer.class, author.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTH_VERSION", "UNVERIFIED", "DISABLED", "WRITE_BLOCKED", "NO_USER"})
    void creationEligibilityIsRecheckedAfterClaimingTheLease(String change) throws Exception {
        var author = author();
        var seed = content(author, "CANCELLED");
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            call.callRealMethod();
            changeEligibility(author.getId(), change);
            return null;
        }).when(limiter).acquire(eq(author.getId()), anyString());
        var response = create(seed.content(), Map.of("expectedVersion", 7), id(), token(author));
        problem(response, change.equals("AUTH_VERSION") ? 401 : 403,
                change.equals("AUTH_VERSION") ? "AUTHENTICATION_REQUIRED" : change.equals("UNVERIFIED") || change.equals("NO_USER") ? "PERMISSION_DENIED" : "ACCOUNT_RESTRICTED");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_draft_creation_request WHERE created_by=?", Integer.class, author.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTH_VERSION", "UNVERIFIED", "DISABLED", "WRITE_BLOCKED", "NO_USER"})
    void creationEligibilityIsRecheckedAfterFilterAuthentication(String change) throws Exception {
        var author = author();
        var seed = content(author, "CANCELLED");
        String token = token(author);
        doAnswer(call -> {
            var principal = (SessionPrincipal) call.callRealMethod();
            changeEligibility(author.getId(), change);
            return principal;
        }).when(authentication).authenticate(token);
        problem(create(seed.content(), Map.of("expectedVersion", 7), id(), token), change.equals("AUTH_VERSION") ? 401 : 403,
                change.equals("AUTH_VERSION") ? "AUTHENTICATION_REQUIRED" : change.equals("UNVERIFIED") || change.equals("NO_USER") ? "PERMISSION_DENIED" : "ACCOUNT_RESTRICTED");
        assertNull(redis.opsForValue().get(sourceKey()));
        verifyNoInteractions(limiter, writes, requests);
    }

    private void changeEligibility(String actor, String change) {
        switch (change) {
            case "AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", actor);
            case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=false WHERE id=?", actor);
            case "NO_USER" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=?", actor);
            default -> jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", change, actor);
        }
    }

    private ContentDraftCreationTransactionService.Claim reserve(String content, Map<String, Object> body, String key, String token) {
        var normalized = new java.util.LinkedHashMap<String, Object>();
        normalized.put("contentId", content); normalized.put("expectedVersion", body.get("expectedVersion"));
        normalized.put("sourceRevisionId", body.get("sourceRevisionId"));
        return transactions.claim(content, json.convertValue(body, CreateDraftRequest.class), TokenDigest.sha256(key),
                TokenDigest.sha256(json.writeValueAsString(normalized)), authentication.authenticate(token));
    }

    @Test
    void corruptReplaySnapshotsFailClosedWithoutWritingAnotherDraft() throws Exception {
        var author = author();
        var seed = content(author, "REJECTED");
        String key = id(), token = token(author);
        assertEquals(201, create(seed.content(), Map.of("expectedVersion", 7), key, token).statusCode());
        jdbc.update("UPDATE content_draft_creation_request SET result_snapshot='{}' WHERE created_by=?", author.getId());
        problem(create(seed.content(), Map.of("expectedVersion", 7), key, token), 503, "DEPENDENCY_UNAVAILABLE");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, seed.content()));
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT state FROM content_draft_creation_request WHERE created_by=?", String.class, author.getId()));
        assertEquals("1", redis.opsForValue().get(sourceKey()));
    }

    @Test
    void missingDefaultSourceDataIs503AndCannotCreateAnEmptyReplacement() throws Exception {
        var author = author();
        var seed = content(author, "CANCELLED");
        jdbc.update("DELETE FROM content_revision WHERE id=?", seed.revision());
        problem(create(seed.content(), Map.of("expectedVersion", 7), id(), token(author)), 503, "DEPENDENCY_UNAVAILABLE");
        assertNull(jdbc.queryForObject("SELECT working_revision_id FROM community_content WHERE id=?", String.class, seed.content()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_draft_creation_request WHERE created_by=?", Integer.class, author.getId()));
    }

    @Test
    void redisFailureReleasesTheClaimAndDoesNotWriteBusinessData() throws Exception {
        var author = author();
        var seed = content(author, "REJECTED");
        doThrow(new RedisConnectionFailureException("sensitive Redis detail")).when(limiter).acquire(anyString(), anyString());
        var response = create(seed.content(), Map.of("expectedVersion", 7), id(), token(author));
        problem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_draft_creation_request WHERE created_by=?", Integer.class, author.getId()));
    }

    @Test
    void videoReferencesAreCopiedWithoutChangingMediaStateOrCreatingAReview() throws Exception {
        var author = author();
        var seed = content(author, "CANCELLED");
        String video = asset(author);
        jdbc.update("UPDATE community_content SET kind='VIDEO' WHERE id=?", seed.content());
        jdbc.update("UPDATE media_asset SET scene='CONTENT_VIDEO',state='PROCESSING',declared_content_type='video/mp4' WHERE id=?", video);
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'VIDEO',0)", seed.revision(), video);
        var response = create(seed.content(), Map.of("expectedVersion", 7), id(), token(author));
        assertEquals(201, response.statusCode(), response.body());
        assertEquals(video, JsonPath.read(response.body(), "$.data.workingRevision.videoAssetId"));
        assertEquals("VIDEO", JsonPath.read(response.body(), "$.data.kind"));
        assertEquals("PROCESSING", jdbc.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class, video));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_review WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_outbox WHERE content_id=?", Integer.class, seed.content()));
    }

    @Test
    void aSourceWithSomebodyElsesMediaCannotBeUsedToBypassOwnership() throws Exception {
        var author = author();
        var seed = content(author, "REJECTED");
        String foreign = asset(author());
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0)", seed.revision(), foreign);
        problem(create(seed.content(), Map.of("expectedVersion", 7), id(), token(author)), 404, "RESOURCE_NOT_FOUND");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, seed.content()));
    }

    @Test
    void openApiMatchesThePrivateDraftCreationContract() throws Exception {
        var result = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        Map<String, Object> operation = JsonPath.read(result.body(), "$.paths['/api/v1/creator/contents/{contentId}/drafts'].post");
        assertEquals("createContentDraft", operation.get("operationId"));
        assertEquals(List.of(Map.of("bearerAuth", List.of())), operation.get("security"));
        assertEquals(java.util.Set.of("201", "400", "401", "403", "404", "409", "413", "415", "422", "429", "500", "503"),
                ((Map<?, ?>) operation.get("responses")).keySet());
        Map<String, Object> schema = JsonPath.read(result.body(), "$.components.schemas.CreateDraftRequest");
        assertEquals(java.util.Set.of("expectedVersion", "sourceRevisionId"), ((Map<?, ?>) schema.get("properties")).keySet());
        assertEquals(List.of("expectedVersion"), schema.get("required"));
        assertEquals(false, schema.get("additionalProperties"));
    }

    private String asset(UserAccount owner) {
        String asset = id();
        jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) "
                + "VALUES(?,?,'CONTENT_IMAGE','test.png','image/png',1,?,'READY',UTC_TIMESTAMP(6))", asset, owner.getId(), "private/" + owner.getId() + "/" + asset + "/source");
        return asset;
    }

    private String tag(UserAccount owner, String status) {
        String tag = id();
        jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) VALUES(?,?,'说明',?,1,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                tag, "tag_" + tag.substring(0, 16), status, owner.getId());
        return tag;
    }

    private Seed content(UserAccount owner, String status) {
        String content = id(), revision = id();
        jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) "
                + "VALUES(?,?,'ARTICLE','ACTIVE','UNPUBLISHED',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", content, owner.getId());
        insertRevision(content, revision, 1, status, "历史标题", "历史正文");
        return new Seed(content, revision);
    }

    private void insertRevision(String content, String revision, int number, String status, String title, String body) {
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", revision, content, number, status, title, body);
    }

    private UserAccount author() {
        var author = LoginTestSupport.account();
        author.setId(id());
        author.setEmail("draft-creation-" + author.getId() + "@example.test");
        author.setEmailNormalized(author.getEmail());
        users.insert(author);
        owners.add(author.getId());
        roles.insertDefaultRole(author.getId(), author.getCreatedAt());
        return author;
    }

    private String token(UserAccount author) { return sessions.create(author.getId(), 1, "draft-creation-test").accessToken(); }

    private HttpResponse<String> create(String content, Map<String, Object> body, String key, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/creator/contents/" + content + "/drafts"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertFalse(JsonPath.<Map<String, Object>>read(response.body(), "$").containsKey("data"));
    }

    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private record Seed(String content, String revision) { }
}
