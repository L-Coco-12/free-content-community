package com.freecontent.community.content.workspace.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.workspace.mapper.PublicationWithdrawalMapper;
import com.freecontent.community.content.workspace.ratelimit.PublicationWithdrawalRateLimiter;
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
import java.util.concurrent.TimeUnit;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class PublicationWithdrawalFlowIT {
    private static final String PREFIX = "test:publication-withdrawal:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private StringRedisTemplate redis;
    @MockitoSpyBean private PublicationWithdrawalMapper writes;
    @MockitoSpyBean private PublicationWithdrawalRateLimiter limiter;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @Autowired private JsonMapper json;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) {
        for (String scope : List.of("auth.login", "content.publication-withdrawal", "content.public-query", "content.review-cancellation",
                "content.review-decision", "content.query", "user.public-query")) {
            registry.add("app." + scope + ".redis-key-prefix", () -> PREFIX);
        }
    }

    @AfterEach
    void cleanup() {
        reset(redis, writes, limiter, authentication);
        var keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) {
            jdbc.update("DELETE FROM content_review_decision_request WHERE created_by=?", owner);
            for (String table : List.of("content_review_decision_request", "content_review_decision_audit", "content_review_cancellation_audit",
                    "content_review_media_preview_audit", "content_media_preview_audit")) {
                jdbc.update("DELETE FROM " + table + " WHERE content_id IN (SELECT id FROM community_content WHERE author_id=?)", owner);
            }
            for (String table : List.of("content_publication_outbox", "content_publication_withdrawal_audit")) {
                if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", Integer.class, table) == 1) {
                    jdbc.update("DELETE FROM " + table + " WHERE content_id IN (SELECT id FROM community_content WHERE author_id=?)", owner);
                }
            }
            ContentTestSupport.clearReviews(jdbc, owner);
            ContentTestSupport.clearWorkspace(jdbc, owner);
        }
        for (String owner : owners) {
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner);
            users.deleteById(owner);
        }
    }

    @Test
    void withdrawingKeepsTheApprovedSnapshotAndPublicationHistory() throws Exception {
        var author = author();
        var seed = published(author);
        var before = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision());
        var dates = jdbc.queryForMap("SELECT first_published_at,published_at FROM community_content WHERE id=?", seed.content());
        var response = withdraw(seed.content(), "7", token(author));
        assertEquals(204, response.statusCode(), response.body());
        assertEquals("", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var row = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        assertEquals("WITHDRAWN", row.get("publication_status"));
        assertEquals("ACTIVE", row.get("lifecycle"));
        assertEquals(seed.revision(), row.get("published_revision_id"));
        assertEquals(8, row.get("version"));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision()));
        assertEquals(dates, jdbc.queryForMap("SELECT first_published_at,published_at FROM community_content WHERE id=?", seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WITHDRAWN", "UNPUBLISHED"})
    void alreadyNonPublicContentReturns204WithoutTouchingNewerPendingWork(String status) throws Exception {
        var author = author();
        var seed = published(author);
        jdbc.update("UPDATE community_content SET publication_status=? WHERE id=?", status, seed.content());
        if (status.equals("UNPUBLISHED")) {
            jdbc.update("UPDATE community_content SET published_revision_id=NULL,first_published_at=NULL,published_at=NULL WHERE id=?", seed.content());
            jdbc.update("UPDATE content_revision SET status='REJECTED' WHERE id=?", seed.revision());
        }
        var pending = working(seed.content(), "IN_REVIEW", status.equals("UNPUBLISHED") ? "INITIAL" : "UPDATE");
        var before = List.of(jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()),
                jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", pending.revision()),
                jdbc.queryForMap("SELECT * FROM content_review WHERE id=?", pending.review()));
        var response = withdraw(seed.content(), "1", token(author));
        assertEquals(204, response.statusCode(), response.body());
        assertEquals(before, List.of(jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()),
                jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", pending.revision()),
                jdbc.queryForMap("SELECT * FROM content_review WHERE id=?", pending.review())));
    }

    @Test
    void takenDownCannotBeOverwrittenEvenWithAnOldVersion() throws Exception {
        var author = author();
        var seed = published(author);
        jdbc.update("UPDATE community_content SET publication_status='TAKEN_DOWN' WHERE id=?", seed.content());
        problem(withdraw(seed.content(), "1", token(author)), 409, "REMEDIATION_REQUIRED");
        assertEquals("TAKEN_DOWN", jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?", String.class, seed.content()));
        assertEquals(7, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
    }

    @Test
    void withdrawingInvalidatesPendingReviewAndCancelsOnlyItsFrozenRevision() throws Exception {
        var author = author();
        var seed = published(author);
        var pending = working(seed.content(), "IN_REVIEW", "UPDATE");
        var frozen = jdbc.queryForMap("SELECT title,body_markdown,created_at FROM content_revision WHERE id=?", pending.revision());
        var response = withdraw(seed.content(), "7", token(author));
        assertEquals(204, response.statusCode(), response.body());
        var row = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        assertEquals("WITHDRAWN", row.get("publication_status"));
        assertNull(row.get("working_revision_id"));
        assertNull(row.get("active_review_id"));
        assertEquals(seed.revision(), row.get("published_revision_id"));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM content_revision WHERE id=?", String.class, pending.revision()));
        assertEquals(frozen, jdbc.queryForMap("SELECT title,body_markdown,created_at FROM content_revision WHERE id=?", pending.revision()));
        var review = jdbc.queryForMap("SELECT * FROM content_review WHERE id=?", pending.review());
        assertEquals("INVALIDATED", review.get("state"));
        assertEquals(4, review.get("version"));
        assertNotNull(review.get("decided_at"));
        assertNull(review.get("reviewer_id"));
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM content_revision WHERE id=?", String.class, seed.revision()));
    }

    @Test
    void anUnsubmittedDraftIsKeptEditableAfterWithdrawal() throws Exception {
        var author = author();
        var seed = published(author);
        var draft = working(seed.content(), "DRAFT", "UPDATE");
        var before = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", draft.revision());
        assertEquals(204, withdraw(seed.content(), "7", token(author)).statusCode());
        assertEquals(draft.revision(), jdbc.queryForObject("SELECT working_revision_id FROM community_content WHERE id=?", String.class, seed.content()));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", draft.revision()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualWithdrawalStoresAuditAndOutboxOnceAndRepeated204DoesNotWriteAgain(boolean withReview) throws Exception {
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                + "AND table_name IN ('content_publication_withdrawal_audit','content_publication_outbox')", Integer.class));
        var author = author();
        var seed = published(author);
        var pending = withReview ? working(seed.content(), "IN_REVIEW", "UPDATE") : null;
        String token = token(author);
        var response = withdraw(seed.content(), "7", token);
        assertEquals(204, response.statusCode(), response.body());
        var audit = jdbc.queryForMap("SELECT * FROM content_publication_withdrawal_audit WHERE content_id=?", seed.content());
        assertEquals(author.getId(), audit.get("actor_id"));
        assertEquals(seed.revision(), audit.get("published_revision_id"));
        assertEquals(7, audit.get("before_content_version"));
        assertEquals(8, audit.get("after_content_version"));
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), audit.get("request_id"));
        assertEquals(withReview ? pending.review() : null, audit.get("invalidated_review_id"));
        assertEquals(withReview ? pending.revision() : null, audit.get("cancelled_revision_id"));
        if (withReview) { assertEquals(3, audit.get("before_review_version")); assertEquals(4, audit.get("after_review_version")); }
        var event = jdbc.queryForMap("SELECT * FROM content_publication_outbox WHERE content_id=?", seed.content());
        assertEquals("CONTENT_WITHDRAWN", event.get("event_type"));
        assertEquals("PENDING", event.get("state"));
        assertEquals(8, event.get("content_version"));
        assertEquals(seed.revision(), event.get("published_revision_id"));
        assertEquals("WITHDRAWN", JsonPath.read((String) event.get("payload"), "$.publicationStatus"));
        assertEquals(withReview ? 1 : 0, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_outbox WHERE content_id=? "
                + "AND event_type='CONTENT_REVIEW_INVALIDATED'", Integer.class, seed.content()));
        assertEquals(204, withdraw(seed.content(), "7", token).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_withdrawal_audit WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_outbox WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(8, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOURCE", "USER"})
    void exhaustedWithdrawalBudgetsStopTheTransaction(String scope) throws Exception {
        var author = author();
        var seed = published(author);
        String key = scope.equals("SOURCE") ? sourceKey() : userKey(author.getId());
        redis.opsForValue().set(key, scope.equals("SOURCE") ? "120" : "60", Duration.ofMinutes(1));
        var response = withdraw(seed.content(), "7", token(author));
        problem(response, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?", String.class, seed.content()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_withdrawal_audit WHERE content_id=?", Integer.class, seed.content()));
        verifyNoInteractions(writes);
    }

    @Test
    void repeated204IsCountedButNeverAddsAnotherBusinessMutation() throws Exception {
        var author = author();
        var seed = published(author);
        String token = token(author);
        assertEquals(204, withdraw(seed.content(), "7", token).statusCode());
        assertEquals(204, withdraw(seed.content(), "7", token).statusCode());
        assertEquals("2", redis.opsForValue().get(sourceKey()));
        assertEquals("2", redis.opsForValue().get(userKey(author.getId())));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_withdrawal_audit WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(8, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
        assertNull(redis.opsForValue().get(PREFIX + "{content-review-cancellation}:user:" + TokenDigest.sha256(author.getId())));
    }

    private String sourceKey() { return PREFIX + "{content-publication-withdrawal}:source:" + TokenDigest.sha256("127.0.0.1"); }
    private String userKey(String actor) { return PREFIX + "{content-publication-withdrawal}:user:" + TokenDigest.sha256(actor); }

    @ParameterizedTest
    @ValueSource(strings = {"CANCEL", "INVALIDATE", "CONTENT", "AUDIT", "PUBLIC_EVENT", "REVIEW_EVENT"})
    void anyWriteFailureRollsBackPublicationReviewRevisionAuditAndEvents(String point) throws Exception {
        var author = author();
        var seed = published(author);
        var pending = working(seed.content(), "IN_REVIEW", "UPDATE");
        var before = relatedRows(seed, pending);
        switch (point) {
            case "CANCEL" -> doReturn(0).when(writes).cancelRevision(anyString(), anyString(), any());
            case "INVALIDATE" -> doReturn(0).when(writes).invalidateReview(anyString(), anyString(), anyString(), anyInt(), any());
            case "CONTENT" -> doReturn(0).when(writes).withdraw(anyString(), anyString(), anyInt(), any(), any());
            case "AUDIT" -> doReturn(0).when(writes).insertAudit(any());
            case "PUBLIC_EVENT" -> doReturn(0).when(writes).insertPublicationEvent(anyString(), anyString(), anyString(), anyInt(), anyString(), any());
            default -> doReturn(0).when(writes).insertReviewEvent(anyString(), any(), anyInt(), anyString(), any());
        }
        var response = withdraw(seed.content(), "7", token(author));
        problem(response, point.equals("CONTENT") ? 409 : 503, point.equals("CONTENT") ? "CONTENT_VERSION_CONFLICT" : "DEPENDENCY_UNAVAILABLE");
        assertEquals(before, relatedRows(seed, pending));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_withdrawal_audit WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_outbox WHERE content_id=?", Integer.class, seed.content()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_outbox WHERE content_id=?", Integer.class, seed.content()));
        reset(writes);
        assertEquals(204, withdraw(seed.content(), "7", token(author)).statusCode());
    }

    @Test
    void invalidatedReviewCannotPublishEvenWithFreshExpectedVersions() throws Exception {
        var author = author();
        var seed = published(author);
        var pending = working(seed.content(), "IN_REVIEW", "UPDATE");
        var moderator = moderator();
        assertEquals(204, withdraw(seed.content(), "7", token(author)).statusCode());
        problem(approve(pending.review(), 8, 4, token(moderator), id()), 409, "REVIEW_NOT_PENDING");
        assertEquals("WITHDRAWN", jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?", String.class, seed.content()));
        assertEquals(seed.revision(), jdbc.queryForObject("SELECT published_revision_id FROM community_content WHERE id=?", String.class, seed.content()));
    }

    @Test
    void withdrawalAndApprovalRaceCannotSilentlyUndoEachOther() throws Exception {
        var author = author();
        var seed = published(author);
        var pending = working(seed.content(), "IN_REVIEW", "UPDATE");
        String authorToken = token(author), moderatorToken = token(moderator());
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var removal = pool.submit(() -> { start.await(); return withdraw(seed.content(), "7", authorToken); });
            var approval = pool.submit(() -> { start.await(); return approve(pending.review(), 7, 3, moderatorToken, id()); });
            start.countDown();
            var removed = removal.get(40, TimeUnit.SECONDS);
            var approved = approval.get(40, TimeUnit.SECONDS);
            if (removed.statusCode() == 204) {
                problem(approved, 409, "CONTENT_VERSION_CONFLICT");
                assertEquals("INVALIDATED", jdbc.queryForObject("SELECT state FROM content_review WHERE id=?", String.class, pending.review()));
            } else {
                assertEquals(200, approved.statusCode(), approved.body());
                problem(removed, 409, "CONTENT_VERSION_CONFLICT");
                assertEquals(204, withdraw(seed.content(), "8", authorToken).statusCode());
            }
            assertEquals("WITHDRAWN", jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?", String.class, seed.content()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_withdrawal_audit WHERE content_id=?", Integer.class, seed.content()));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void twoConcurrentWithdrawalsProduceOnlyOneMutationAndOneAudit() throws Exception {
        var author = author();
        var seed = published(author);
        String token = token(author);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { start.await(); return withdraw(seed.content(), "7", token); });
            var second = pool.submit(() -> { start.await(); return withdraw(seed.content(), "7", token); });
            start.countDown();
            assertEquals(204, first.get(40, TimeUnit.SECONDS).statusCode());
            assertEquals(204, second.get(40, TimeUnit.SECONDS).statusCode());
            assertEquals(8, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_withdrawal_audit WHERE content_id=?", Integer.class, seed.content()));
            assertEquals("2", redis.opsForValue().get(sourceKey()));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void approvalReplayCannotRepublishAfterAnAuthorHasWithdrawn() throws Exception {
        var author = author();
        var seed = published(author);
        var pending = working(seed.content(), "IN_REVIEW", "UPDATE");
        String moderatorToken = token(moderator()), key = id();
        assertEquals(200, approve(pending.review(), 7, 3, moderatorToken, key).statusCode());
        assertEquals(204, withdraw(seed.content(), "8", token(author)).statusCode());
        assertEquals(200, approve(pending.review(), 7, 3, moderatorToken, key).statusCode());
        assertEquals("WITHDRAWN", jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?", String.class, seed.content()));
        assertEquals(9, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
    }

    @Test
    void withdrawalStopsPublicReadsButKeepsTheAuthorsWorkspace() throws Exception {
        var author = author();
        var seed = published(author);
        String token = token(author);
        assertEquals(200, get("/api/v1/contents/" + seed.content(), null).statusCode());
        assertEquals(204, withdraw(seed.content(), "7", token).statusCode());
        problem(get("/api/v1/contents/" + seed.content(), null), 404, "RESOURCE_NOT_FOUND");
        var list = get("/api/v1/users/" + author.getId() + "/contents", null);
        assertEquals(200, list.statusCode(), list.body());
        assertEquals(List.of(), JsonPath.read(list.body(), "$.data.items"));
        var profile = get("/api/v1/users/" + author.getId(), null);
        assertEquals(0, JsonPath.<Integer>read(profile.body(), "$.data.publishedContentCount"));
        var workspace = get("/api/v1/creator/contents/" + seed.content(), token);
        assertEquals(200, workspace.statusCode(), workspace.body());
        assertEquals("WITHDRAWN", JsonPath.read(workspace.body(), "$.data.publicationStatus"));
        assertEquals(seed.revision(), JsonPath.read(workspace.body(), "$.data.publishedRevision.id"));
    }

    @Test
    void anOldRetryCannotWithdrawANewPublicationAndNewCyclesHaveDistinctAuditVersions() throws Exception {
        var author = author();
        var seed = published(author);
        String token = token(author);
        assertEquals(204, withdraw(seed.content(), "7", token).statusCode());
        jdbc.update("UPDATE community_content SET publication_status='PUBLISHED',version=version+1 WHERE id=?", seed.content());
        problem(withdraw(seed.content(), "7", token), 409, "CONTENT_VERSION_CONFLICT");
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?", String.class, seed.content()));
        assertEquals(204, withdraw(seed.content(), "9", token).statusCode());
        assertEquals(List.of(8, 10), jdbc.queryForList("SELECT after_content_version FROM content_publication_withdrawal_audit WHERE content_id=? ORDER BY after_content_version", Integer.class, seed.content()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_outbox WHERE content_id=?", Integer.class, seed.content()));
    }

    @Test
    void maximumVersionBlocksMutationButDoesNotBlockNoOpSuccess() throws Exception {
        var author = author();
        var seed = published(author);
        jdbc.update("UPDATE community_content SET version=? WHERE id=?", Integer.MAX_VALUE, seed.content());
        String token = token(author);
        problem(withdraw(seed.content(), Integer.toString(Integer.MAX_VALUE), token), 409, "CONTENT_VERSION_CONFLICT");
        jdbc.update("UPDATE community_content SET publication_status='WITHDRAWN' WHERE id=?", seed.content());
        assertEquals(204, withdraw(seed.content(), "1", token).statusCode());
        assertEquals(Integer.MAX_VALUE, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_withdrawal_audit WHERE content_id=?", Integer.class, seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETED", "MISSING", "FOREIGN", "STALE"})
    void inaccessibleOrConflictingContentIsNeverMutated(String state) throws Exception {
        var author = author();
        var seed = published(author);
        String target = seed.content(), token = token(author), version = "7", code = "CONTENT_VERSION_CONFLICT";
        int status = 409;
        if (state.equals("DELETED")) { jdbc.update("UPDATE community_content SET lifecycle='DELETED' WHERE id=?", target); code = "CONTENT_NOT_EDITABLE"; }
        else if (state.equals("MISSING")) { target = id(); status = 404; code = "RESOURCE_NOT_FOUND"; }
        else if (state.equals("FOREIGN")) { token = token(author()); status = 404; code = "RESOURCE_NOT_FOUND"; }
        else version = "6";
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        var response = withdraw(target, version, token);
        problem(response, status, code);
        if (status == 404) assertFalse(JsonPath.<Map<String, Object>>read(response.body(), "$").containsKey("currentVersion"));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLISHED_POINTER", "ACTIVE_REVIEW", "WORKING_POINTER", "TERMINAL_REVIEW", "REVIEW_MAX"})
    void inconsistentBindingsOrUnadvanceableReviewDoNotPartiallyWithdraw(String issue) throws Exception {
        var author = author();
        var seed = published(author);
        var pending = issue.equals("PUBLISHED_POINTER") ? null : working(seed.content(), "IN_REVIEW", "UPDATE");
        switch (issue) {
            case "PUBLISHED_POINTER" -> jdbc.update("UPDATE community_content SET published_revision_id=NULL WHERE id=?", seed.content());
            case "ACTIVE_REVIEW" -> jdbc.update("UPDATE community_content SET active_review_id=NULL WHERE id=?", seed.content());
            case "WORKING_POINTER" -> jdbc.update("UPDATE community_content SET working_revision_id=NULL WHERE id=?", seed.content());
            case "TERMINAL_REVIEW" -> jdbc.update("UPDATE content_review SET state='CANCELLED' WHERE id=?", pending.review());
            default -> jdbc.update("UPDATE content_review SET version=? WHERE id=?", Integer.MAX_VALUE, pending.review());
        }
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        problem(withdraw(seed.content(), "7", token(author)), issue.equals("REVIEW_MAX") ? 409 : 503,
                issue.equals("REVIEW_MAX") ? "CONTENT_NOT_EDITABLE" : "DEPENDENCY_UNAVAILABLE");
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_withdrawal_audit WHERE content_id=?", Integer.class, seed.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTH_VERSION", "UNVERIFIED", "DISABLED", "WRITE_BLOCKED", "NO_USER"})
    void eligibilityIsRecheckedAfterAuthentication(String change) throws Exception {
        var author = author();
        var seed = published(author);
        String token = token(author);
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
        problem(withdraw(seed.content(), "7", token), change.equals("AUTH_VERSION") ? 401 : 403,
                change.equals("AUTH_VERSION") ? "AUTHENTICATION_REQUIRED" : change.equals("UNVERIFIED") || change.equals("NO_USER") ? "PERMISSION_DENIED" : "ACCOUNT_RESTRICTED");
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?", String.class, seed.content()));
        verifyNoInteractions(writes);
    }

    @Test
    void unavailableRedisCannotStartTheWithdrawal() throws Exception {
        var author = author();
        var seed = published(author);
        doThrow(new RedisConnectionFailureException("sensitive Redis detail")).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(), any());
        var result = withdraw(seed.content(), "7", token(author));
        problem(result, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(result.body().contains("sensitive"));
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?", String.class, seed.content()));
        verifyNoInteractions(writes);
    }

    @Test
    void openApiDeclaresThePrivateDeleteAndNoRequestOrSuccessBody() throws Exception {
        var response = get("/v3/api-docs", null);
        assertEquals(200, response.statusCode());
        Map<String, Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/creator/contents/{contentId}/publication'].delete");
        assertEquals("withdrawMyPublication", operation.get("operationId"));
        assertEquals(List.of(Map.of("bearerAuth", List.of())), operation.get("security"));
        assertFalse(operation.containsKey("requestBody"));
        assertEquals(java.util.Set.of("204", "400", "401", "403", "404", "409", "422", "429", "500", "503"), ((Map<?, ?>) operation.get("responses")).keySet());
        Map<?, ?> success = (Map<?, ?>) ((Map<?, ?>) operation.get("responses")).get("204");
        assertFalse(success.containsKey("content"));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(45)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private List<Object> relatedRows(Seed seed, Pending pending) {
        return List.of(jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()),
                jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", pending.revision()),
                jdbc.queryForMap("SELECT * FROM content_review WHERE id=?", pending.review()));
    }

    private UserAccount moderator() {
        var actor = author();
        jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,'MODERATOR',UTC_TIMESTAMP(6))", actor.getId());
        return actor;
    }

    private HttpResponse<String> approve(String review, int contentVersion, int reviewVersion, String token, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/reviews/" + review + "/decisions"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("expectedContentVersion", contentVersion, "expectedReviewVersion", reviewVersion, "decision", "APPROVE", "reason", "测试通过")))).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Pending working(String content, String state, String purpose) {
        String revision = id(), review = state.equals("IN_REVIEW") ? id() : null;
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) "
                + "VALUES(?,?,2,?,'新版标题','新版正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", revision, content, state);
        if (review != null) jdbc.update("INSERT INTO content_review(id,content_id,revision_id,purpose,state,version,submitted_at) "
                + "VALUES(?,?,?,?,'PENDING',3,UTC_TIMESTAMP(6))", review, content, revision, purpose);
        jdbc.update("UPDATE community_content SET working_revision_id=?,active_review_id=? WHERE id=?", revision, review, content);
        return new Pending(revision, review);
    }

    private Seed published(UserAccount author) {
        String content = id(), revision = id();
        jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) "
                + "VALUES(?,?,'ARTICLE','ACTIVE','UNPUBLISHED',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", content, author.getId());
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) "
                + "VALUES(?,?,1,'APPROVED','公开作品','公开正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", revision, content);
        jdbc.update("UPDATE community_content SET publication_status='PUBLISHED',published_revision_id=?,"
                + "first_published_at='2026-11-03 08:00:00',published_at='2026-11-04 08:00:00' WHERE id=?", revision, content);
        return new Seed(content, revision);
    }

    private UserAccount author() {
        var author = LoginTestSupport.account();
        author.setId(id());
        author.setEmail("publication-withdrawal-" + author.getId() + "@example.test");
        author.setEmailNormalized(author.getEmail());
        users.insert(author);
        owners.add(author.getId());
        roles.insertDefaultRole(author.getId(), author.getCreatedAt());
        return author;
    }

    private String token(UserAccount author) { return sessions.create(author.getId(), 1, "withdrawal-test").accessToken(); }

    private HttpResponse<String> withdraw(String content, String version, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/creator/contents/" + content
                + "/publication?expectedVersion=" + version)).timeout(Duration.ofSeconds(30)).DELETE();
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
    private record Pending(String revision, String review) { }
}
