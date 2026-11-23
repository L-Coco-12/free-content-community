package com.freecontent.community.content.moderation.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.mapper.ContentReadMapper;
import com.freecontent.community.content.moderation.mapper.AdminContentQueryMapper;
import com.freecontent.community.content.moderation.mapper.ContentTakeDownMapper;
import com.freecontent.community.content.moderation.mapper.ContentTakeDownRequestMapper;
import com.freecontent.community.content.moderation.ratelimit.ContentTakeDownRateLimiter;
import com.freecontent.community.content.moderation.service.transaction.ContentTakeDownTransactionService;
import com.freecontent.community.content.moderation.dto.TakeDownRequest;
import com.freecontent.community.notification.mapper.NotificationWriteMapper;
import com.freecontent.community.notification.service.ModerationNotificationWriter;
import tools.jackson.databind.json.JsonMapper;
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
import org.junit.jupiter.params.provider.CsvSource;
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
import org.mybatis.spring.SqlSessionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class ContentTakeDownFlowIT {
    private static final String PREFIX = "test:content-take-down:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private StringRedisTemplate redis;
    @MockitoSpyBean private AdminContentQueryMapper queries;
    @MockitoSpyBean private ContentTakeDownMapper writes;
    @MockitoSpyBean private ContentTakeDownRequestMapper requests;
    @MockitoSpyBean private ContentTakeDownRateLimiter limiter;
    @MockitoSpyBean private NotificationWriteMapper notices;
    @Autowired private ContentTakeDownTransactionService transactions;
    @Autowired private ModerationNotificationWriter notifications;
    @Autowired private JsonMapper json;
    @MockitoSpyBean private ContentReadMapper reads;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @Autowired private SqlSessionTemplate sqlSession;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) {
        for (String scope : List.of("content.take-down", "auth.login", "content.admin-query", "content.admin-review-query", "content.query", "content.draft-save",
                "content.review-decision","content.review-submission","content.restoration","content.deletion","content.draft-creation","content.publication-republish","content.publication-withdrawal","content.moderation-query"))
            registry.add("app." + scope + ".redis-key-prefix", () -> PREFIX);
    }
    @AfterEach
    void cleanup() {
        reset(redis, queries, reads, writes, requests, limiter, notices, authentication);
        var keys = redis.keys(PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) {
            for (String table : List.of("content_take_down_request","content_restoration_request","content_draft_creation_request","content_review_decision_request"))
                if (tableExists(table)) jdbc.update("DELETE FROM " + table + " WHERE created_by=?",owner);
            if (tableExists("user_notification")) jdbc.update("DELETE FROM user_notification WHERE recipient_id=?",owner);
        }
        for (String owner : owners) {
            for (String table : List.of("content_take_down_audit","content_publication_outbox","content_lifecycle_outbox","content_restoration_audit","content_deletion_audit","content_publication_withdrawal_audit","content_publication_republish_audit","content_review_decision_audit","content_review_decision_request"))
                if (tableExists(table)) jdbc.update("DELETE FROM " + table + " WHERE content_id IN (SELECT id FROM community_content WHERE author_id=?)",owner);
            if (tableExists("content_moderation_action")) jdbc.update("DELETE FROM content_moderation_action WHERE content_id IN (SELECT id FROM community_content WHERE author_id=?)",owner);
            ContentTestSupport.clearReviews(jdbc, owner); ContentTestSupport.clearWorkspace(jdbc, owner);
        }
        for (String owner : owners) {
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?", owner); jdbc.update("DELETE FROM community_tag WHERE created_by=?", owner);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner); users.deleteById(owner);
        }
    }
    @Test
    void moderatorTakesDownPublishedContentAndReceivesAnActionResult() throws Exception {
        var moderator = account("MODERATOR"); var author = account(null); var seed = content(author,"ACTIVE","PUBLISHED");
        var before = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?",seed.revision());
        String token = token(moderator);
        var response = takeDown(seed.content(),7,"SPAM","经核对存在违规营销，需整改后重审。",id(),token);
        assertEquals(201,response.statusCode(),response.body());
        assertEquals("CONTENT",JsonPath.read(response.body(),"$.data.targetType")); assertEquals(seed.content(),JsonPath.read(response.body(),"$.data.targetId"));
        assertEquals("TAKE_DOWN",JsonPath.read(response.body(),"$.data.action")); assertEquals(moderator.getId(),JsonPath.read(response.body(),"$.data.operatorId"));
        assertEquals(8,JsonPath.<Integer>read(response.body(),"$.data.resultingVersion"));
        assertEquals("TAKEN_DOWN",jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?",String.class,seed.content()));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT lifecycle FROM community_content WHERE id=?",String.class,seed.content()));
        assertEquals(before,jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?",seed.revision()));
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        String actionId = JsonPath.read(response.body(), "$.data.id");
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/moderation-actions/" + actionId))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token).GET().build();
        var persisted = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, persisted.statusCode(), persisted.body());
        assertEquals(JsonPath.<Map<String, Object>>read(response.body(), "$.data"),
                JsonPath.<Map<String, Object>>read(persisted.body(), "$.data"));
    }
    @ParameterizedTest
    @CsvSource({"ACTIVE,PUBLISHED,DRAFT", "ACTIVE,WITHDRAWN,IN_REVIEW", "DELETED,PUBLISHED,DRAFT", "DELETED,WITHDRAWN,IN_REVIEW"})
    void takeDownCancelsEveryWorkingRevisionAndInvalidatesPendingReviewWithoutChangingLifecycle(String lifecycle,String publication,String state) throws Exception {
        var moderator = account("ADMIN"); var seed = content(account(null),lifecycle,publication); var work = working(seed,state,"UPDATE");
        var frozen = jdbc.queryForMap("SELECT title,body_markdown,created_at FROM content_revision WHERE id=?",work.revision());
        var dates = jdbc.queryForMap("SELECT first_published_at,published_at,deleted_at,published_revision_id FROM community_content WHERE id=?",seed.content());
        var response = takeDown(seed.content(),7,"ABUSE","内容需要整改。",id(),token(moderator)); assertEquals(201,response.statusCode(),response.body());
        var after = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?",seed.content());
        assertEquals(lifecycle,after.get("lifecycle")); assertEquals("TAKEN_DOWN",after.get("publication_status")); assertEquals(8,after.get("version"));
        assertNull(after.get("working_revision_id")); assertNull(after.get("active_review_id"));
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM content_revision WHERE id=?",String.class,work.revision()));
        assertEquals(frozen,jdbc.queryForMap("SELECT title,body_markdown,created_at FROM content_revision WHERE id=?",work.revision()));
        assertEquals(dates,jdbc.queryForMap("SELECT first_published_at,published_at,deleted_at,published_revision_id FROM community_content WHERE id=?",seed.content()));
        if (work.review() != null) {
            var review = jdbc.queryForMap("SELECT * FROM content_review WHERE id=?",work.review());
            assertEquals("INVALIDATED",review.get("state")); assertEquals(2,review.get("version")); assertNull(review.get("reviewer_id")); assertNotNull(review.get("decided_at"));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"DRAFT", "IN_REVIEW"})
    void aNewKeyCannotTakeDownAnAlreadyTakenDownContentOrCancelLaterRemediation(String state) throws Exception {
        var moderator = account("MODERATOR"); var seed = content(account(null),"ACTIVE","TAKEN_DOWN"); var work = working(seed,state,"REMEDIATION");
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?",seed.content());
        var revision = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?",work.revision());
        problem(takeDown(seed.content(),7,"OTHER","再次处置",id(),token(moderator)),409,"CONTENT_NOT_EDITABLE");
        assertEquals(before,jdbc.queryForMap("SELECT * FROM community_content WHERE id=?",seed.content())); assertEquals(revision,jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?",work.revision()));
    }
    @Test
    void neverPublishedContentCannotBeTakenDown() throws Exception {
        var moderator = account("MODERATOR"); var seed = content(account(null),"ACTIVE","UNPUBLISHED");
        problem(takeDown(seed.content(),7,"OTHER","不能处置未发布草稿",id(),token(moderator)),409,"CONTENT_NOT_EDITABLE");
        assertEquals("DRAFT",jdbc.queryForObject("SELECT status FROM content_revision WHERE id=?",String.class,seed.revision()));
    }
    private Work working(Seed seed,String state,String purpose) {
        String revision = id(), review = null;
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,2,?,'工作标题','工作正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",revision,seed.content(),state);
        if (state.equals("IN_REVIEW")) { review = ContentTestSupport.pending(jdbc,seed.content(),revision); jdbc.update("UPDATE content_review SET purpose=? WHERE id=?",purpose,review); }
        jdbc.update("UPDATE community_content SET working_revision_id=?,active_review_id=? WHERE id=?",revision,review,seed.content());
        return new Work(revision,review);
    }
    private record Work(String revision,String review) { }
    @ParameterizedTest @ValueSource(strings = {"NONE","DRAFT","IN_REVIEW"})
    void actionAuditAuthorNotificationEventsAndSuccessfulSnapshotAreDurableTogether(String state) throws Exception {
        assertTrue(tableExists("content_moderation_action")); assertTrue(tableExists("content_take_down_request")); assertTrue(tableExists("content_take_down_audit")); assertTrue(tableExists("user_notification"));
        var moderator = account("MODERATOR"); var author = account(null); var seed = content(author,"ACTIVE","PUBLISHED");
        var work = state.equals("NONE") ? null : working(seed,state,"UPDATE"); String key = id(), token = token(moderator);
        var response = takeDown(seed.content(),7,"COPYRIGHT","版权问题需要整改。",key,token); assertEquals(201,response.statusCode(),response.body());
        String actionId = JsonPath.read(response.body(),"$.data.id");
        var action = jdbc.queryForMap("SELECT * FROM content_moderation_action WHERE id=?",actionId);
        assertEquals(seed.content(),action.get("content_id")); assertEquals("COPYRIGHT",action.get("reason_code")); assertEquals("版权问题需要整改。",action.get("reason")); assertEquals(8,action.get("resulting_version"));
        var audit = jdbc.queryForMap("SELECT * FROM content_take_down_audit WHERE action_id=?",actionId);
        assertEquals(moderator.getId(),audit.get("operator_id")); assertEquals(7,audit.get("before_content_version")); assertEquals(8,audit.get("after_content_version"));
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(),audit.get("request_id"));
        assertEquals(work == null ? null : work.revision(),audit.get("cancelled_revision_id"));
        assertEquals(work == null ? null : work.review(),audit.get("invalidated_review_id"));
        var event = jdbc.queryForMap("SELECT * FROM content_publication_outbox WHERE content_id=?",seed.content());
        assertEquals("CONTENT_TAKEN_DOWN",event.get("event_type")); assertEquals("PENDING",event.get("state"));
        var notice = jdbc.queryForMap("SELECT * FROM user_notification WHERE recipient_id=?",author.getId());
        assertEquals("MODERATION_RESULT",notice.get("type")); assertEquals("CONTENT",notice.get("target_type")); assertEquals(seed.content(),notice.get("target_id"));
        assertEquals(event.get("id"),notice.get("source_event_id")); assertNull(notice.get("read_at")); assertTrue(((String)notice.get("body")).contains("版权问题"));
        assertEquals(state.equals("IN_REVIEW") ? 1 : 0,jdbc.queryForObject("SELECT COUNT(*) FROM content_review_outbox WHERE content_id=? AND event_type='CONTENT_REVIEW_INVALIDATED'",Integer.class,seed.content()));
        var request = jdbc.queryForMap("SELECT * FROM content_take_down_request WHERE created_by=? AND key_hash=?",moderator.getId(),TokenDigest.sha256(key));
        assertEquals("SUCCEEDED",request.get("state")); assertEquals(actionId,request.get("action_id")); assertNull(request.get("lease_token")); assertNull(request.get("lease_until"));
        assertEquals(201,takeDown(seed.content(),7,"COPYRIGHT","版权问题需要整改。",key,token).statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_moderation_action WHERE content_id=?",Integer.class,seed.content()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE recipient_id=?",Integer.class,author.getId()));
    }
    @Test
    void sameKeyReplayReturnsOriginalActionWithoutCancellingLaterRemediation() throws Exception {
        var moderator = account("MODERATOR"); var seed = content(account(null),"ACTIVE","PUBLISHED"); String key = id(), token = token(moderator);
        var first = takeDown(seed.content(),7,"SPAM","需要整改",key,token); assertEquals(201,first.statusCode(),first.body());
        var work = working(seed,"IN_REVIEW","REMEDIATION"); jdbc.update("UPDATE community_content SET version=10 WHERE id=?",seed.content());
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?",seed.content());
        var replay = takeDown(seed.content(),7,"SPAM","需要整改",key,token); assertEquals(201,replay.statusCode(),replay.body());
        assertEquals(JsonPath.<Map<String,Object>>read(first.body(),"$.data"),JsonPath.read(replay.body(),"$.data"));
        assertEquals(before,jdbc.queryForMap("SELECT * FROM community_content WHERE id=?",seed.content()));
        assertEquals("PENDING",jdbc.queryForObject("SELECT state FROM content_review WHERE id=?",String.class,work.review()));
    }
    @ParameterizedTest @ValueSource(strings = {"SOURCE","USER"})
    void exhaustedTakeDownQuotaStopsAllBusinessChangesAndReleasesTheClaim(String scope) throws Exception {
        var moderator = account("MODERATOR"); var seed = content(account(null),"ACTIVE","PUBLISHED");
        redis.opsForValue().set(scope.equals("SOURCE") ? sourceKey() : userKey(moderator.getId()),scope.equals("SOURCE") ? "60" : "100",Duration.ofHours(1));
        problem(takeDown(seed.content(),7,"SPAM","需要整改",id(),token(moderator)),429,"RATE_LIMITED");
        assertEquals("PUBLISHED",jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?",String.class,seed.content()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_take_down_request WHERE created_by=?",Integer.class,moderator.getId()));
        verifyNoInteractions(writes);
    }
    @Test
    void successfulReplayIsFreeAndDoesNotShareReviewOrReadBudgets() throws Exception {
        var moderator = account("MODERATOR"); var seed = content(account(null),"ACTIVE","PUBLISHED"); String key = id(), token = token(moderator);
        redis.opsForValue().set(PREFIX + "{content-review-decision}:source:" + TokenDigest.sha256("127.0.0.1"),"60",Duration.ofMinutes(1));
        redis.opsForValue().set(PREFIX + "{content-admin-query}:source:" + TokenDigest.sha256("127.0.0.1"),"120",Duration.ofMinutes(1));
        assertEquals(201,takeDown(seed.content(),7,"SPAM","需要整改",key,token).statusCode());
        assertEquals("1",redis.opsForValue().get(sourceKey())); assertEquals("1",redis.opsForValue().get(userKey(moderator.getId())));
        redis.opsForValue().set(sourceKey(),"60",Duration.ofMinutes(1)); redis.opsForValue().set(userKey(moderator.getId()),"100",Duration.ofHours(1));
        assertEquals(201,takeDown(seed.content(),7,"SPAM","需要整改",key,token).statusCode());
        assertEquals("60",redis.opsForValue().get(sourceKey())); assertEquals("100",redis.opsForValue().get(userKey(moderator.getId())));
    }
    @Test
    void fullUnicodeReasonIsPreservedAndOnlyTheNoticeSummaryIsTruncated() throws Exception {
        var moderator = account("MODERATOR"); var author = account(null); var seed = content(author,"ACTIVE","PUBLISHED"); String reason = "😀".repeat(1000);
        var response = takeDown(seed.content(),7,"OTHER",reason,id(),token(moderator)); assertEquals(201,response.statusCode(),response.body());
        assertEquals(reason,JsonPath.read(response.body(),"$.data.reason"));
        assertEquals(reason,jdbc.queryForObject("SELECT reason FROM content_moderation_action WHERE content_id=?",String.class,seed.content()));
        String body = jdbc.queryForObject("SELECT body FROM user_notification WHERE recipient_id=?",String.class,author.getId());
        assertEquals(500,body.codePointCount(0,body.length())); assertTrue(body.endsWith("…")); assertFalse(body.contains("后台正文"));
    }
    @ParameterizedTest @ValueSource(strings = {"WORK","REVIEW","CONTENT","ACTION","AUDIT","NOTICE","PUBLIC_EVENT","REVIEW_EVENT","SNAPSHOT","SQL"})
    void anyWriteFailureRollsBackContentWorkReviewActionNoticeEventsAndSuccess(String point) throws Exception {
        var moderator = account("MODERATOR"); var author = account(null); var seed = content(author,"ACTIVE","PUBLISHED"); var work = working(seed,"IN_REVIEW","UPDATE");
        var before = related(seed,work);
        switch (point) {
            case "WORK" -> doReturn(0).when(writes).cancelWorking(anyString(),anyString(),any());
            case "REVIEW" -> doReturn(0).when(writes).invalidateReview(anyString(),anyString(),anyString(),anyInt(),any());
            case "CONTENT" -> doReturn(0).when(writes).takeDown(anyString(),anyInt(),any());
            case "ACTION" -> doReturn(0).when(writes).insertAction(any());
            case "AUDIT" -> doReturn(0).when(writes).insertAudit(any());
            case "NOTICE" -> doReturn(0).when(notices).insertModerationResult(any());
            case "PUBLIC_EVENT" -> doReturn(0).when(writes).insertPublicationEvent(anyString(),anyString(),anyString(),anyInt(),anyString(),any());
            case "REVIEW_EVENT" -> doReturn(0).when(writes).insertReviewEvent(anyString(),any(),anyInt(),anyString(),any());
            case "SNAPSHOT" -> doReturn(0).when(requests).complete(anyString(),anyString(),anyString(),anyString(),anyString(),any());
            default -> doThrow(new TransientDataAccessResourceException("sensitive SQL detail")).when(writes).insertAction(any());
        }
        var response = takeDown(seed.content(),7,"SPAM","需要整改",id(),token(moderator));
        problem(response,point.equals("CONTENT") || point.equals("SNAPSHOT") ? 409 : 503,
                point.equals("CONTENT") ? "CONTENT_VERSION_CONFLICT" : point.equals("SNAPSHOT") ? "REQUEST_IN_PROGRESS" : "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive")); assertEquals(before,related(seed,work));
        for (String table : List.of("content_moderation_action","content_take_down_audit","content_publication_outbox","content_review_outbox"))
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE content_id=?",Integer.class,seed.content()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE recipient_id=?",Integer.class,author.getId()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_take_down_request WHERE created_by=?",Integer.class,moderator.getId()));
    }
    @ParameterizedTest @ValueSource(strings = {"AUTH_VERSION","UNVERIFIED","DISABLED","WRITE_BLOCKED","NO_ROLE"})
    void eligibilityIsRecheckedAfterClaimAndBeforeAnyContentChange(String condition) throws Exception {
        var moderator = account("MODERATOR"); var seed = content(account(null),"ACTIVE","PUBLISHED");
        doAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()); call.callRealMethod();
            switch (condition) {
                case "AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?",moderator.getId());
                case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=false WHERE id=?",moderator.getId());
                case "NO_ROLE" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code IN ('MODERATOR','ADMIN')",moderator.getId());
                default -> jdbc.update("UPDATE app_user SET account_status=? WHERE id=?",condition,moderator.getId());
            }
            return null;
        }).when(limiter).acquire(eq(moderator.getId()),anyString());
        problem(takeDown(seed.content(),7,"SPAM","需要整改",id(),token(moderator)),condition.equals("AUTH_VERSION") ? 401 : 403,
                condition.equals("AUTH_VERSION") ? "AUTHENTICATION_REQUIRED" : condition.equals("UNVERIFIED") || condition.equals("NO_ROLE") ? "PERMISSION_DENIED" : "ACCOUNT_RESTRICTED");
        assertEquals("PUBLISHED",jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?",String.class,seed.content())); verifyNoInteractions(writes,notices);
    }
    @ParameterizedTest @ValueSource(strings = {"MISSING","STALE","MAX"})
    void targetExistenceAndExpectedVersionAreProtected(String condition) throws Exception {
        var moderator = account("MODERATOR"); var seed = content(account(null),"ACTIVE","PUBLISHED");
        int version = condition.equals("MAX") ? Integer.MAX_VALUE : condition.equals("STALE") ? 6 : 7;
        if (condition.equals("MAX")) jdbc.update("UPDATE community_content SET version=? WHERE id=?",Integer.MAX_VALUE,seed.content());
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?",seed.content());
        var response = takeDown(condition.equals("MISSING") ? id() : seed.content(),version,"SPAM","需要整改",id(),token(moderator));
        problem(response,condition.equals("MISSING") ? 404 : 409,condition.equals("MISSING") ? "RESOURCE_NOT_FOUND" : "CONTENT_VERSION_CONFLICT");
        assertEquals(before,jdbc.queryForMap("SELECT * FROM community_content WHERE id=?",seed.content()));
    }
    @ParameterizedTest @ValueSource(strings = {"PUBLISHED_POINTER","PUBLISHED_STATUS","WORKING_POINTER","REVIEW_POINTER","REVIEW_REVISION","REVIEW_MAX"})
    void corruptBindingsCannotBePartiallyTakenDown(String issue) throws Exception {
        var moderator = account("MODERATOR"); var seed = content(account(null),"ACTIVE","PUBLISHED"); var work = working(seed,"IN_REVIEW","UPDATE");
        switch (issue) {
            case "PUBLISHED_POINTER" -> jdbc.update("UPDATE community_content SET published_revision_id=NULL WHERE id=?",seed.content());
            case "PUBLISHED_STATUS" -> jdbc.update("UPDATE content_revision SET status='REJECTED' WHERE id=?",seed.revision());
            case "WORKING_POINTER" -> jdbc.update("UPDATE community_content SET working_revision_id=NULL WHERE id=?",seed.content());
            case "REVIEW_POINTER" -> jdbc.update("UPDATE community_content SET active_review_id=NULL WHERE id=?",seed.content());
            case "REVIEW_REVISION" -> jdbc.update("UPDATE content_review SET revision_id=? WHERE id=?",seed.revision(),work.review());
            default -> jdbc.update("UPDATE content_review SET version=? WHERE id=?",Integer.MAX_VALUE,work.review());
        }
        var before = related(seed,work);
        problem(takeDown(seed.content(),7,"SPAM","需要整改",id(),token(moderator)),503,"DEPENDENCY_UNAVAILABLE"); assertEquals(before,related(seed,work));
    }
    @Test
    void notificationCapabilityRequiresTheParentTransaction() {
        var author = account(null); var seed = content(author,"ACTIVE","PUBLISHED");
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,() -> notifications.create(author.getId(),seed.content(),id(),"原因",java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));
        verifyNoInteractions(notices);
    }
    @Test
    void activeModeratorWithoutCreatorRoleCanTakeDownOwnContent() throws Exception {
        var moderator = account("MODERATOR"); var seed = content(moderator,"ACTIVE","PUBLISHED");
        jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='USER'",moderator.getId());
        var response = takeDown(seed.content(),7,"OTHER","本人作品也按处置流程整改",id(),token(moderator)); assertEquals(201,response.statusCode(),response.body());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE recipient_id=?",Integer.class,moderator.getId()));
    }
    @ParameterizedTest @ValueSource(strings = {"VERSION","CODE","REASON","PATH"})
    void successfulKeyCannotBeReusedForDifferentBusinessParameters(String change) throws Exception {
        var moderator = account("MODERATOR"); var author = account(null); var seed = content(author,"ACTIVE","PUBLISHED"); String token = token(moderator), key = id();
        assertEquals(201,takeDown(seed.content(),7,"SPAM","原原因",key,token).statusCode());
        String target = change.equals("PATH") ? content(author,"ACTIVE","PUBLISHED").content() : seed.content();
        problem(takeDown(target,change.equals("VERSION") ? 8 : 7,change.equals("CODE") ? "OTHER" : "SPAM",change.equals("REASON") ? "新原因" : "原原因",key,token),409,"IDEMPOTENCY_KEY_REUSED");
        assertEquals("1",redis.opsForValue().get(sourceKey()));
    }
    @ParameterizedTest @ValueSource(booleans = {false,true})
    void liveLeaseBlocksAndExpiredLeaseCanBeTakenOver(boolean expired) throws Exception {
        var moderator = account("MODERATOR"); var seed = content(account(null),"ACTIVE","PUBLISHED"); String token = token(moderator), key = id();
        var actor = authentication.authenticate(token);
        var claim = transactions.claim(seed.content(),input(7,"SPAM","需要整改"),TokenDigest.sha256(key),fingerprint(seed.content(),7,"SPAM","需要整改"),actor);
        if (expired) jdbc.update("UPDATE content_take_down_request SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",claim.request().getId());
        var response = takeDown(seed.content(),7,"SPAM","需要整改",key,token);
        if (expired) {
            assertEquals(201,response.statusCode(),response.body());
            assertEquals(com.freecontent.community.common.error.ErrorCode.REQUEST_IN_PROGRESS,assertThrows(com.freecontent.community.common.error.BusinessException.class,() -> transactions.finish(claim,actor,"req_old")).errorCode());
            transactions.release(claim); assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT state FROM content_take_down_request WHERE id=?",String.class,claim.request().getId()));
        } else { problem(response,409,"REQUEST_IN_PROGRESS"); assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0); assertNull(redis.opsForValue().get(sourceKey())); }
    }
    @ParameterizedTest @ValueSource(strings = {"TOKEN","EXPIRED"})
    void staleFinisherCannotChangeContentOrReleaseAReplacementLease(String change) throws Exception {
        var moderator = account("MODERATOR"); var seed = content(account(null),"ACTIVE","PUBLISHED"); String replacement = id();
        doAnswer(call -> {
            call.callRealMethod();
            if (change.equals("TOKEN")) jdbc.update("UPDATE content_take_down_request SET lease_token=? WHERE created_by=?",replacement,moderator.getId());
            else jdbc.update("UPDATE content_take_down_request SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE created_by=?",moderator.getId());
            return null;
        }).when(limiter).acquire(eq(moderator.getId()),anyString());
        problem(takeDown(seed.content(),7,"SPAM","需要整改",id(),token(moderator)),409,"REQUEST_IN_PROGRESS");
        assertEquals("PUBLISHED",jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?",String.class,seed.content()));
        if (change.equals("TOKEN")) assertEquals(replacement,jdbc.queryForObject("SELECT lease_token FROM content_take_down_request WHERE created_by=?",String.class,moderator.getId()));
    }
    @Test
    void expired24HourKeyCanBeUsedForANewTargetWithoutDeletingOldBusinessFacts() throws Exception {
        var moderator = account("MODERATOR"); var author = account(null); var one = content(author,"ACTIVE","PUBLISHED"); var two = content(author,"ACTIVE","PUBLISHED"); String key = id(), token = token(moderator);
        var first = takeDown(one.content(),7,"SPAM","需要整改",key,token); assertEquals(201,first.statusCode(),first.body());
        jdbc.update("UPDATE content_take_down_request SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE created_by=?",moderator.getId());
        assertEquals(201,takeDown(two.content(),7,"COPYRIGHT","另一个处置原因",key,token).statusCode());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM content_moderation_action WHERE operator_id=?",Integer.class,moderator.getId()));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE recipient_id=?",Integer.class,author.getId()));
    }
    @ParameterizedTest @ValueSource(booleans = {false,true})
    void redisFailureReleasesClaimOrLeavesARecoverableReservation(boolean cleanupFails) throws Exception {
        var moderator = account("MODERATOR"); var seed = content(account(null),"ACTIVE","PUBLISHED"); String token = token(moderator), key = id();
        doThrow(new RedisConnectionFailureException("sensitive Redis detail")).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(),any());
        if (cleanupFails) doThrow(new TransientDataAccessResourceException("sensitive cleanup detail")).when(requests).release(anyString(),anyString());
        var response = takeDown(seed.content(),7,"SPAM","需要整改",key,token); problem(response,503,"DEPENDENCY_UNAVAILABLE"); assertFalse(response.body().contains("sensitive"));
        assertEquals("PUBLISHED",jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?",String.class,seed.content()));
        assertEquals(cleanupFails ? 1 : 0,jdbc.queryForObject("SELECT COUNT(*) FROM content_take_down_request WHERE created_by=?",Integer.class,moderator.getId()));
        reset(redis,requests);
        if (cleanupFails) jdbc.update("UPDATE content_take_down_request SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE created_by=?",moderator.getId());
        assertEquals(201,takeDown(seed.content(),7,"SPAM","需要整改",key,token).statusCode());
    }
    @Test
    void corruptSuccessfulSnapshotIs503AndDoesNotCreateAnotherActionOrNotice() throws Exception {
        var moderator = account("MODERATOR"); var author = account(null); var seed = content(author,"ACTIVE","PUBLISHED"); String token = token(moderator), key = id();
        assertEquals(201,takeDown(seed.content(),7,"SPAM","需要整改",key,token).statusCode());
        jdbc.update("UPDATE content_take_down_request SET result_snapshot=JSON_SET(result_snapshot,'$.targetId','forged') WHERE created_by=?",moderator.getId());
        problem(takeDown(seed.content(),7,"SPAM","需要整改",key,token),503,"DEPENDENCY_UNAVAILABLE");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_moderation_action WHERE content_id=?",Integer.class,seed.content()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE recipient_id=?",Integer.class,author.getId()));
    }
    @Test
    void sameKeyConcurrentAbsentClaimsRecoverFromUniqueKeyRaceAndCommitOnlyOnce() throws Exception {
        var moderator = account("MODERATOR"); var author = account(null); var seed = content(author,"ACTIVE","PUBLISHED"); String key = id(), token = token(moderator);
        var emptyClaims = new CountDownLatch(2);
        doAnswer(call -> {
            com.freecontent.community.content.moderation.entity.ContentTakeDownRequestRecord record = sqlSession.selectOne("com.freecontent.community.content.moderation.mapper.ContentTakeDownRequestMapper.lockByKey",Map.<String,Object>of("actor",call.getArgument(0),"key",call.getArgument(1)));
            if (record == null && emptyClaims.getCount() > 0) { emptyClaims.countDown(); assertTrue(emptyClaims.await(10,TimeUnit.SECONDS)); }
            return record;
        }).when(requests).lockByKey(eq(moderator.getId()),eq(TokenDigest.sha256(key)));
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { start.await(); return takeDown(seed.content(),7,"SPAM","需要整改",key,token); });
            var second = pool.submit(() -> { start.await(); return takeDown(seed.content(),7,"SPAM","需要整改",key,token); });
            start.countDown(); var one = first.get(45,TimeUnit.SECONDS); var two = second.get(45,TimeUnit.SECONDS);
            assertTrue(one.statusCode() == 201 || two.statusCode() == 201,one.body()+two.body());
            for (var response : List.of(one,two)) if (response.statusCode() != 201) problem(response,409,"REQUEST_IN_PROGRESS");
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_moderation_action WHERE content_id=?",Integer.class,seed.content()));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE recipient_id=?",Integer.class,author.getId())); assertEquals("1",redis.opsForValue().get(sourceKey()));
        } finally { pool.shutdownNow(); }
    }
    @Test
    void twoOperatorsTakingDownTheSameVersionCreateOnlyOneAction() throws Exception {
        var mod1 = account("MODERATOR"); var mod2 = account("ADMIN"); var seed = content(account(null),"ACTIVE","PUBLISHED"); String token1=token(mod1),token2=token(mod2),key1=id(),key2=id();
        var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            var one=pool.submit(() -> {start.await();return takeDown(seed.content(),7,"SPAM","需要整改",key1,token1);});
            var two=pool.submit(() -> {start.await();return takeDown(seed.content(),7,"SPAM","需要整改",key2,token2);}); start.countDown();
            var first=one.get(45,TimeUnit.SECONDS); var second=two.get(45,TimeUnit.SECONDS);
            assertTrue(first.statusCode()==201 || second.statusCode()==201,first.body()+second.body());
            for(var result:List.of(first,second)) if(result.statusCode()!=201) problem(result,409,"CONTENT_NOT_EDITABLE");
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_moderation_action WHERE content_id=?",Integer.class,seed.content()));
        } finally {pool.shutdownNow();}
    }
    @Test
    void operatorsCanConcurrentlyModerateEachOthersContentWithoutAnAccountLockCycle() throws Exception {
        var mod1=account("MODERATOR"); var mod2=account("MODERATOR"); var one=content(mod1,"ACTIVE","PUBLISHED"); var two=content(mod2,"ACTIVE","PUBLISHED");
        String token1=token(mod1),token2=token(mod2),key1=id(),key2=id(); var ready=new CountDownLatch(2);
        doAnswer(call -> {ready.countDown();assertTrue(ready.await(10,TimeUnit.SECONDS));call.callRealMethod();return null;}).when(limiter).acquire(anyString(),anyString());
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(() -> takeDown(two.content(),7,"ABUSE","需要整改",key1,token1));
            var second=pool.submit(() -> takeDown(one.content(),7,"ABUSE","需要整改",key2,token2));
            for(var result:List.of(first,second)){var response=result.get(45,TimeUnit.SECONDS);assertEquals(201,response.statusCode(),response.body());}
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE recipient_id=?",Integer.class,mod1.getId()));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE recipient_id=?",Integer.class,mod2.getId()));
        } finally {pool.shutdownNow();}
    }
    @Test
    void approvalAndTakeDownCannotBothConsumeTheSameVersion() throws Exception {
        var moderator=account("MODERATOR"); var seed=content(account(null),"ACTIVE","PUBLISHED"); var work=working(seed,"IN_REVIEW","UPDATE");
        String token=token(moderator),approvalKey=id(),takeDownKey=id(); var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            var down=pool.submit(() -> {start.await();return takeDown(seed.content(),7,"SPAM","需要整改",takeDownKey,token);});
            var approve=pool.submit(() -> {start.await();return post("/api/v1/admin/reviews/"+work.review()+"/decisions",Map.of("expectedContentVersion",7,"expectedReviewVersion",1,"decision","APPROVE","reason","审核通过"),approvalKey,token);});
            start.countDown();var taken=down.get(45,TimeUnit.SECONDS);var approved=approve.get(45,TimeUnit.SECONDS);
            if(taken.statusCode()==201){problem(approved,409,"CONTENT_VERSION_CONFLICT");assertEquals("INVALIDATED",jdbc.queryForObject("SELECT state FROM content_review WHERE id=?",String.class,work.review()));}
            else {assertEquals(200,approved.statusCode(),approved.body());problem(taken,409,"CONTENT_VERSION_CONFLICT");assertEquals(201,takeDown(seed.content(),8,"SPAM","复核新版本后处置",id(),token).statusCode());}
            assertEquals("TAKEN_DOWN",jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?",String.class,seed.content()));
        } finally {pool.shutdownNow();}
    }
    @Test
    void realRemediationReviewCanPublishANewRevisionAndOldTakeDownReplayDoesNotUndoIt() throws Exception {
        var moderator=account("MODERATOR"); var author=account(null); var seed=content(author,"ACTIVE","PUBLISHED"); String modToken=token(moderator),authorToken=token(author),key=id();
        var original=takeDown(seed.content(),7,"SPAM","需要整改",key,modToken);assertEquals(201,original.statusCode(),original.body());
        var draft=post("/api/v1/creator/contents/"+seed.content()+"/drafts",Map.of("expectedVersion",8),id(),authorToken);assertEquals(201,draft.statusCode(),draft.body());
        String revision=JsonPath.read(draft.body(),"$.data.workingRevision.id");assertNotEquals(seed.revision(),revision);
        var submitted=post("/api/v1/creator/contents/"+seed.content()+"/reviews",Map.of("expectedVersion",9,"revisionId",revision),id(),authorToken);assertEquals(201,submitted.statusCode(),submitted.body());
        assertEquals("REMEDIATION",JsonPath.read(submitted.body(),"$.data.review.purpose"));String review=JsonPath.read(submitted.body(),"$.data.review.id");
        var approved=post("/api/v1/admin/reviews/"+review+"/decisions",Map.of("expectedContentVersion",10,"expectedReviewVersion",1,"decision","APPROVE","reason","整改审核通过"),key,modToken);assertEquals(200,approved.statusCode(),approved.body());
        var before=jdbc.queryForMap("SELECT * FROM community_content WHERE id=?",seed.content());assertEquals("PUBLISHED",before.get("publication_status"));assertEquals(revision,before.get("published_revision_id"));
        var replay=takeDown(seed.content(),7,"SPAM","需要整改",key,modToken);assertEquals(201,replay.statusCode(),replay.body());
        assertEquals(JsonPath.<Map<String,Object>>read(original.body(),"$.data"),JsonPath.read(replay.body(),"$.data"));assertEquals(before,jdbc.queryForMap("SELECT * FROM community_content WHERE id=?",seed.content()));
    }
    @Test
    void deletedContentKeepsTheTakeDownMarkerAfterRealRestorationAndCannotRepublishOldApproval() throws Exception {
        var moderator=account("MODERATOR");var author=account(null);var seed=content(author,"DELETED","PUBLISHED");String authorToken=token(author);
        assertEquals(201,takeDown(seed.content(),7,"COPYRIGHT","需要整改",id(),token(moderator)).statusCode());
        var restored=post("/api/v1/creator/contents/"+seed.content()+"/restorations",Map.of("expectedVersion",8),id(),authorToken);assertEquals(200,restored.statusCode(),restored.body());
        assertEquals("TAKEN_DOWN",JsonPath.read(restored.body(),"$.data.publicationStatus"));
        var publish=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/creator/contents/"+seed.content()+"/publication"))
                .header("Authorization","Bearer "+authorToken).header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("expectedVersion",9,"revisionId",seed.revision())))).build(),HttpResponse.BodyHandlers.ofString());
        problem(publish,409,"REMEDIATION_REQUIRED");
    }
    @Test
    void takeDownRemovesPublicVisibilityAndKeepsTheAuthorsWorkspaceAndMediaMetadata() throws Exception {
        var moderator=account("MODERATOR");var author=account(null);var seed=content(author,"ACTIVE","PUBLISHED");String asset=id();
        jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) VALUES(?,?,'CONTENT_IMAGE','image.png','image/png',1,?,'READY',UTC_TIMESTAMP(6))",asset,author.getId(),"take-down/"+asset);
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'IMAGE',0)",seed.revision(),asset);
        var media=jdbc.queryForMap("SELECT * FROM media_asset WHERE id=?",asset);
        assertEquals(200,getPath("/api/v1/contents/"+seed.content(),null).statusCode());assertEquals(201,takeDown(seed.content(),7,"SPAM","需要整改",id(),token(moderator)).statusCode());
        problem(getPath("/api/v1/contents/"+seed.content(),null),404,"RESOURCE_NOT_FOUND");
        problem(getPath("/api/v1/contents/"+seed.content()+"/media/"+asset+"/access",null),404,"RESOURCE_NOT_FOUND");
        assertEquals(List.of(),JsonPath.read(getPath("/api/v1/users/"+author.getId()+"/contents",null).body(),"$.data.items"));
        assertEquals(0,JsonPath.<Integer>read(getPath("/api/v1/users/"+author.getId(),null).body(),"$.data.publishedContentCount"));
        assertEquals("TAKEN_DOWN",JsonPath.read(getPath("/api/v1/creator/contents/"+seed.content(),token(author)).body(),"$.data.publicationStatus"));
        assertEquals(media,jdbc.queryForMap("SELECT * FROM media_asset WHERE id=?",asset));
    }
    @Test
    void auditCannotLoseReviewVersionEvidence() throws Exception {
        var moderator=account("MODERATOR");var seed=content(account(null),"ACTIVE","PUBLISHED");working(seed,"IN_REVIEW","UPDATE");
        assertEquals(201,takeDown(seed.content(),7,"SPAM","需要整改",id(),token(moderator)).statusCode());
        var error=assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update("UPDATE content_take_down_audit SET before_review_version=NULL,after_review_version=NULL WHERE content_id=?",seed.content()));
        var sql=assertInstanceOf(java.sql.SQLException.class,error.getCause());assertEquals(3819,sql.getErrorCode());assertTrue(sql.getMessage().contains("chk_take_down_audit_review"));
    }
    @Test
    void openApiDeclaresTheTakeDownRequestAndActionResult() throws Exception {
        var response=getPath("/v3/api-docs",null);assertEquals(200,response.statusCode(),response.body());
        Map<String,Object> operation=JsonPath.read(response.body(),"$.paths['/api/v1/admin/contents/{contentId}/take-downs'].post");assertEquals("takeDownContent",operation.get("operationId"));
        assertEquals(List.of(Map.of("bearerAuth",List.of())),operation.get("security"));
        Map<String,Object> request=JsonPath.read(response.body(),"$.components.schemas.TakeDownRequest");assertEquals(java.util.Set.of("expectedVersion","reasonCode","reason"),((Map<?,?>)request.get("properties")).keySet());
        assertEquals(java.util.Set.of("expectedVersion","reasonCode","reason"),new java.util.HashSet<>((List<?>)request.get("required")));assertEquals(false,request.get("additionalProperties"));
        assertNotNull(JsonPath.read(response.body(),"$.paths['/api/v1/admin/contents/{contentId}/take-downs'].post.responses['201'].content['application/json'].schema['$ref']"));
    }
    private HttpResponse<String> post(String path,Map<String,Object> body,String key,String token) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(40)).header("Authorization","Bearer "+token).header("Idempotency-Key",key).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> getPath(String path,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(45)).GET();if(token!=null)request.header("Authorization","Bearer "+token);
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private TakeDownRequest input(int version,String code,String reason) { return json.convertValue(Map.of("expectedVersion",version,"reasonCode",code,"reason",reason),TakeDownRequest.class); }
    private String fingerprint(String content,int version,String code,String reason) {
        var fields = new java.util.LinkedHashMap<String,Object>(); fields.put("contentId",content); fields.put("expectedVersion",version); fields.put("reasonCode",code); fields.put("reason",reason);
        return TokenDigest.sha256(json.writeValueAsString(fields));
    }
    private List<Object> related(Seed seed,Work work) {
        return List.of(jdbc.queryForMap("SELECT * FROM community_content WHERE id=?",seed.content()),jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?",work.revision()),jdbc.queryForMap("SELECT * FROM content_review WHERE id=?",work.review()));
    }
    private String sourceKey() { return PREFIX + "{content-take-down}:source:" + TokenDigest.sha256("127.0.0.1"); }
    private String userKey(String actor) { return PREFIX + "{content-take-down}:user:" + TokenDigest.sha256(actor); }
    private boolean tableExists(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",Integer.class,table) == 1; }
    private HttpResponse<String> takeDown(String content,int version,String reasonCode,String reason,String key,String token) throws Exception {
        String body = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of("expectedVersion",version,"reasonCode",reasonCode,"reason",reason));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/contents/" + content + "/take-downs"))
                .timeout(Duration.ofSeconds(40)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) request.header("Idempotency-Key",key); if (token != null) request.header("Authorization","Bearer " + token);
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private Seed content(UserAccount author, String lifecycle, String publication) {
        String content = id(), revision = id();
        jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) "
                + "VALUES(?,?,'ARTICLE',?,'UNPUBLISHED',7,UTC_TIMESTAMP(6),'2035-01-01 08:00:00')", content, author.getId(), lifecycle);
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) "
                + "VALUES(?,?,1,?,'后台标题','后台正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", revision, content, publication.equals("UNPUBLISHED") ? "DRAFT" : "APPROVED");
        if (publication.equals("UNPUBLISHED")) jdbc.update("UPDATE community_content SET working_revision_id=? WHERE id=?", revision, content);
        else jdbc.update("UPDATE community_content SET publication_status=?,published_revision_id=?,first_published_at='2026-11-03 08:00:00',published_at='2026-11-04 08:00:00' WHERE id=?", publication, revision, content);
        if (lifecycle.equals("DELETED")) jdbc.update("UPDATE community_content SET deleted_at=UTC_TIMESTAMP(6) WHERE id=?", content);
        return new Seed(content, revision);
    }
    private UserAccount account(String role) {
        var actor = LoginTestSupport.account(); actor.setId(id()); actor.setEmail("admin-content-" + actor.getId() + "@example.test"); actor.setEmailNormalized(actor.getEmail());
        users.insert(actor); owners.add(actor.getId()); roles.insertDefaultRole(actor.getId(), actor.getCreatedAt());
        if (role != null) jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,?,UTC_TIMESTAMP(6))", actor.getId(), role);
        return actor;
    }
    private String token(UserAccount actor) { return sessions.create(actor.getId(), 1, "admin-content-test").accessToken(); }
    private HttpResponse<String> list(String query, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/contents" + query)).timeout(Duration.ofSeconds(45)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertFalse(JsonPath.<Map<String,Object>>read(response.body(), "$").containsKey("data"));
    }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private record Seed(String content, String revision) { }
}
