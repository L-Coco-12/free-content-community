package com.freecontent.community.content.moderation.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.mapper.ContentReadMapper;
import com.freecontent.community.content.moderation.mapper.AdminContentQueryMapper;
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
class AdminContentQueryFlowIT {
    private static final String PREFIX = "test:admin-content-query:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private StringRedisTemplate redis;
    @MockitoSpyBean private AdminContentQueryMapper queries;
    @MockitoSpyBean private ContentReadMapper reads;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @Autowired private SqlSessionTemplate sqlSession;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) {
        for (String scope : List.of("auth.login", "content.admin-query", "content.admin-review-query", "content.query"))
            registry.add("app." + scope + ".redis-key-prefix", () -> PREFIX);
    }
    @AfterEach
    void cleanup() {
        reset(redis, queries, reads, authentication);
        var keys = redis.keys(PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) { ContentTestSupport.clearReviews(jdbc, owner); ContentTestSupport.clearWorkspace(jdbc, owner); }
        for (String owner : owners) {
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?", owner); jdbc.update("DELETE FROM community_tag WHERE created_by=?", owner);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner); users.deleteById(owner);
        }
    }
    @Test
    void moderatorCanReadOwnAndOtherAuthorsDraftsPublicationsAndDeletedContentByDefault() throws Exception {
        var moderator = account("MODERATOR"); var author = account(null);
        var mine = content(moderator, "ACTIVE", "UNPUBLISHED"); var foreign = content(author, "ACTIVE", "PUBLISHED"); var deleted = content(author, "DELETED", "PUBLISHED");
        var response = list("", token(moderator)); assertEquals(200, response.statusCode(), response.body());
        List<String> ids = JsonPath.read(response.body(), "$.data.items[*].id");
        assertTrue(ids.containsAll(List.of(mine.content(), foreign.content(), deleted.content())), response.body());
        assertEquals(1, JsonPath.<Integer>read(response.body(), "$.data.page")); assertEquals(20, JsonPath.<Integer>read(response.body(), "$.data.pageSize"));
        assertTrue(JsonPath.<Integer>read(response.body(), "$.data.total") >= 3);
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }
    @ParameterizedTest
    @CsvSource({"ACTIVE,UNPUBLISHED", "ACTIVE,PUBLISHED", "ACTIVE,WITHDRAWN", "ACTIVE,TAKEN_DOWN", "DELETED,UNPUBLISHED", "DELETED,PUBLISHED", "DELETED,WITHDRAWN", "DELETED,TAKEN_DOWN"})
    void authorLifecycleAndPublicationFiltersAreCombinedWithAnd(String lifecycle, String publication) throws Exception {
        var moderator = account("MODERATOR"); var author = account(null); var other = account(null);
        var target = content(author, lifecycle, publication);
        content(author, lifecycle.equals("ACTIVE") ? "DELETED" : "ACTIVE", publication);
        content(author, lifecycle, publication.equals("UNPUBLISHED") ? "PUBLISHED" : "UNPUBLISHED"); content(other, lifecycle, publication);
        var response = list("?authorId=" + author.getId() + "&lifecycle=" + lifecycle + "&publicationStatus=" + publication, token(moderator));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of(target.content()), JsonPath.read(response.body(), "$.data.items[*].id")); assertEquals(1, JsonPath.<Integer>read(response.body(), "$.data.total"));
    }
    @Test
    void paginationUsesUpdatedTimeThenIdDescendingAndAcceptsHugeOutOfRangePage() throws Exception {
        var moderator = account("ADMIN"); var author = account(null); String token = token(moderator);
        var older1 = content(author, "ACTIVE", "UNPUBLISHED"); var older2 = content(author, "DELETED", "PUBLISHED"); var newer = content(author, "ACTIVE", "WITHDRAWN");
        jdbc.update("UPDATE community_content SET updated_at='2035-01-02 08:00:00' WHERE id=?", newer.content());
        var oldIds = new ArrayList<>(List.of(older1.content(), older2.content())); oldIds.sort(java.util.Comparator.reverseOrder());
        String query = "?authorId=" + author.getId() + "&pageSize=2";
        var first = list(query, token); assertEquals(200, first.statusCode(), first.body());
        assertEquals(List.of(newer.content(), oldIds.get(0)), JsonPath.read(first.body(), "$.data.items[*].id"));
        assertEquals(2, JsonPath.<Integer>read(first.body(), "$.data.pageSize")); assertEquals(3, JsonPath.<Integer>read(first.body(), "$.data.total"));
        assertEquals(List.of(oldIds.get(1)), JsonPath.read(list(query + "&page=2", token).body(), "$.data.items[*].id"));
        var huge = list(query + "&page=99999999999999999999999999999", token);
        assertEquals(200, huge.statusCode(), huge.body()); assertEquals(List.of(), JsonPath.read(huge.body(), "$.data.items"));
        assertEquals("99999999999999999999999999999", JsonPath.read(huge.body(), "$.data.page").toString()); assertEquals(3, JsonPath.<Integer>read(huge.body(), "$.data.total"));
    }
    @Test
    void validButUnknownAuthorReturnsAnEmptyPage() throws Exception {
        var response = list("?authorId=user_missing", token(account("MODERATOR")));
        assertEquals(200, response.statusCode(), response.body()); assertEquals(List.of(), JsonPath.read(response.body(), "$.data.items")); assertEquals(0, JsonPath.<Integer>read(response.body(), "$.data.total"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOURCE", "USER"})
    void exhaustedAdminContentQuotaReturns429(String scope) throws Exception {
        var moderator = account("MODERATOR");
        redis.opsForValue().set(scope.equals("SOURCE") ? sourceKey() : userKey(moderator.getId()), scope.equals("SOURCE") ? "120" : "60", Duration.ofMinutes(1));
        var response = list("?authorId=" + moderator.getId(), token(moderator));
        problem(response, 429, "RATE_LIMITED"); assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
        verifyNoInteractions(queries, reads);
    }
    @Test
    void repeatedEmptyQueriesCountOnlyAgainstTheIndependentAdminContentBudget() throws Exception {
        var moderator = account("MODERATOR"); String token = token(moderator);
        redis.opsForValue().set(PREFIX + "{content-admin-review-query}:source:" + TokenDigest.sha256("127.0.0.1"), "120", Duration.ofMinutes(1));
        assertEquals(200, list("?authorId=" + moderator.getId(), token).statusCode()); assertEquals(200, list("?authorId=" + moderator.getId(), token).statusCode());
        assertEquals("2", redis.opsForValue().get(sourceKey())); assertEquals("2", redis.opsForValue().get(userKey(moderator.getId())));
    }
    @ParameterizedTest
    @ValueSource(strings = {"AUTH_VERSION", "UNVERIFIED", "DISABLED", "NO_MODERATION_ROLE"})
    void currentPrivilegesAreRecheckedAfterTheAuthenticationFilter(String condition) throws Exception {
        var moderator = account("MODERATOR"); String token = token(moderator);
        doAnswer(call -> {
            var actor = (SessionPrincipal) call.callRealMethod();
            switch (condition) {
                case "AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", moderator.getId());
                case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=false WHERE id=?", moderator.getId());
                case "DISABLED" -> jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", moderator.getId());
                default -> jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code IN ('MODERATOR','ADMIN')", moderator.getId());
            }
            return actor;
        }).when(authentication).authenticate(token);
        problem(list("?authorId=" + moderator.getId(), token), condition.equals("AUTH_VERSION") ? 401 : 403,
                condition.equals("AUTH_VERSION") ? "AUTHENTICATION_REQUIRED" : condition.equals("DISABLED") ? "ACCOUNT_RESTRICTED" : "PERMISSION_DENIED");
        verifyNoInteractions(queries, reads);
    }
    @Test
    void writeBlockedModeratorWithoutUserRoleCanReadDisabledAuthorsContent() throws Exception {
        var moderator = account("MODERATOR"); var author = account(null); var seed = content(author, "DELETED", "TAKEN_DOWN");
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", moderator.getId());
        jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='USER'", moderator.getId());
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", author.getId());
        var response = list("?authorId=" + author.getId(), token(moderator));
        assertEquals(200, response.statusCode(), response.body()); assertEquals(List.of(seed.content()), JsonPath.read(response.body(), "$.data.items[*].id"));
    }
    @Test
    void redisFailureDoesNotStartTheContentQuery() throws Exception {
        var moderator = account("MODERATOR"); String token = token(moderator);
        doThrow(new RedisConnectionFailureException("sensitive Redis detail")).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(), any());
        var response = list("?authorId=" + moderator.getId(), token); problem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive")); verifyNoInteractions(queries, reads);
    }
    @ParameterizedTest @ValueSource(strings = {"COUNT", "PAGE", "BATCH"})
    void databaseFailuresDoNotBecomeAnEmptySuccess(String point) throws Exception {
        var moderator = account("MODERATOR"); content(moderator, "ACTIVE", "UNPUBLISHED");
        var failure = new TransientDataAccessResourceException("sensitive SQL detail");
        switch (point) {
            case "COUNT" -> doThrow(failure).when(queries).count(any(), any(), any());
            case "PAGE" -> doThrow(failure).when(queries).page(any(), any(), any(), anyLong(), anyInt());
            default -> doThrow(failure).when(reads).findRevisions(anyList(), anyList());
        }
        var response = list("?authorId=" + moderator.getId(), token(moderator)); problem(response, 503, "DEPENDENCY_UNAVAILABLE"); assertFalse(response.body().contains("sensitive"));
    }
    @ParameterizedTest @ValueSource(strings = {"MISSING_PUBLICATION", "WRONG_WORKING_STATE"})
    void inconsistentRevisionPointersFailClosed(String issue) throws Exception {
        var moderator = account("ADMIN"); var seed = content(moderator, "ACTIVE", "PUBLISHED");
        if (issue.equals("MISSING_PUBLICATION")) jdbc.update("UPDATE community_content SET published_revision_id=NULL WHERE id=?", seed.content());
        else jdbc.update("UPDATE community_content SET working_revision_id=? WHERE id=?", seed.revision(), seed.content());
        problem(list("?authorId=" + moderator.getId(), token(moderator)), 503, "DEPENDENCY_UNAVAILABLE");
    }
    @Test
    void emptyPageUsesOnlyTheCountQueryAndDoesNotLoadRevisions() throws Exception {
        var response = list("?authorId=" + id(), token(account("MODERATOR"))); assertEquals(200, response.statusCode(), response.body());
        verify(queries, times(1)).count(any(), any(), any()); verify(queries, never()).page(any(), any(), any(), anyLong(), anyInt()); verifyNoInteractions(reads);
    }
    @Test
    void fullWorkspacesAreLoadedInBatchesWithFrozenReferencesAndNoBusinessWrites() throws Exception {
        var moderator = account("MODERATOR"); var author = account(null); var seed = content(author, "ACTIVE", "PUBLISHED"); content(author, "DELETED", "UNPUBLISHED");
        String working = id(), image1 = media(author), image2 = media(author), tag = id();
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) "
                + "VALUES(?,?,2,'IN_REVIEW','待审标题','待审正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", working, seed.content());
        String review = ContentTestSupport.pending(jdbc, seed.content(), working);
        jdbc.update("UPDATE content_review SET purpose='UPDATE' WHERE id=?", review);
        jdbc.update("UPDATE community_content SET working_revision_id=?,active_review_id=? WHERE id=?", working, review, seed.content());
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'IMAGE',1),(?,?,'IMAGE',0)", working, image1, working, image2);
        jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) VALUES(?,?,'说明','DISABLED',1,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", tag, "tag_" + tag.substring(0,16), author.getId());
        jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,0)", working, tag);
        var before = jdbc.queryForList("SELECT * FROM community_content WHERE author_id=? ORDER BY id", author.getId());
        var frozen = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", working);
        var response = list("?authorId=" + author.getId(), token(moderator)); assertEquals(200, response.statusCode(), response.body());
        List<Map<String,Object>> items = JsonPath.read(response.body(), "$.data.items");
        var item = items.stream().filter(value -> seed.content().equals(value.get("id"))).findFirst().orElseThrow();
        assertEquals(review, item.get("activeReviewId"));
        assertEquals("待审正文", ((Map<?,?>) item.get("workingRevision")).get("bodyMarkdown"));
        assertEquals(List.of(image2,image1), ((Map<?,?>) item.get("workingRevision")).get("imageAssetIds"));
        assertEquals(List.of(tag), ((Map<?,?>) item.get("workingRevision")).get("tagIds"));
        assertEquals(seed.revision(), ((Map<?,?>) item.get("publishedRevision")).get("id"));
        verify(reads, times(1)).findRevisions(anyList(), anyList()); verify(reads, times(1)).findAssetsForRevisions(anyList()); verify(reads, times(1)).findTagsForRevisions(anyList());
        verify(reads, never()).findRevision(anyString(), anyString()); verify(reads, never()).findAssets(anyString()); verify(reads, never()).findTagIds(anyString());
        assertEquals(before, jdbc.queryForList("SELECT * FROM community_content WHERE author_id=? ORDER BY id", author.getId()));
        assertEquals(frozen, jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", working));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_lifecycle_outbox WHERE content_id=?", Integer.class, seed.content()));
    }
    @Test
    void countPageAndRevisionsShareARepeatableReadSnapshotDuringConcurrentChanges() throws Exception {
        var moderator = account("MODERATOR"); var author = account(null); String token = token(moderator);
        var one = content(author, "ACTIVE", "UNPUBLISHED"); var two = content(author, "ACTIVE", "UNPUBLISHED");
        var counted = new CountDownLatch(1); var proceed = new CountDownLatch(1); var pool = Executors.newSingleThreadExecutor();
        doAnswer(call -> {
            counted.countDown(); assertTrue(proceed.await(10, TimeUnit.SECONDS));
            var parameters = new java.util.HashMap<String,Object>(); parameters.put("author", call.getArgument(0)); parameters.put("lifecycle", call.getArgument(1));
            parameters.put("publication", call.getArgument(2)); parameters.put("offset", call.getArgument(3)); parameters.put("limit", call.getArgument(4));
            return sqlSession.selectList("com.freecontent.community.content.moderation.mapper.AdminContentQueryMapper.page", parameters);
        }).when(queries).page(eq(author.getId()), eq("ACTIVE"), isNull(), anyLong(), anyInt());
        try {
            var request = pool.submit(() -> list("?authorId=" + author.getId() + "&lifecycle=ACTIVE", token));
            assertTrue(counted.await(10, TimeUnit.SECONDS));
            jdbc.update("UPDATE community_content SET lifecycle='DELETED',deleted_at=UTC_TIMESTAMP(6) WHERE id=?", one.content());
            jdbc.update("UPDATE content_revision SET title='并发修改后的标题' WHERE id=?", one.revision());
            content(author, "ACTIVE", "UNPUBLISHED"); proceed.countDown();
            var response = request.get(30, TimeUnit.SECONDS); assertEquals(200, response.statusCode(), response.body());
            assertEquals(2, JsonPath.<Integer>read(response.body(), "$.data.total"));
            assertEquals(java.util.Set.of(one.content(),two.content()), new java.util.HashSet<>(JsonPath.<List<String>>read(response.body(), "$.data.items[*].id")));
            assertFalse(response.body().contains("并发修改后的标题"));
            assertEquals(List.of("ACTIVE","ACTIVE"), JsonPath.read(response.body(), "$.data.items[*].lifecycle"));
        } finally { proceed.countDown(); pool.shutdownNow(); }
    }
    @Test
    void openApiDeclaresOnlyTheFiveFiltersAndTheSharedWorkspacePage() throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).timeout(Duration.ofSeconds(45)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        Map<String,Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/admin/contents'].get");
        assertEquals("listAdminContents", operation.get("operationId")); assertEquals(List.of(Map.of("bearerAuth",List.of())), operation.get("security")); assertFalse(operation.containsKey("requestBody"));
        List<Map<String,Object>> parameters = JsonPath.read(response.body(), "$.paths['/api/v1/admin/contents'].get.parameters");
        assertEquals(java.util.Set.of("page","pageSize","authorId","lifecycle","publicationStatus"), parameters.stream().map(value -> value.get("name")).collect(java.util.stream.Collectors.toSet()));
        assertEquals("#/components/schemas/ContentWorkspace", JsonPath.read(response.body(), "$.components.schemas.WorkspacePage.properties.items.items['$ref']"));
    }
    private String media(UserAccount author) {
        String asset = id(); jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) "
                + "VALUES(?,?,'CONTENT_IMAGE','image.png','image/png',1,?,'FAILED',UTC_TIMESTAMP(6))", asset, author.getId(), "admin-query/" + asset); return asset;
    }
    private String sourceKey() { return PREFIX + "{content-admin-query}:source:" + TokenDigest.sha256("127.0.0.1"); }
    private String userKey(String actor) { return PREFIX + "{content-admin-query}:user:" + TokenDigest.sha256(actor); }

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
