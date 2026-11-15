package com.freecontent.community.content.workspace.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.workspace.mapper.ContentRepublicationMapper;
import com.freecontent.community.content.mapper.ContentReadMapper;
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
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class ContentRepublicationFlowIT {
    private static final String PREFIX = "test:content-republication:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private StringRedisTemplate redis;
    @MockitoSpyBean private ContentRepublicationMapper writes;
    @MockitoSpyBean private ContentReadMapper reads;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @Autowired private JsonMapper json;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) {
        for (String scope : List.of("auth.login", "content.publication-republish", "content.publication-withdrawal", "content.public-query", "content.query", "user.public-query",
                "content.review-decision", "content.draft-creation")) {
            registry.add("app." + scope + ".redis-key-prefix", () -> PREFIX);
        }
    }

    @AfterEach
    void cleanup() {
        reset(redis, writes, reads, authentication);
        var keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) {
            jdbc.update("DELETE FROM content_review_decision_request WHERE created_by=?", owner);
            jdbc.update("DELETE FROM content_draft_creation_request WHERE created_by=?", owner);
            for (String table : List.of("content_review_decision_request", "content_review_decision_audit"))
                jdbc.update("DELETE FROM " + table + " WHERE content_id IN (SELECT id FROM community_content WHERE author_id=?)", owner);
            for (String table : List.of("content_publication_republish_audit", "content_publication_outbox", "content_publication_withdrawal_audit")) {
                if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", Integer.class, table) == 1) {
                    jdbc.update("DELETE FROM " + table + " WHERE content_id IN (SELECT id FROM community_content WHERE author_id=?)", owner);
                }
            }
            ContentTestSupport.clearReviews(jdbc, owner);
            ContentTestSupport.clearWorkspace(jdbc, owner);
        }
        for (String owner : owners) {
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM community_tag WHERE created_by=?", owner);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner);
            users.deleteById(owner);
        }
    }

    @Test
    void withdrawnApprovedContentIsRepublishedWithANewVersionAndCurrentPublicationTime() throws Exception {
        var author = author();
        var seed = withdrawn(author);
        var before = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision());
        var dates = jdbc.queryForMap("SELECT first_published_at,published_at FROM community_content WHERE id=?", seed.content());
        var response = republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", seed.revision()), token(author));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("PUBLISHED", JsonPath.read(response.body(), "$.data.publicationStatus"));
        assertEquals(9, JsonPath.<Integer>read(response.body(), "$.data.version"));
        assertEquals(seed.revision(), JsonPath.read(response.body(), "$.data.publishedRevision.id"));
        assertEquals("APPROVED", JsonPath.read(response.body(), "$.data.publishedRevision.status"));
        assertNull(JsonPath.read(response.body(), "$.data.workingRevision"));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision()));
        var after = jdbc.queryForMap("SELECT first_published_at,published_at FROM community_content WHERE id=?", seed.content());
        assertEquals(dates.get("first_published_at"), after.get("first_published_at"));
        assertTrue(((java.time.LocalDateTime) after.get("published_at")).isAfter((java.time.LocalDateTime) dates.get("published_at")));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void alreadyPublishedLatestApprovalIgnoresOldVersionAndReturnsCurrentWorkspaceWithoutMutation() throws Exception {
        var author = author();
        var seed = withdrawn(author);
        jdbc.update("UPDATE community_content SET publication_status='PUBLISHED' WHERE id=?", seed.content());
        String draft = id();
        insertRevision(seed.content(), draft, 2, "DRAFT", "新草稿", "不能覆盖");
        jdbc.update("UPDATE community_content SET working_revision_id=? WHERE id=?", draft, seed.content());
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        var response = republish(seed.content(), Map.of("expectedVersion", 1, "revisionId", seed.revision()), token(author));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(8, JsonPath.<Integer>read(response.body(), "$.data.version"));
        assertEquals(draft, JsonPath.read(response.body(), "$.data.workingRevision.id"));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WITHDRAWN", "PUBLISHED"})
    void anOlderApprovalCannotBeSelectedEvenWhenItIsTheCurrentPublication(String status) throws Exception {
        var author = author(); var seed = withdrawn(author);
        insertRevision(seed.content(), id(), 2, "APPROVED", "最近批准稿", "最新批准正文");
        jdbc.update("UPDATE community_content SET publication_status=? WHERE id=?", status, seed.content());
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        problem(republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", seed.revision()), token(author)), 409, "CONTENT_NOT_EDITABLE");
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "IN_REVIEW", "REJECTED", "CANCELLED"})
    void laterUnapprovedWorkDoesNotReplaceLatestApprovalOrGetModified(String state) throws Exception {
        var author = author(); var seed = withdrawn(author);
        String later = id();
        insertRevision(seed.content(), later, 2, state, "新版标题", "新版正文");
        String review = state.equals("IN_REVIEW") ? ContentTestSupport.pending(jdbc, seed.content(), later) : null;
        if (state.equals("DRAFT") || review != null) jdbc.update("UPDATE community_content SET working_revision_id=?,active_review_id=? WHERE id=?", later, review, seed.content());
        var frozen = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", later);
        var reviewBefore = review == null ? null : jdbc.queryForMap("SELECT * FROM content_review WHERE id=?", review);
        var response = republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", seed.revision()), token(author));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(seed.revision(), JsonPath.read(response.body(), "$.data.publishedRevision.id"));
        if (state.equals("DRAFT") || review != null) assertEquals(later, JsonPath.read(response.body(), "$.data.workingRevision.id"));
        assertEquals(review, JsonPath.read(response.body(), "$.data.activeReviewId"));
        assertEquals(frozen, jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", later));
        if (review != null) assertEquals(reviewBefore, jdbc.queryForMap("SELECT * FROM content_review WHERE id=?", review));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TAKEN_DOWN", "UNPUBLISHED", "DELETED"})
    void publicationAndLifecycleRestrictionsCannotBeOverridden(String state) throws Exception {
        var author = author(); var seed = withdrawn(author);
        jdbc.update("UPDATE community_content SET " + (state.equals("DELETED") ? "lifecycle" : "publication_status") + "=? WHERE id=?", state, seed.content());
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        problem(republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", seed.revision()), token(author)), 409,
                state.equals("TAKEN_DOWN") ? "REMEDIATION_REQUIRED" : "CONTENT_NOT_EDITABLE");
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCESSING", "FAILED", "OWNER", "SCENE", "MISSING_VIDEO", "MISSING_TIMES", "MISSING_POINTER", "BLANK_TITLE", "NOOP_PROCESSING"})
    void approvedRevisionMustStillMeetCurrentPublicVisibilityConditions(String condition) throws Exception {
        var author = author(); var seed = withdrawn(author);
        boolean corrupt = condition.startsWith("MISSING_") && !condition.equals("MISSING_VIDEO") || condition.equals("BLANK_TITLE");
        switch (condition) {
            case "MISSING_VIDEO" -> jdbc.update("UPDATE community_content SET kind='VIDEO' WHERE id=?", seed.content());
            case "MISSING_TIMES" -> jdbc.update("UPDATE community_content SET first_published_at=NULL,published_at=NULL WHERE id=?", seed.content());
            case "MISSING_POINTER" -> jdbc.update("UPDATE community_content SET published_revision_id=NULL WHERE id=?", seed.content());
            case "BLANK_TITLE" -> jdbc.update("UPDATE content_revision SET title='  ' WHERE id=?", seed.revision());
            default -> {
                String owner = condition.equals("OWNER") ? author().getId() : author.getId();
                String state = condition.equals("FAILED") ? "FAILED" : condition.endsWith("PROCESSING") ? "PROCESSING" : "READY";
                String asset = asset(owner, condition.equals("SCENE") ? "AVATAR" : "CONTENT_IMAGE", state);
                jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'IMAGE',0)", seed.revision(), asset);
                if (condition.startsWith("NOOP")) jdbc.update("UPDATE community_content SET publication_status='PUBLISHED' WHERE id=?", seed.content());
            }
        }
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        problem(republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", seed.revision()), token(author)),
                corrupt ? 503 : 409, corrupt ? "DEPENDENCY_UNAVAILABLE" : "CONTENT_NOT_EDITABLE");
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
    }

    @Test
    void eachActualRepublicationCommitsOneAuditAndEventAndRepeatsDoNotWriteAgain() throws Exception {
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='content_publication_republish_audit'", Integer.class));
        var author = author(); var seed = withdrawn(author); String token = token(author);
        var response = republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", seed.revision()), token);
        assertEquals(200, response.statusCode(), response.body());
        var audit = jdbc.queryForMap("SELECT * FROM content_publication_republish_audit WHERE content_id=?", seed.content());
        assertEquals(author.getId(), audit.get("actor_id")); assertEquals(seed.revision(), audit.get("published_revision_id"));
        assertEquals(8, audit.get("before_content_version")); assertEquals(9, audit.get("after_content_version"));
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), audit.get("request_id"));
        var event = jdbc.queryForMap("SELECT * FROM content_publication_outbox WHERE content_id=?", seed.content());
        assertEquals("CONTENT_REPUBLISHED", event.get("event_type")); assertEquals("PENDING", event.get("state"));
        assertEquals(9, event.get("content_version")); assertEquals(seed.revision(), event.get("published_revision_id"));
        assertEquals("PUBLISHED", JsonPath.read((String) event.get("payload"), "$.publicationStatus"));
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), JsonPath.read((String) event.get("payload"), "$.requestId"));
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        assertEquals(200, republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", seed.revision()), token).statusCode());
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_republish_audit WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_outbox WHERE content_id=?", Integer.class, seed.content()));
        var withdrawn = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/creator/contents/" + seed.content() + "/publication?expectedVersion=9"))
                .header("Authorization", "Bearer " + token).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, withdrawn.statusCode(), withdrawn.body());
        assertEquals(200, republish(seed.content(), Map.of("expectedVersion", 10, "revisionId", seed.revision()), token).statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_republish_audit WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_outbox WHERE content_id=? AND event_type='CONTENT_REPUBLISHED'", Integer.class, seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOURCE", "USER"})
    void exhaustedIndependentRepublicationQuotaReturns429BeforeMutation(String scope) throws Exception {
        var author = author(); var seed = withdrawn(author);
        String key = scope.equals("SOURCE") ? sourceKey() : userKey(author.getId());
        redis.opsForValue().set(key, scope.equals("SOURCE") ? "120" : "60", Duration.ofMinutes(1));
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        var response = republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", seed.revision()), token(author));
        problem(response, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
    }

    @Test
    void repeatedSuccessUsesRepublicationQuotaAndIsIsolatedFromWithdrawalAndBrowsing() throws Exception {
        var author = author(); var seed = withdrawn(author); String token = token(author);
        for (String slot : List.of("content-publication-withdrawal", "content-public-query"))
            redis.opsForValue().set(PREFIX + "{" + slot + "}:source:" + TokenDigest.sha256("127.0.0.1"), "120", Duration.ofMinutes(1));
        assertEquals(200, republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", seed.revision()), token).statusCode());
        assertEquals(200, republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", seed.revision()), token).statusCode());
        assertEquals("2", redis.opsForValue().get(sourceKey()));
        assertEquals("2", redis.opsForValue().get(userKey(author.getId())));
        assertEquals(9, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_republish_audit WHERE content_id=?", Integer.class, seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONTENT", "AUDIT", "EVENT", "SQL", "RESPONSE"})
    void writeOrResponseFailureRollsBackStateAuditAndOutboxTogether(String point) throws Exception {
        var author = author(); var seed = withdrawn(author);
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        switch (point) {
            case "CONTENT" -> doReturn(0).when(writes).republish(anyString(), anyString(), anyInt(), anyString(), any());
            case "AUDIT" -> doReturn(0).when(writes).insertAudit(any());
            case "EVENT" -> doReturn(0).when(writes).insertEvent(anyString(), anyString(), anyString(), anyInt(), anyString(), any());
            case "SQL" -> doThrow(new TransientDataAccessResourceException("sensitive SQL detail")).when(writes).insertEvent(anyString(), anyString(), anyString(), anyInt(), anyString(), any());
            default -> doThrow(new TransientDataAccessResourceException("sensitive projection detail")).when(reads).findOwned(anyString(), anyString());
        }
        var response = republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", seed.revision()), token(author));
        problem(response, point.equals("CONTENT") ? 409 : 503, point.equals("CONTENT") ? "CONTENT_VERSION_CONFLICT" : "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive"));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_republish_audit WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_outbox WHERE content_id=?", Integer.class, seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTH_VERSION", "UNVERIFIED", "DISABLED", "WRITE_BLOCKED", "NO_USER"})
    void eligibilityIsRecheckedInsideTheWriteTransaction(String change) throws Exception {
        var author = author(); var seed = withdrawn(author); String token = token(author);
        doAnswer(call -> {
            var actor = (SessionPrincipal) call.callRealMethod();
            switch (change) {
                case "AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", author.getId());
                case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=false WHERE id=?", author.getId());
                case "NO_USER" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=?", author.getId());
                default -> jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", change, author.getId());
            }
            return actor;
        }).when(authentication).authenticate(token);
        problem(republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", seed.revision()), token), change.equals("AUTH_VERSION") ? 401 : 403,
                change.equals("AUTH_VERSION") ? "AUTHENTICATION_REQUIRED" : change.equals("UNVERIFIED") || change.equals("NO_USER") ? "PERMISSION_DENIED" : "ACCOUNT_RESTRICTED");
        assertEquals("WITHDRAWN", jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?", String.class, seed.content()));
        verifyNoInteractions(writes);
    }

    @Test
    void redisFailureReturns503WithoutStartingTheWriteTransaction() throws Exception {
        var author = author(); var seed = withdrawn(author);
        doThrow(new RedisConnectionFailureException("sensitive Redis detail")).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(), any());
        var response = republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", seed.revision()), token(author));
        problem(response, 503, "DEPENDENCY_UNAVAILABLE"); assertFalse(response.body().contains("sensitive"));
        verifyNoInteractions(writes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "FOREIGN", "FOREIGN_REVISION", "DRAFT", "REJECTED", "CANCELLED", "STALE", "MAX"})
    void ownershipRevisionStateAndActualMutationVersionAreProtected(String condition) throws Exception {
        var author = author(); var seed = withdrawn(author);
        String content = seed.content(), revision = seed.revision(), token = token(author);
        int version = 8, status = 409; String code = "CONTENT_NOT_EDITABLE";
        switch (condition) {
            case "MISSING" -> { content = id(); status = 404; code = "RESOURCE_NOT_FOUND"; }
            case "FOREIGN" -> { token = token(author()); status = 404; code = "RESOURCE_NOT_FOUND"; }
            case "FOREIGN_REVISION" -> { revision = withdrawn(author()).revision(); status = 404; code = "RESOURCE_NOT_FOUND"; }
            case "STALE" -> { version = 7; code = "CONTENT_VERSION_CONFLICT"; }
            case "MAX" -> { jdbc.update("UPDATE community_content SET version=? WHERE id=?", Integer.MAX_VALUE, content); version = Integer.MAX_VALUE; code = "CONTENT_VERSION_CONFLICT"; }
            default -> jdbc.update("UPDATE content_revision SET status=? WHERE id=?", condition, revision);
        }
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        var response = republish(content, Map.of("expectedVersion", version, "revisionId", revision), token);
        problem(response, status, code);
        if (status == 404) assertFalse(JsonPath.<Map<String, Object>>read(response.body(), "$").containsKey("currentVersion"));
        if (code.equals("CONTENT_VERSION_CONFLICT")) assertEquals(before.get("version"), JsonPath.read(response.body(), "$.currentVersion"));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
    }

    @Test
    void maximumVersionStillAllowsAnAlreadyPublicNoOp() throws Exception {
        var author = author(); var seed = withdrawn(author);
        jdbc.update("UPDATE community_content SET publication_status='PUBLISHED',version=? WHERE id=?", Integer.MAX_VALUE, seed.content());
        var response = republish(seed.content(), Map.of("expectedVersion", 1, "revisionId", seed.revision()), token(author));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(Integer.MAX_VALUE, JsonPath.<Integer>read(response.body(), "$.data.version"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_republish_audit WHERE content_id=?", Integer.class, seed.content()));
    }

    @Test
    void concurrentDuplicateRequestsCommitOnlyOneRepublication() throws Exception {
        var author = author(); var seed = withdrawn(author); String token = token(author);
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { start.await(); return republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", seed.revision()), token); });
            var second = pool.submit(() -> { start.await(); return republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", seed.revision()), token); });
            start.countDown();
            for (var result : List.of(first, second)) { var response = result.get(40, TimeUnit.SECONDS); assertEquals(200, response.statusCode(), response.body()); }
            assertEquals(9, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_republish_audit WHERE content_id=?", Integer.class, seed.content()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_outbox WHERE content_id=?", Integer.class, seed.content()));
            assertEquals("2", redis.opsForValue().get(sourceKey()));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void republicationRestoresPublicReadsAndCountsAndKeepsDisabledTags() throws Exception {
        var author = author(); var seed = withdrawn(author); String tag = id();
        jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) "
                + "VALUES(?,?,'说明','DISABLED',1,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", tag, "tag_" + tag.substring(0, 16), author.getId());
        jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,0)", seed.revision(), tag);
        String media = asset(author.getId(), "CONTENT_IMAGE", "READY");
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'IMAGE',0)", seed.revision(), media);
        problem(get("/api/v1/contents/" + seed.content()), 404, "RESOURCE_NOT_FOUND");
        assertEquals(200, republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", seed.revision()), token(author)).statusCode());
        var detail = get("/api/v1/contents/" + seed.content());
        assertEquals(200, detail.statusCode(), detail.body());
        assertEquals(List.of(media), JsonPath.read(detail.body(), "$.data.imageAssetIds"));
        assertEquals("DISABLED", JsonPath.read(detail.body(), "$.data.tags[0].status"));
        assertEquals("2026-11-03T08:00:00Z", JsonPath.read(detail.body(), "$.data.firstPublishedAt"));
        assertEquals(1, JsonPath.<Integer>read(get("/api/v1/users/" + author.getId()).body(), "$.data.publishedContentCount"));
        assertEquals(seed.content(), JsonPath.read(get("/api/v1/users/" + author.getId() + "/contents").body(), "$.data.items[0].id"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(45)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aDifferentLatestApprovalCannotReplaceAnAlreadyPublicRevisionThroughThisEndpoint() throws Exception {
        var author = author(); var seed = withdrawn(author); String latest = id();
        insertRevision(seed.content(), latest, 2, "APPROVED", "最近批准", "批准正文");
        jdbc.update("UPDATE community_content SET publication_status='PUBLISHED' WHERE id=?", seed.content());
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        problem(republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", latest), token(author)), 409, "CONTENT_NOT_EDITABLE");
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
    }

    private String sourceKey() { return PREFIX + "{content-publication-republish}:source:" + TokenDigest.sha256("127.0.0.1"); }
    private String userKey(String actor) { return PREFIX + "{content-publication-republish}:user:" + TokenDigest.sha256(actor); }

    @Test
    void anOldWithdrawalConcurrentWithRepublicationCannotWithdrawTheNewPublication() throws Exception {
        var author = author(); var seed = withdrawn(author); String token = token(author);
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var publish = pool.submit(() -> { start.await(); return republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", seed.revision()), token); });
            var withdraw = pool.submit(() -> { start.await(); return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/creator/contents/" + seed.content() + "/publication?expectedVersion=8"))
                    .header("Authorization", "Bearer " + token).DELETE().build(), HttpResponse.BodyHandlers.ofString()); });
            start.countDown(); var published = publish.get(40, TimeUnit.SECONDS); var withdrawn = withdraw.get(40, TimeUnit.SECONDS);
            assertEquals(200, published.statusCode(), published.body());
            if (withdrawn.statusCode() != 204) problem(withdrawn, 409, "CONTENT_VERSION_CONFLICT");
            assertEquals("PUBLISHED", jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?", String.class, seed.content()));
            assertEquals(9, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_withdrawal_audit WHERE content_id=?", Integer.class, seed.content()));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void approvalAndRepublicationSerializeOnTheSameContentVersion() throws Exception {
        var author = author(); var seed = withdrawn(author); String token = token(author), pendingRevision = id();
        insertRevision(seed.content(), pendingRevision, 2, "IN_REVIEW", "待审新版", "冻结正文");
        String review = ContentTestSupport.pending(jdbc, seed.content(), pendingRevision);
        jdbc.update("UPDATE content_review SET purpose='UPDATE' WHERE id=?", review);
        jdbc.update("UPDATE community_content SET working_revision_id=?,active_review_id=? WHERE id=?", pendingRevision, review, seed.content());
        var moderator = author();
        jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,'MODERATOR',UTC_TIMESTAMP(6))", moderator.getId());
        String moderatorToken = token(moderator), key = id();
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var publish = pool.submit(() -> { start.await(); return republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", seed.revision()), token); });
            var decide = pool.submit(() -> { start.await(); return post("/api/v1/admin/reviews/" + review + "/decisions",
                    Map.of("expectedContentVersion", 8, "expectedReviewVersion", 1, "decision", "APPROVE", "reason", "审核通过"), moderatorToken, key); });
            start.countDown(); var published = publish.get(40, TimeUnit.SECONDS); var approved = decide.get(40, TimeUnit.SECONDS);
            if (approved.statusCode() == 200) {
                problem(published, 409, "CONTENT_NOT_EDITABLE");
                assertEquals(pendingRevision, jdbc.queryForObject("SELECT published_revision_id FROM community_content WHERE id=?", String.class, seed.content()));
            } else {
                assertEquals(200, published.statusCode(), published.body()); problem(approved, 409, "CONTENT_VERSION_CONFLICT");
                assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM content_review WHERE id=?", String.class, review));
            }
            assertEquals(9, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void draftCreationAndRepublicationCannotBothConsumeTheSameVersion() throws Exception {
        var author = author(); var seed = withdrawn(author); String token = token(author), key = id();
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var publish = pool.submit(() -> { start.await(); return republish(seed.content(), Map.of("expectedVersion", 8, "revisionId", seed.revision()), token); });
            var create = pool.submit(() -> { start.await(); return post("/api/v1/creator/contents/" + seed.content() + "/drafts", Map.of("expectedVersion", 8), token, key); });
            start.countDown(); var published = publish.get(40, TimeUnit.SECONDS); var draft = create.get(40, TimeUnit.SECONDS);
            if (draft.statusCode() == 201) { problem(published, 409, "CONTENT_VERSION_CONFLICT"); assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, seed.content())); }
            else { assertEquals(200, published.statusCode(), published.body()); problem(draft, 409, "CONTENT_VERSION_CONFLICT"); assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, seed.content())); }
            assertEquals(9, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void republicationDoesNotMoveAnOlderWorkToTheFrontOfTheAuthorList() throws Exception {
        var author = author(); var old = withdrawn(author); var newer = withdrawn(author);
        jdbc.update("UPDATE community_content SET publication_status='PUBLISHED',first_published_at='2026-11-05 08:00:00',published_at='2026-11-05 08:00:00' WHERE id=?", newer.content());
        assertEquals(200, republish(old.content(), Map.of("expectedVersion", 8, "revisionId", old.revision()), token(author)).statusCode());
        var list = get("/api/v1/users/" + author.getId() + "/contents");
        assertEquals(200, list.statusCode(), list.body());
        assertEquals(List.of(newer.content(), old.content()), JsonPath.read(list.body(), "$.data.items[*].id"));
    }

    private HttpResponse<String> post(String path, Map<String, Object> body, String token, String key) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void openApiDeclaresTheStrictPublicationBodyPrivateSecurityAndWorkspaceResult() throws Exception {
        var response = get("/v3/api-docs"); assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/creator/contents/{contentId}/publication'].put");
        assertEquals("republishApprovedContent", operation.get("operationId"));
        assertEquals(List.of(Map.of("bearerAuth", List.of())), operation.get("security"));
        assertEquals("#/components/schemas/PublicationRequest", JsonPath.read(response.body(), "$.paths['/api/v1/creator/contents/{contentId}/publication'].put.requestBody.content['application/json'].schema['$ref']"));
        Map<String, Object> schema = JsonPath.read(response.body(), "$.components.schemas.PublicationRequest");
        assertEquals(java.util.Set.of("expectedVersion", "revisionId"), ((Map<?, ?>) schema.get("properties")).keySet());
        assertEquals(java.util.Set.of("expectedVersion", "revisionId"), new java.util.HashSet<>((List<?>) schema.get("required")));
        assertEquals(false, schema.get("additionalProperties"));
        assertEquals("integer", JsonPath.read(response.body(), "$.components.schemas.PublicationRequest.properties.expectedVersion.type"));
        assertNotNull(JsonPath.read(response.body(), "$.paths['/api/v1/creator/contents/{contentId}/publication'].put.responses['200'].content['application/json'].schema['$ref']"));
    }

    private String asset(String owner, String scene, String state) {
        String asset = id();
        jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) "
                + "VALUES(?,?,?,'image.png','image/png',1,?,?,UTC_TIMESTAMP(6))", asset, owner, scene, "republication/" + asset, state);
        return asset;
    }

    private Seed withdrawn(UserAccount author) {
        String content = id(), revision = id();
        jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) "
                + "VALUES(?,?,'ARTICLE','ACTIVE','UNPUBLISHED',8,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", content, author.getId());
        insertRevision(content, revision, 1, "APPROVED", "批准标题", "批准正文");
        jdbc.update("UPDATE community_content SET publication_status='WITHDRAWN',published_revision_id=?,"
                + "first_published_at='2026-11-03 08:00:00',published_at='2026-11-04 08:00:00' WHERE id=?", revision, content);
        return new Seed(content, revision);
    }

    private void insertRevision(String content, String revision, int number, String status, String title, String body) {
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", revision, content, number, status, title, body);
    }

    private UserAccount author() {
        var author = LoginTestSupport.account();
        author.setId(id());
        author.setEmail("republication-" + author.getId() + "@example.test");
        author.setEmailNormalized(author.getEmail());
        users.insert(author); owners.add(author.getId());
        roles.insertDefaultRole(author.getId(), author.getCreatedAt());
        return author;
    }

    private String token(UserAccount author) { return sessions.create(author.getId(), 1, "republication-test").accessToken(); }

    private HttpResponse<String> republish(String content, Map<String, Object> body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/creator/contents/" + content + "/publication"))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertFalse(JsonPath.<Map<String, Object>>read(response.body(), "$").containsKey("data"));
    }

    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private record Seed(String content, String revision) { }
}
