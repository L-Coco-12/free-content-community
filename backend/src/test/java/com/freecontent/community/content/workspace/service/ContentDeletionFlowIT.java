package com.freecontent.community.content.workspace.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.workspace.mapper.ContentDeletionMapper;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.RedisConnectionFailureException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class ContentDeletionFlowIT {
    private static final String PREFIX = "test:content-deletion:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private StringRedisTemplate redis;
    @MockitoSpyBean private ContentDeletionMapper writes;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @Autowired private JsonMapper json;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) {
        for (String scope : List.of("auth.login", "content.deletion", "content.publication-withdrawal", "content.publication-republish", "content.public-query",
                "content.public-media-access", "content.query", "user.public-query", "content.review-decision", "content.draft-creation", "content.draft-save"))
            registry.add("app." + scope + ".redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        reset(redis, writes, authentication);
        var keys = redis.keys(PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) {
            jdbc.update("DELETE FROM content_review_decision_request WHERE created_by=?", owner);
            jdbc.update("DELETE FROM content_draft_creation_request WHERE created_by=?", owner);
            for (String table : List.of("content_review_decision_request", "content_review_decision_audit", "content_review_cancellation_audit",
                    "content_review_media_preview_audit", "content_media_preview_audit", "content_publication_outbox",
                    "content_publication_republish_audit", "content_publication_withdrawal_audit", "content_deletion_audit", "content_lifecycle_outbox")) {
                if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", Integer.class, table) == 1)
                    jdbc.update("DELETE FROM " + table + " WHERE content_id IN (SELECT id FROM community_content WHERE author_id=?)", owner);
            }
            ContentTestSupport.clearReviews(jdbc, owner); ContentTestSupport.clearWorkspace(jdbc, owner);
        }
        for (String owner : owners) {
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?", owner); jdbc.update("DELETE FROM community_tag WHERE created_by=?", owner);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner); users.deleteById(owner);
        }
    }

    @Test
    void unpublishedDraftMovesToTrashWithoutDeletingRevisionOrChangingPublicationStatus() throws Exception {
        var author = author(); var seed = content(author, "UNPUBLISHED");
        var revision = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision());
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        var response = delete(seed.content(), "7", token(author));
        assertEquals(204, response.statusCode(), response.body()); assertEquals("", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var after = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        assertEquals("DELETED", after.get("lifecycle")); assertEquals("UNPUBLISHED", after.get("publication_status"));
        assertEquals(8, after.get("version")); assertNotNull(after.get("deleted_at"));
        assertEquals(seed.revision(), after.get("working_revision_id")); assertEquals(before.get("created_at"), after.get("created_at"));
        assertEquals(revision, jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNPUBLISHED", "PUBLISHED", "WITHDRAWN", "TAKEN_DOWN"})
    void pendingReviewIsInvalidatedAndItsFrozenRevisionCancelledInTheSameDeletion(String publication) throws Exception {
        var author = author(); var seed = content(author, publication); var pending = pending(seed, publication);
        var frozen = jdbc.queryForMap("SELECT title,body_markdown,created_at FROM content_revision WHERE id=?", pending.revision());
        var publishedTimes = jdbc.queryForMap("SELECT published_revision_id,first_published_at,published_at FROM community_content WHERE id=?", seed.content());
        var response = delete(seed.content(), "7", token(author));
        assertEquals(204, response.statusCode(), response.body());
        var after = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        assertEquals("DELETED", after.get("lifecycle")); assertEquals(publication, after.get("publication_status"));
        assertNull(after.get("working_revision_id")); assertNull(after.get("active_review_id")); assertEquals(8, after.get("version"));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM content_revision WHERE id=?", String.class, pending.revision()));
        assertEquals(frozen, jdbc.queryForMap("SELECT title,body_markdown,created_at FROM content_revision WHERE id=?", pending.revision()));
        var review = jdbc.queryForMap("SELECT * FROM content_review WHERE id=?", pending.review());
        assertEquals("INVALIDATED", review.get("state")); assertEquals(2, review.get("version")); assertNotNull(review.get("decided_at"));
        assertNull(review.get("reviewer_id")); assertNull(review.get("reason"));
        assertEquals(publishedTimes, jdbc.queryForMap("SELECT published_revision_id,first_published_at,published_at FROM community_content WHERE id=?", seed.content()));
    }

    @Test
    void alreadyDeletedRepeatsIgnoreOldVersionAndDoNotChangeDeletionTime() throws Exception {
        var author = author(); var seed = content(author, "UNPUBLISHED"); String token = token(author);
        assertEquals(204, delete(seed.content(), "7", token).statusCode());
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        var response = delete(seed.content(), "7", token);
        assertEquals(204, response.statusCode(), response.body());
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
    }

    @ParameterizedTest
    @CsvSource({"UNPUBLISHED,false", "UNPUBLISHED,true", "PUBLISHED,true", "TAKEN_DOWN,true"})
    void actualDeletionStoresAuditAndLifecycleOutboxOnceAndIncludesReviewInvalidation(String publication, boolean withReview) throws Exception {
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('content_deletion_audit','content_lifecycle_outbox')", Integer.class));
        var author = author(); var seed = content(author, publication); var pending = withReview ? pending(seed, publication) : null;
        String token = token(author); var response = delete(seed.content(), "7", token);
        assertEquals(204, response.statusCode(), response.body());
        var audit = jdbc.queryForMap("SELECT * FROM content_deletion_audit WHERE content_id=?", seed.content());
        assertEquals(author.getId(), audit.get("actor_id")); assertEquals(publication, audit.get("publication_status"));
        assertEquals(publication.equals("UNPUBLISHED") ? null : seed.revision(), audit.get("published_revision_id"));
        assertEquals(7, audit.get("before_content_version")); assertEquals(8, audit.get("after_content_version"));
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), audit.get("request_id"));
        assertEquals(withReview ? pending.review() : null, audit.get("invalidated_review_id"));
        assertEquals(withReview ? pending.revision() : null, audit.get("cancelled_revision_id"));
        assertEquals(withReview ? 1 : null, audit.get("before_review_version")); assertEquals(withReview ? 2 : null, audit.get("after_review_version"));
        var event = jdbc.queryForMap("SELECT * FROM content_lifecycle_outbox WHERE content_id=?", seed.content());
        assertEquals("CONTENT_DELETED", event.get("event_type")); assertEquals("PENDING", event.get("state")); assertEquals(8, event.get("content_version"));
        assertEquals("DELETED", JsonPath.read((String) event.get("payload"), "$.lifecycle"));
        assertEquals(publication, JsonPath.read((String) event.get("payload"), "$.publicationStatus"));
        assertEquals(publication.equals("UNPUBLISHED") ? null : seed.revision(), event.get("published_revision_id"));
        if (withReview) {
            String payload = jdbc.queryForObject("SELECT payload FROM content_review_outbox WHERE content_id=? AND event_type='CONTENT_REVIEW_INVALIDATED'", String.class, seed.content());
            assertEquals("AUTHOR_DELETION", JsonPath.read(payload, "$.reasonCode"));
            assertEquals(2, JsonPath.<Integer>read(payload, "$.reviewVersion"));
        }
        assertEquals(204, delete(seed.content(), "7", token).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_deletion_audit WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_lifecycle_outbox WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(withReview ? 1 : 0, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_outbox WHERE content_id=?", Integer.class, seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOURCE", "USER"})
    void exhaustedDeletionQuotaReturns429WithoutDeleting(String scope) throws Exception {
        var author = author(); var seed = content(author, "UNPUBLISHED");
        redis.opsForValue().set(scope.equals("SOURCE") ? sourceKey() : userKey(author.getId()), scope.equals("SOURCE") ? "120" : "60", Duration.ofMinutes(1));
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        var response = delete(seed.content(), "7", token(author));
        problem(response, 429, "RATE_LIMITED"); assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
    }

    @Test
    void repeated204ConsumesOnlyIndependentDeletionQuota() throws Exception {
        var author = author(); var seed = content(author, "UNPUBLISHED"); String token = token(author);
        for (String slot : List.of("content-publication-withdrawal", "content-publication-republish"))
            redis.opsForValue().set(PREFIX + "{" + slot + "}:source:" + TokenDigest.sha256("127.0.0.1"), "120", Duration.ofMinutes(1));
        assertEquals(204, delete(seed.content(), "7", token).statusCode()); assertEquals(204, delete(seed.content(), "7", token).statusCode());
        assertEquals("2", redis.opsForValue().get(sourceKey())); assertEquals("2", redis.opsForValue().get(userKey(author.getId())));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_deletion_audit WHERE content_id=?", Integer.class, seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCEL", "INVALIDATE", "CONTENT", "AUDIT", "LIFECYCLE_EVENT", "REVIEW_EVENT", "SQL"})
    void anyWriteFailureRollsBackDeletionReviewRevisionAuditAndEvents(String point) throws Exception {
        var author = author(); var seed = content(author, "PUBLISHED"); var pending = pending(seed, "PUBLISHED");
        var before = related(seed, pending);
        switch (point) {
            case "CANCEL" -> doReturn(0).when(writes).cancelRevision(anyString(), anyString(), any());
            case "INVALIDATE" -> doReturn(0).when(writes).invalidateReview(anyString(), anyString(), anyString(), anyInt(), any());
            case "CONTENT" -> doReturn(0).when(writes).delete(anyString(), anyString(), anyInt(), any(), any());
            case "AUDIT" -> doReturn(0).when(writes).insertAudit(any());
            case "LIFECYCLE_EVENT" -> doReturn(0).when(writes).insertLifecycleEvent(anyString(), anyString(), any(), anyInt(), anyString(), any());
            case "REVIEW_EVENT" -> doReturn(0).when(writes).insertReviewEvent(anyString(), any(), anyInt(), anyString(), any());
            default -> doThrow(new TransientDataAccessResourceException("sensitive SQL detail")).when(writes).insertLifecycleEvent(anyString(), anyString(), any(), anyInt(), anyString(), any());
        }
        var response = delete(seed.content(), "7", token(author));
        problem(response, point.equals("CONTENT") ? 409 : 503, point.equals("CONTENT") ? "CONTENT_VERSION_CONFLICT" : "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive")); assertEquals(before, related(seed, pending));
        for (String table : List.of("content_deletion_audit", "content_lifecycle_outbox", "content_review_outbox"))
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE content_id=?", Integer.class, seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTH_VERSION", "UNVERIFIED", "DISABLED", "WRITE_BLOCKED", "NO_USER"})
    void eligibilityIsRecheckedAfterTheAuthenticationFilter(String change) throws Exception {
        var author = author(); var seed = content(author, "UNPUBLISHED"); String token = token(author);
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
        problem(delete(seed.content(), "7", token), change.equals("AUTH_VERSION") ? 401 : 403,
                change.equals("AUTH_VERSION") ? "AUTHENTICATION_REQUIRED" : change.equals("NO_USER") || change.equals("UNVERIFIED") ? "PERMISSION_DENIED" : "ACCOUNT_RESTRICTED");
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT lifecycle FROM community_content WHERE id=?", String.class, seed.content()));
        verifyNoInteractions(writes);
    }

    @Test
    void redisFailureDoesNotStartDeletion() throws Exception {
        var author = author(); var seed = content(author, "UNPUBLISHED");
        doThrow(new RedisConnectionFailureException("sensitive Redis detail")).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(), any());
        var response = delete(seed.content(), "7", token(author));
        problem(response, 503, "DEPENDENCY_UNAVAILABLE"); assertFalse(response.body().contains("sensitive")); verifyNoInteractions(writes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "FOREIGN", "STALE", "MAX"})
    void ownershipAndCurrentVersionAreRequiredForActualDeletion(String condition) throws Exception {
        var author = author(); var seed = content(author, "UNPUBLISHED");
        String target = condition.equals("MISSING") ? id() : seed.content(), token = condition.equals("FOREIGN") ? token(author()) : token(author);
        String version = condition.equals("MAX") ? "2147483647" : condition.equals("STALE") ? "6" : "7";
        if (condition.equals("MAX")) jdbc.update("UPDATE community_content SET version=? WHERE id=?", Integer.MAX_VALUE, target);
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        var response = delete(target, version, token); boolean inaccessible = condition.equals("MISSING") || condition.equals("FOREIGN");
        problem(response, inaccessible ? 404 : 409, inaccessible ? "RESOURCE_NOT_FOUND" : "CONTENT_VERSION_CONFLICT");
        if (inaccessible) assertFalse(JsonPath.<Map<String, Object>>read(response.body(), "$").containsKey("currentVersion"));
        else assertEquals(before.get("version"), JsonPath.read(response.body(), "$.currentVersion"));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE_REVIEW", "WORKING_POINTER", "TERMINAL_REVIEW", "REVIEW_MAX", "MISMATCHED_REVISION"})
    void inconsistentOrUnadvanceableReviewBindingsFailWithoutPartialDeletion(String issue) throws Exception {
        var author = author(); var seed = content(author, "PUBLISHED"); var pending = pending(seed, "PUBLISHED");
        switch (issue) {
            case "ACTIVE_REVIEW" -> jdbc.update("UPDATE community_content SET active_review_id=NULL WHERE id=?", seed.content());
            case "WORKING_POINTER" -> jdbc.update("UPDATE community_content SET working_revision_id=NULL WHERE id=?", seed.content());
            case "TERMINAL_REVIEW" -> jdbc.update("UPDATE content_review SET state='CANCELLED' WHERE id=?", pending.review());
            case "MISMATCHED_REVISION" -> jdbc.update("UPDATE content_review SET revision_id=? WHERE id=?", seed.revision(), pending.review());
            default -> jdbc.update("UPDATE content_review SET version=? WHERE id=?", Integer.MAX_VALUE, pending.review());
        }
        var before = related(seed, pending);
        problem(delete(seed.content(), "7", token(author)), 503, "DEPENDENCY_UNAVAILABLE");
        assertEquals(before, related(seed, pending));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_deletion_audit WHERE content_id=?", Integer.class, seed.content()));
    }

    @Test
    void alreadyDeletedAtMaximumVersionStillReturns204() throws Exception {
        var author = author(); var seed = content(author, "UNPUBLISHED");
        jdbc.update("UPDATE community_content SET lifecycle='DELETED',version=?,deleted_at=UTC_TIMESTAMP(6) WHERE id=?", Integer.MAX_VALUE, seed.content());
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        assertEquals(204, delete(seed.content(), "1", token(author)).statusCode());
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
    }

    @Test
    void deletionStopsPublicReadsAndMovesTheWorkspaceToTrashWithoutDeletingMedia() throws Exception {
        var author = author(); var seed = content(author, "PUBLISHED"); String token = token(author), asset = id(), tag = id();
        jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) "
                + "VALUES(?,?,'CONTENT_IMAGE','image.png','image/png',1,?,'READY',UTC_TIMESTAMP(6))", asset, author.getId(), "deletion/" + asset);
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'IMAGE',0)", seed.revision(), asset);
        jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) "
                + "VALUES(?,?,'说明','DISABLED',1,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", tag, "tag_" + tag.substring(0, 16), author.getId());
        jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,0)", seed.revision(), tag);
        var media = jdbc.queryForMap("SELECT * FROM media_asset WHERE id=?", asset);
        assertEquals(200, get("/api/v1/contents/" + seed.content(), null).statusCode());
        assertEquals(204, delete(seed.content(), "7", token).statusCode());
        problem(get("/api/v1/contents/" + seed.content(), null), 404, "RESOURCE_NOT_FOUND");
        problem(get("/api/v1/contents/" + seed.content() + "/media/" + asset + "/access", null), 404, "RESOURCE_NOT_FOUND");
        assertEquals(List.of(), JsonPath.read(get("/api/v1/contents?tagId=" + tag, null).body(), "$.data.items"));
        assertEquals(List.of(), JsonPath.read(get("/api/v1/users/" + author.getId() + "/contents", null).body(), "$.data.items"));
        assertEquals(0, JsonPath.<Integer>read(get("/api/v1/users/" + author.getId(), null).body(), "$.data.publishedContentCount"));
        assertEquals(List.of(), JsonPath.read(get("/api/v1/creator/contents", token).body(), "$.data.items"));
        assertEquals(seed.content(), JsonPath.read(get("/api/v1/creator/contents?lifecycle=DELETED", token).body(), "$.data.items[0].id"));
        var workspace = get("/api/v1/creator/contents/" + seed.content(), token);
        assertEquals(200, workspace.statusCode(), workspace.body());
        assertEquals("DELETED", JsonPath.read(workspace.body(), "$.data.lifecycle"));
        assertEquals("PUBLISHED", JsonPath.read(workspace.body(), "$.data.publicationStatus"));
        assertEquals(seed.revision(), JsonPath.read(workspace.body(), "$.data.publishedRevision.id"));
        assertEquals(List.of(asset), JsonPath.read(workspace.body(), "$.data.publishedRevision.imageAssetIds"));
        assertEquals(media, jdbc.queryForMap("SELECT * FROM media_asset WHERE id=?", asset));
    }

    @Test
    void deletionAuditCannotOmitVersionsForAnInvalidatedReview() {
        var author = author(); var seed = content(author, "PUBLISHED"); var pending = pending(seed, "PUBLISHED");
        var failure = assertThrows(DataAccessException.class, () -> jdbc.update("INSERT INTO content_deletion_audit "
                + "(id,actor_id,content_id,publication_status,published_revision_id,invalidated_review_id,cancelled_revision_id,before_content_version,after_content_version,"
                + "before_review_version,after_review_version,request_id,occurred_at) VALUES(?,?,?,'PUBLISHED',?,?,?,7,8,NULL,NULL,'req_constraint',UTC_TIMESTAMP(6))",
                id(), author.getId(), seed.content(), seed.revision(), pending.review(), pending.revision()));
        var databaseError = assertInstanceOf(java.sql.SQLException.class, failure.getCause());
        assertEquals(3819, databaseError.getErrorCode()); assertTrue(databaseError.getMessage().contains("chk_content_deletion_review"));
    }

    @Test
    void twoConcurrentDeletesPerformOnlyOneMutationAndAudit() throws Exception {
        var author = author(); var seed = content(author, "PUBLISHED"); var pending = pending(seed, "PUBLISHED"); String token = token(author);
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { start.await(); return delete(seed.content(), "7", token); });
            var second = pool.submit(() -> { start.await(); return delete(seed.content(), "7", token); });
            start.countDown();
            for (var result : List.of(first, second)) { var response = result.get(40, TimeUnit.SECONDS); assertEquals(204, response.statusCode(), response.body()); }
            assertEquals(8, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
            assertEquals(2, jdbc.queryForObject("SELECT version FROM content_review WHERE id=?", Integer.class, pending.review()));
            for (String table : List.of("content_deletion_audit", "content_lifecycle_outbox", "content_review_outbox"))
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE content_id=?", Integer.class, seed.content()));
            assertEquals("2", redis.opsForValue().get(sourceKey()));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void deletionAndApprovalCannotBothConsumeTheSameContentVersion() throws Exception {
        var author = author(); var seed = content(author, "PUBLISHED"); var pending = pending(seed, "PUBLISHED"); String token = token(author);
        var moderator = author(); jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,'MODERATOR',UTC_TIMESTAMP(6))", moderator.getId());
        String moderatorToken = token(moderator), key = id();
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var deletion = pool.submit(() -> { start.await(); return delete(seed.content(), "7", token); });
            var approval = pool.submit(() -> { start.await(); return post("/api/v1/admin/reviews/" + pending.review() + "/decisions",
                    Map.of("expectedContentVersion", 7, "expectedReviewVersion", 1, "decision", "APPROVE", "reason", "审核通过"), moderatorToken, key); });
            start.countDown(); var deleted = deletion.get(40, TimeUnit.SECONDS); var approved = approval.get(40, TimeUnit.SECONDS);
            if (deleted.statusCode() == 204) {
                problem(approved, 409, "CONTENT_VERSION_CONFLICT");
                assertEquals("INVALIDATED", jdbc.queryForObject("SELECT state FROM content_review WHERE id=?", String.class, pending.review()));
            } else { assertEquals(200, approved.statusCode(), approved.body()); problem(deleted, 409, "CONTENT_VERSION_CONFLICT"); assertEquals(204, delete(seed.content(), "8", token).statusCode()); }
            assertEquals("DELETED", jdbc.queryForObject("SELECT lifecycle FROM community_content WHERE id=?", String.class, seed.content()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_deletion_audit WHERE content_id=?", Integer.class, seed.content()));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void deletionAndDraftCreationCannotBothConsumeTheSameVersion() throws Exception {
        var author = author(); var seed = content(author, "PUBLISHED"); String token = token(author), key = id();
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var deletion = pool.submit(() -> { start.await(); return delete(seed.content(), "7", token); });
            var draft = pool.submit(() -> { start.await(); return post("/api/v1/creator/contents/" + seed.content() + "/drafts", Map.of("expectedVersion", 7), token, key); });
            start.countDown(); var deleted = deletion.get(40, TimeUnit.SECONDS); var created = draft.get(40, TimeUnit.SECONDS);
            if (deleted.statusCode() == 204) problem(created, 409, "CONTENT_VERSION_CONFLICT");
            else { assertEquals(201, created.statusCode(), created.body()); problem(deleted, 409, "CONTENT_VERSION_CONFLICT"); assertEquals(204, delete(seed.content(), "8", token).statusCode());
                String revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
                assertEquals("DRAFT", jdbc.queryForObject("SELECT status FROM content_revision WHERE id=?", String.class, revision)); }
            assertEquals("DELETED", jdbc.queryForObject("SELECT lifecycle FROM community_content WHERE id=?", String.class, seed.content()));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void deletionCannotBeUndoneByAConcurrentRepublication() throws Exception {
        var author = author(); var seed = content(author, "WITHDRAWN"); String token = token(author);
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var deletion = pool.submit(() -> { start.await(); return delete(seed.content(), "7", token); });
            var publish = pool.submit(() -> { start.await(); return put("/api/v1/creator/contents/" + seed.content() + "/publication",
                    Map.of("expectedVersion", 7, "revisionId", seed.revision()), token); });
            start.countDown(); var deleted = deletion.get(40, TimeUnit.SECONDS); var published = publish.get(40, TimeUnit.SECONDS);
            if (deleted.statusCode() == 204) problem(published, 409, "CONTENT_NOT_EDITABLE");
            else { assertEquals(200, published.statusCode(), published.body()); problem(deleted, 409, "CONTENT_VERSION_CONFLICT"); assertEquals(204, delete(seed.content(), "8", token).statusCode()); }
            assertEquals("DELETED", jdbc.queryForObject("SELECT lifecycle FROM community_content WHERE id=?", String.class, seed.content()));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void openApiDeclaresThePrivateDeleteQueryVersionAndNoRequestOrSuccessBody() throws Exception {
        var response = get("/v3/api-docs", null); assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/creator/contents/{contentId}'].delete");
        assertEquals("deleteMyContent", operation.get("operationId"));
        assertEquals(List.of(Map.of("bearerAuth", List.of())), operation.get("security")); assertFalse(operation.containsKey("requestBody"));
        assertEquals(java.util.Set.of("204", "400", "401", "403", "404", "409", "422", "429", "500", "503"), ((Map<?, ?>) operation.get("responses")).keySet());
        Map<?, ?> success = (Map<?, ?>) ((Map<?, ?>) operation.get("responses")).get("204"); assertFalse(success.containsKey("content"));
        List<Map<String, Object>> parameters = JsonPath.read(response.body(), "$.paths['/api/v1/creator/contents/{contentId}'].delete.parameters");
        assertEquals(java.util.Set.of("contentId", "expectedVersion"), parameters.stream().map(parameter -> parameter.get("name")).collect(java.util.stream.Collectors.toSet()));
        var version = parameters.stream().filter(parameter -> parameter.get("name").equals("expectedVersion")).findFirst().orElseThrow();
        assertEquals("query", version.get("in")); assertEquals(true, version.get("required"));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(45)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String path, Map<String, Object> body, String token, String key) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> put(String path, Map<String, Object> body, String token) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private List<Object> related(Seed seed, Pending pending) {
        return List.of(jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()),
                jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", pending.revision()), jdbc.queryForMap("SELECT * FROM content_review WHERE id=?", pending.review()));
    }

    private String sourceKey() { return PREFIX + "{content-deletion}:source:" + TokenDigest.sha256("127.0.0.1"); }
    private String userKey(String actor) { return PREFIX + "{content-deletion}:user:" + TokenDigest.sha256(actor); }

    private Pending pending(Seed seed, String publication) {
        String revision = seed.revision();
        if (publication.equals("UNPUBLISHED")) jdbc.update("UPDATE content_revision SET status='IN_REVIEW' WHERE id=?", revision);
        else { revision = id(); insertRevision(seed.content(), revision, 2, "IN_REVIEW"); }
        String review = ContentTestSupport.pending(jdbc, seed.content(), revision);
        jdbc.update("UPDATE content_review SET purpose=? WHERE id=?", publication.equals("UNPUBLISHED") ? "INITIAL" : publication.equals("TAKEN_DOWN") ? "REMEDIATION" : "UPDATE", review);
        jdbc.update("UPDATE community_content SET working_revision_id=?,active_review_id=? WHERE id=?", revision, review, seed.content());
        return new Pending(revision, review);
    }

    private Seed content(UserAccount author, String publication) {
        String content = id(), revision = id();
        jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) "
                + "VALUES(?,?,'ARTICLE','ACTIVE','UNPUBLISHED',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", content, author.getId());
        insertRevision(content, revision, 1, publication.equals("UNPUBLISHED") ? "DRAFT" : "APPROVED");
        if (publication.equals("UNPUBLISHED")) jdbc.update("UPDATE community_content SET working_revision_id=? WHERE id=?", revision, content);
        else jdbc.update("UPDATE community_content SET publication_status=?,published_revision_id=?,first_published_at='2026-11-03 08:00:00',"
                + "published_at='2026-11-04 08:00:00' WHERE id=?", publication, revision, content);
        return new Seed(content, revision);
    }

    private void insertRevision(String content, String revision, int number, String state) {
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) "
                + "VALUES(?,?,?,?,'测试标题','测试正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", revision, content, number, state);
    }

    private UserAccount author() {
        var author = LoginTestSupport.account(); author.setId(id()); author.setEmail("deletion-" + author.getId() + "@example.test");
        author.setEmailNormalized(author.getEmail()); users.insert(author); owners.add(author.getId()); roles.insertDefaultRole(author.getId(), author.getCreatedAt()); return author;
    }
    private String token(UserAccount author) { return sessions.create(author.getId(), 1, "deletion-test").accessToken(); }
    private HttpResponse<String> delete(String content, String version, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/creator/contents/" + content + "?expectedVersion=" + version))
                .timeout(Duration.ofSeconds(30)).DELETE();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertFalse(JsonPath.<Map<String, Object>>read(response.body(), "$").containsKey("data"));
    }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private record Seed(String content, String revision) { }
    private record Pending(String revision, String review) { }
}
