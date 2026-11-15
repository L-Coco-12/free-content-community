package com.freecontent.community.content.workspace.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.workspace.mapper.ContentRestorationMapper;
import com.freecontent.community.content.workspace.mapper.ContentRestorationRequestMapper;
import com.freecontent.community.content.workspace.ratelimit.ContentRestorationRateLimiter;
import com.freecontent.community.content.workspace.service.transaction.ContentRestorationTransactionService;
import com.freecontent.community.content.workspace.dto.VersionRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
class ContentRestorationFlowIT {
    private static final String PREFIX = "test:content-restoration:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private StringRedisTemplate redis;
    @MockitoSpyBean private ContentRestorationMapper writes;
    @MockitoSpyBean private ContentRestorationRequestMapper requests;
    @MockitoSpyBean private ContentRestorationRateLimiter limiter;
    @Autowired private ContentRestorationTransactionService transactions;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @Autowired private JsonMapper json;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) {
        for (String scope : List.of("content.restoration", "auth.login", "content.deletion", "content.publication-withdrawal", "content.publication-republish", "content.public-query",
                "content.public-media-access", "content.query", "user.public-query", "content.review-decision", "content.draft-creation", "content.draft-save"))
            registry.add("app." + scope + ".redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        reset(redis, writes, requests, limiter, authentication);
        var keys = redis.keys(PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='content_restoration_request'", Integer.class) == 1)
                jdbc.update("DELETE FROM content_restoration_request WHERE created_by=?", owner);
            jdbc.update("DELETE FROM content_review_decision_request WHERE created_by=?", owner);
            jdbc.update("DELETE FROM content_draft_creation_request WHERE created_by=?", owner);
            for (String table : List.of("content_review_decision_request", "content_review_decision_audit", "content_review_cancellation_audit",
                    "content_review_media_preview_audit", "content_media_preview_audit", "content_publication_outbox",
                    "content_publication_republish_audit", "content_publication_withdrawal_audit", "content_deletion_audit", "content_lifecycle_outbox", "content_restoration_audit")) {
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
    void restoresDeletedUnpublishedDraftWithoutPublishingOrChangingItsRevision() throws Exception {
        var author = author(); var seed = trashed(author, "UNPUBLISHED");
        var before = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision());
        var response = restore(seed.content(), Map.of("expectedVersion", 7), id(), token(author));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("ACTIVE", JsonPath.read(response.body(), "$.data.lifecycle"));
        assertEquals("UNPUBLISHED", JsonPath.read(response.body(), "$.data.publicationStatus"));
        assertEquals(8, JsonPath.<Integer>read(response.body(), "$.data.version"));
        assertNull(JsonPath.read(response.body(), "$.data.deletedAt"));
        assertEquals(seed.revision(), JsonPath.read(response.body(), "$.data.workingRevision.id"));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision()));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLISHED", "WITHDRAWN", "TAKEN_DOWN"})
    void historyRestoresWithoutPublishingAndKeepsTheDraftAndTakeDownMarker(String publication) throws Exception {
        var author = author(); var seed = trashed(author, publication); String draft = id();
        insertRevision(seed.content(), draft, 2, "DRAFT");
        jdbc.update("UPDATE community_content SET working_revision_id=? WHERE id=?", draft, seed.content());
        var history = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision());
        var working = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", draft);
        var dates = jdbc.queryForMap("SELECT first_published_at,published_at FROM community_content WHERE id=?", seed.content());
        var response = restore(seed.content(), Map.of("expectedVersion", 7), id(), token(author));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(publication.equals("TAKEN_DOWN") ? "TAKEN_DOWN" : "WITHDRAWN", JsonPath.read(response.body(), "$.data.publicationStatus"));
        assertEquals(draft, JsonPath.read(response.body(), "$.data.workingRevision.id"));
        assertEquals(seed.revision(), JsonPath.read(response.body(), "$.data.publishedRevision.id"));
        assertEquals(history, jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision()));
        assertEquals(working, jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", draft));
        assertEquals(dates, jdbc.queryForMap("SELECT first_published_at,published_at FROM community_content WHERE id=?", seed.content()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void residualPendingReviewCannotBeRevivedByRestoration(boolean hasActivePointer) throws Exception {
        var author = author(); var seed = trashed(author, "UNPUBLISHED");
        jdbc.update("UPDATE content_revision SET status='IN_REVIEW' WHERE id=?", seed.revision());
        String review = ContentTestSupport.pending(jdbc, seed.content(), seed.revision());
        if (hasActivePointer) jdbc.update("UPDATE community_content SET active_review_id=? WHERE id=?", review, seed.content());
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        problem(restore(seed.content(), Map.of("expectedVersion", 7), id(), token(author)), 503, "DEPENDENCY_UNAVAILABLE");
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
        assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM content_review WHERE id=?", String.class, review));
    }

    @Test
    void successfulSameKeyReplayReturnsOriginalSnapshotAndDoesNotRestoreADeletedAgainContent() throws Exception {
        var author = author(); var seed = trashed(author, "UNPUBLISHED"); String key = id(), token = token(author);
        var first = restore(seed.content(), Map.of("expectedVersion", 7), key, token); assertEquals(200, first.statusCode(), first.body());
        assertEquals(204, delete(seed.content(), "8", token).statusCode());
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        var replay = restore(seed.content(), Map.of("expectedVersion", 7), key, token);
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(JsonPath.<Map<String,Object>>read(first.body(), "$.data"), JsonPath.read(replay.body(), "$.data"));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
    }

    @Test
    void restorationAuditLifecycleEventAndSuccessfulSnapshotCommitTogether() throws Exception {
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('content_restoration_request','content_restoration_audit')", Integer.class));
        var author = author(); var seed = trashed(author, "PUBLISHED"); String key = id(), token = token(author);
        var response = restore(seed.content(), Map.of("expectedVersion", 7), key, token); assertEquals(200, response.statusCode(), response.body());
        var audit = jdbc.queryForMap("SELECT * FROM content_restoration_audit WHERE content_id=?", seed.content());
        assertEquals(author.getId(), audit.get("actor_id")); assertEquals(seed.revision(), audit.get("published_revision_id"));
        assertEquals("PUBLISHED", audit.get("before_publication_status")); assertEquals("WITHDRAWN", audit.get("after_publication_status"));
        assertEquals(7, audit.get("before_content_version")); assertEquals(8, audit.get("after_content_version"));
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), audit.get("request_id"));
        var event = jdbc.queryForMap("SELECT * FROM content_lifecycle_outbox WHERE content_id=?", seed.content());
        assertEquals("CONTENT_RESTORED", event.get("event_type")); assertEquals("PENDING", event.get("state"));
        assertEquals("ACTIVE", JsonPath.read((String) event.get("payload"), "$.lifecycle"));
        assertEquals("WITHDRAWN", JsonPath.read((String) event.get("payload"), "$.publicationStatus"));
        var record = jdbc.queryForMap("SELECT * FROM content_restoration_request WHERE created_by=? AND key_hash=?", author.getId(), TokenDigest.sha256(key));
        assertEquals("SUCCEEDED", record.get("state")); assertEquals(seed.content(), record.get("content_id"));
        assertNull(record.get("lease_token")); assertNull(record.get("lease_until"));
        assertEquals(JsonPath.<Map<String,Object>>read(response.body(), "$.data"), JsonPath.parse((String) record.get("result_snapshot")).read("$"));
        var expires = record.get("expires_at");
        assertEquals(200, restore(seed.content(), Map.of("expectedVersion", 7), key, token).statusCode());
        assertEquals(expires, jdbc.queryForObject("SELECT expires_at FROM content_restoration_request WHERE created_by=? AND key_hash=?", java.time.LocalDateTime.class, author.getId(), TokenDigest.sha256(key)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_restoration_audit WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_lifecycle_outbox WHERE content_id=?", Integer.class, seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOURCE", "USER"})
    void exhaustedRestorationQuotaDoesNotRestoreOrKeepAReservation(String scope) throws Exception {
        var author = author(); var seed = trashed(author, "UNPUBLISHED");
        redis.opsForValue().set(scope.equals("SOURCE") ? sourceKey() : userKey(author.getId()), scope.equals("SOURCE") ? "60" : "100", Duration.ofHours(1));
        var response = restore(seed.content(), Map.of("expectedVersion", 7), id(), token(author));
        problem(response, 429, "RATE_LIMITED"); assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
        assertEquals("DELETED", jdbc.queryForObject("SELECT lifecycle FROM community_content WHERE id=?", String.class, seed.content()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_restoration_request WHERE created_by=?", Integer.class, author.getId()));
    }

    @Test
    void successfulReplayIsExemptEvenWhenBothRestorationBudgetsAreExhausted() throws Exception {
        var author = author(); var seed = trashed(author, "UNPUBLISHED"); String key = id(), token = token(author);
        assertEquals(200, restore(seed.content(), Map.of("expectedVersion", 7), key, token).statusCode());
        assertEquals("1", redis.opsForValue().get(sourceKey())); assertEquals("1", redis.opsForValue().get(userKey(author.getId())));
        redis.opsForValue().set(sourceKey(), "60", Duration.ofMinutes(1)); redis.opsForValue().set(userKey(author.getId()), "100", Duration.ofHours(1));
        assertEquals(200, restore(seed.content(), Map.of("expectedVersion", 7), key, token).statusCode());
        assertEquals("60", redis.opsForValue().get(sourceKey())); assertEquals("100", redis.opsForValue().get(userKey(author.getId())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"publicationStatus", "publishedRevision"})
    void corruptSuccessSnapshotFailsClosedInsteadOfReturningAnInvalidWorkspace(String field) throws Exception {
        var author = author(); var seed = trashed(author, "PUBLISHED"); String key = id(), token = token(author);
        assertEquals(200, restore(seed.content(), Map.of("expectedVersion", 7), key, token).statusCode());
        jdbc.update("UPDATE content_restoration_request SET result_snapshot=JSON_SET(result_snapshot,?,CAST('null' AS JSON)) WHERE created_by=?", "$." + field, author.getId());
        problem(restore(seed.content(), Map.of("expectedVersion", 7), key, token), 503, "DEPENDENCY_UNAVAILABLE");
        assertEquals(8, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"VERSION", "PATH"})
    void theSameKeyCannotBeReusedForAnotherRequest(String change) throws Exception {
        var author = author(); var seed = trashed(author, "UNPUBLISHED"); String key = id(), token = token(author);
        assertEquals(200, restore(seed.content(), Map.of("expectedVersion", 7), key, token).statusCode());
        problem(restore(change.equals("PATH") ? trashed(author, "UNPUBLISHED").content() : seed.content(),
                Map.of("expectedVersion", change.equals("VERSION") ? 8 : 7), key, token), 409, "IDEMPOTENCY_KEY_REUSED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void liveLeaseBlocksRetriesAndExpiredLeaseCanBeTakenOver(boolean expired) throws Exception {
        var author = author(); var seed = trashed(author, "UNPUBLISHED"); String key = id(), token = token(author);
        var principal = authentication.authenticate(token);
        var claim = transactions.claim(seed.content(), input(7), TokenDigest.sha256(key), fingerprint(seed.content(), 7), principal);
        if (expired) jdbc.update("UPDATE content_restoration_request SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", claim.request().getId());
        var response = restore(seed.content(), Map.of("expectedVersion", 7), key, token);
        if (expired) {
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(com.freecontent.community.common.error.ErrorCode.REQUEST_IN_PROGRESS,
                    assertThrows(com.freecontent.community.common.error.BusinessException.class, () -> transactions.finish(claim, principal, "req_old")).errorCode());
            transactions.release(claim);
            assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT state FROM content_restoration_request WHERE id=?", String.class, claim.request().getId()));
        } else {
            problem(response, 409, "REQUEST_IN_PROGRESS"); assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
            assertNull(redis.opsForValue().get(sourceKey()));
        }
    }

    @Test
    void expired24HourKeyCanRestoreANewDeletionWithoutLosingOldAudits() throws Exception {
        var author = author(); var seed = trashed(author, "UNPUBLISHED"); String key = id(), token = token(author);
        assertEquals(200, restore(seed.content(), Map.of("expectedVersion", 7), key, token).statusCode());
        assertEquals(204, delete(seed.content(), "8", token).statusCode());
        jdbc.update("UPDATE content_restoration_request SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE created_by=?", author.getId());
        var response = restore(seed.content(), Map.of("expectedVersion", 9), key, token);
        assertEquals(200, response.statusCode(), response.body()); assertEquals(10, JsonPath.<Integer>read(response.body(), "$.data.version"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM content_restoration_audit WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM content_lifecycle_outbox WHERE content_id=?", Integer.class, seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TOKEN", "EXPIRED"})
    void staleClaimCannotFinishOrDeleteAReplacementReservation(String condition) throws Exception {
        var author = author(); var seed = trashed(author, "UNPUBLISHED"); String replacement = id();
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); call.callRealMethod();
            if (condition.equals("TOKEN")) jdbc.update("UPDATE content_restoration_request SET lease_token=? WHERE created_by=?", replacement, author.getId());
            else jdbc.update("UPDATE content_restoration_request SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE created_by=?", author.getId());
            return null;
        }).when(limiter).acquire(eq(author.getId()), anyString());
        problem(restore(seed.content(), Map.of("expectedVersion", 7), id(), token(author)), 409, "REQUEST_IN_PROGRESS");
        assertEquals("DELETED", jdbc.queryForObject("SELECT lifecycle FROM community_content WHERE id=?", String.class, seed.content()));
        if (condition.equals("TOKEN")) assertEquals(replacement, jdbc.queryForObject("SELECT lease_token FROM content_restoration_request WHERE created_by=?", String.class, author.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONTENT", "AUDIT", "EVENT", "SNAPSHOT", "SQL"})
    void everyWriteFailureRollsBackRestorationAuditEventAndSuccessSnapshot(String point) throws Exception {
        var author = author(); var seed = trashed(author, "PUBLISHED");
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        switch (point) {
            case "CONTENT" -> doReturn(0).when(writes).restore(anyString(), anyString(), anyInt(), any());
            case "AUDIT" -> doReturn(0).when(writes).insertAudit(any());
            case "EVENT" -> doReturn(0).when(writes).insertEvent(anyString(), anyString(), any(), anyInt(), anyString(), any());
            case "SNAPSHOT" -> doReturn(0).when(requests).complete(anyString(), anyString(), anyString(), anyString(), any());
            default -> doThrow(new TransientDataAccessResourceException("sensitive SQL detail")).when(writes).insertAudit(any());
        }
        var response = restore(seed.content(), Map.of("expectedVersion", 7), id(), token(author));
        problem(response, point.equals("CONTENT") || point.equals("SNAPSHOT") ? 409 : 503,
                point.equals("CONTENT") ? "CONTENT_VERSION_CONFLICT" : point.equals("SNAPSHOT") ? "REQUEST_IN_PROGRESS" : "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive")); assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
        for (String table : List.of("content_restoration_audit", "content_lifecycle_outbox")) assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_restoration_request WHERE created_by=?", Integer.class, author.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTH_VERSION", "UNVERIFIED", "DISABLED", "WRITE_BLOCKED", "NO_USER"})
    void qualificationIsRecheckedAfterClaimAndBeforeRestoring(String condition) throws Exception {
        var author = author(); var seed = trashed(author, "UNPUBLISHED");
        doAnswer(call -> {
            call.callRealMethod();
            switch (condition) {
                case "AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", author.getId());
                case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=false WHERE id=?", author.getId());
                case "NO_USER" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=?", author.getId());
                default -> jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", condition, author.getId());
            }
            return null;
        }).when(limiter).acquire(eq(author.getId()), anyString());
        problem(restore(seed.content(), Map.of("expectedVersion", 7), id(), token(author)), condition.equals("AUTH_VERSION") ? 401 : 403,
                condition.equals("AUTH_VERSION") ? "AUTHENTICATION_REQUIRED" : condition.equals("UNVERIFIED") || condition.equals("NO_USER") ? "PERMISSION_DENIED" : "ACCOUNT_RESTRICTED");
        assertEquals("DELETED", jdbc.queryForObject("SELECT lifecycle FROM community_content WHERE id=?", String.class, seed.content()));
        verifyNoInteractions(writes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "FOREIGN", "STALE", "MAX", "ACTIVE"})
    void inaccessibleOrConflictingTargetsDoNotChangeBusinessState(String condition) throws Exception {
        var author = author(); var seed = trashed(author, "UNPUBLISHED");
        String target = condition.equals("MISSING") ? id() : seed.content(), token = condition.equals("FOREIGN") ? token(author()) : token(author);
        int version = condition.equals("STALE") ? 6 : condition.equals("MAX") ? Integer.MAX_VALUE : 7;
        if (condition.equals("MAX")) jdbc.update("UPDATE community_content SET version=? WHERE id=?", Integer.MAX_VALUE, seed.content());
        if (condition.equals("ACTIVE")) jdbc.update("UPDATE community_content SET lifecycle='ACTIVE',deleted_at=NULL WHERE id=?", seed.content());
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        boolean invisible = condition.equals("MISSING") || condition.equals("FOREIGN");
        var response = restore(target, Map.of("expectedVersion", version), id(), token);
        problem(response, invisible ? 404 : 409, invisible ? "RESOURCE_NOT_FOUND" : condition.equals("ACTIVE") ? "CONTENT_NOT_EDITABLE" : "CONTENT_VERSION_CONFLICT");
        if (invisible) assertFalse(JsonPath.<Map<String,Object>>read(response.body(), "$").containsKey("currentVersion"));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_restoration_request WHERE created_by=?", Integer.class, author.getId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void redisFailureReleasesTheClaimOrLeavesARecoverableLeaseIfCleanupAlsoFails(boolean cleanupFails) throws Exception {
        var author = author(); var seed = trashed(author, "UNPUBLISHED"); String token = token(author), key = id();
        doThrow(new RedisConnectionFailureException("sensitive Redis detail")).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(), any());
        if (cleanupFails) doThrow(new TransientDataAccessResourceException("sensitive cleanup detail")).when(requests).release(anyString(), anyString());
        var response = restore(seed.content(), Map.of("expectedVersion", 7), key, token);
        problem(response, 503, "DEPENDENCY_UNAVAILABLE"); assertFalse(response.body().contains("sensitive"));
        assertEquals("DELETED", jdbc.queryForObject("SELECT lifecycle FROM community_content WHERE id=?", String.class, seed.content()));
        assertEquals(cleanupFails ? 1 : 0, jdbc.queryForObject("SELECT COUNT(*) FROM content_restoration_request WHERE created_by=?", Integer.class, author.getId()));
        reset(redis, requests);
        if (cleanupFails) jdbc.update("UPDATE content_restoration_request SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE created_by=?", author.getId());
        assertEquals(200, restore(seed.content(), Map.of("expectedVersion", 7), key, token).statusCode());
    }

    @Test
    void realDeleteThenRestoreDoesNotReviveTheInvalidatedReview() throws Exception {
        var author = author(); var seed = content(author, "PUBLISHED"); String revision = id(), token = token(author);
        insertRevision(seed.content(), revision, 2, "IN_REVIEW"); String review = ContentTestSupport.pending(jdbc, seed.content(), revision);
        jdbc.update("UPDATE content_review SET purpose='UPDATE' WHERE id=?", review);
        jdbc.update("UPDATE community_content SET working_revision_id=?,active_review_id=? WHERE id=?", revision, review, seed.content());
        assertEquals(204, delete(seed.content(), "7", token).statusCode());
        var reviewBefore = jdbc.queryForMap("SELECT * FROM content_review WHERE id=?", review);
        var revisionBefore = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", revision);
        var response = restore(seed.content(), Map.of("expectedVersion", 8), id(), token);
        assertEquals(200, response.statusCode(), response.body()); assertNull(JsonPath.read(response.body(), "$.data.activeReviewId")); assertNull(JsonPath.read(response.body(), "$.data.workingRevision"));
        assertEquals(reviewBefore, jdbc.queryForMap("SELECT * FROM content_review WHERE id=?", review));
        assertEquals(revisionBefore, jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", revision));
        assertEquals("INVALIDATED", reviewBefore.get("state")); assertEquals("CANCELLED", revisionBefore.get("status"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentRestorationsCommitOnceForSameOrDifferentKeys(boolean sameKey) throws Exception {
        var author = author(); var seed = trashed(author, "UNPUBLISHED"); String token = token(author), key = id(), secondKey = sameKey ? key : id();
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { start.await(); return restore(seed.content(), Map.of("expectedVersion", 7), key, token); });
            var second = pool.submit(() -> { start.await(); return restore(seed.content(), Map.of("expectedVersion", 7), secondKey, token); });
            start.countDown(); var one = first.get(40, TimeUnit.SECONDS); var two = second.get(40, TimeUnit.SECONDS);
            assertTrue(one.statusCode() == 200 || two.statusCode() == 200, one.body() + two.body());
            for (var result : List.of(one, two)) if (result.statusCode() != 200) problem(result, 409, sameKey ? "REQUEST_IN_PROGRESS" : "CONTENT_NOT_EDITABLE");
            assertEquals(8, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_restoration_audit WHERE content_id=?", Integer.class, seed.content()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_lifecycle_outbox WHERE content_id=?", Integer.class, seed.content()));
            assertEquals(sameKey ? "1" : "2", redis.opsForValue().get(sourceKey()));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void anOldDeleteConcurrentWithRestorationCannotDeleteTheRestoredVersion() throws Exception {
        var author = author(); var seed = trashed(author, "UNPUBLISHED"); String token = token(author), key = id();
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var restoring = pool.submit(() -> { start.await(); return restore(seed.content(), Map.of("expectedVersion", 7), key, token); });
            var deleting = pool.submit(() -> { start.await(); return delete(seed.content(), "7", token); });
            start.countDown(); var response = restoring.get(40, TimeUnit.SECONDS); var deleted = deleting.get(40, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode(), response.body());
            if (deleted.statusCode() != 204) problem(deleted, 409, "CONTENT_VERSION_CONFLICT");
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT lifecycle FROM community_content WHERE id=?", String.class, seed.content()));
            assertEquals(8, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void restorationRemainsPrivateUntilExplicitRepublicationAndReplayDoesNotUndoPublication() throws Exception {
        var author = author(); var seed = trashed(author, "PUBLISHED"); String token = token(author), key = id();
        var restored = restore(seed.content(), Map.of("expectedVersion", 7), key, token); assertEquals(200, restored.statusCode(), restored.body());
        problem(get("/api/v1/contents/" + seed.content()), 404, "RESOURCE_NOT_FOUND");
        assertEquals(List.of(), JsonPath.read(get("/api/v1/users/" + author.getId() + "/contents").body(), "$.data.items"));
        var publish = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/creator/contents/" + seed.content() + "/publication"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("expectedVersion", 8, "revisionId", seed.revision())))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, publish.statusCode(), publish.body()); assertEquals(200, get("/api/v1/contents/" + seed.content()).statusCode());
        assertEquals(200, restore(seed.content(), Map.of("expectedVersion", 7), key, token).statusCode());
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?", String.class, seed.content()));
        assertEquals(9, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
    }

    @Test
    void openApiMatchesTheStrictVersionRequestAndIdempotencyHeader() throws Exception {
        var response = get("/v3/api-docs"); assertEquals(200, response.statusCode(), response.body());
        Map<String,Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/creator/contents/{contentId}/restorations'].post");
        assertEquals("restoreMyContent", operation.get("operationId")); assertEquals(List.of(Map.of("bearerAuth", List.of())), operation.get("security"));
        Map<String,Object> schema = JsonPath.read(response.body(), "$.components.schemas.VersionRequest");
        assertEquals(java.util.Set.of("expectedVersion"), ((Map<?,?>) schema.get("properties")).keySet()); assertEquals(List.of("expectedVersion"), schema.get("required")); assertEquals(false, schema.get("additionalProperties"));
        List<Map<String,Object>> parameters = JsonPath.read(response.body(), "$.paths['/api/v1/creator/contents/{contentId}/restorations'].post.parameters");
        assertEquals(java.util.Set.of("contentId", "Idempotency-Key"), parameters.stream().map(parameter -> parameter.get("name")).collect(java.util.stream.Collectors.toSet()));
    }

    private HttpResponse<String> get(String path) throws Exception { return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(45)).GET().build(), HttpResponse.BodyHandlers.ofString()); }
    private VersionRequest input(int version) { return json.convertValue(Map.of("expectedVersion", version), VersionRequest.class); }
    private String fingerprint(String content, int version) {
        var values = new java.util.LinkedHashMap<String,Object>(); values.put("contentId", content); values.put("expectedVersion", version);
        return TokenDigest.sha256(json.writeValueAsString(values));
    }

    private String sourceKey() { return PREFIX + "{content-restoration}:source:" + TokenDigest.sha256("127.0.0.1"); }
    private String userKey(String actor) { return PREFIX + "{content-restoration}:user:" + TokenDigest.sha256(actor); }

    private Seed trashed(UserAccount author, String publication) {
        var seed = content(author, publication);
        jdbc.update("UPDATE community_content SET lifecycle='DELETED',deleted_at=UTC_TIMESTAMP(6) WHERE id=?", seed.content());
        return seed;
    }
    private HttpResponse<String> restore(String content, Map<String, Object> body, String key, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/creator/contents/" + content + "/restorations"))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (key != null) request.header("Idempotency-Key", key);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
