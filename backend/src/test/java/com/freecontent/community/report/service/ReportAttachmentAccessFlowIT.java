package com.freecontent.community.report.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.media.storage.MediaObjectReader;
import com.freecontent.community.media.storage.MediaReadSigner;
import com.freecontent.community.report.mapper.ReportAttachmentAccessMapper;
import com.freecontent.community.report.mapper.ReportAttachmentAccessMapper.AccessAudit;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.model.UserRole;
import com.jayway.jsonpath.JsonPath;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.processing.enabled=false",
        "app.media.upload-cleanup.enabled=false"})
class ReportAttachmentAccessFlowIT {
    private static final String PATH = "/api/v1/admin/reports";
    private static final String PREFIX = "test:report-attachment-access:" + UUID.randomUUID() + ":";
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6AxoAAAAASUVORK5CYII=");
    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserAccountMapper users;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private StorageProperties storage;
    @Autowired private SqlSessionTemplate sqlSession;
    @MockitoSpyBean private ReportAttachmentAccessMapper attachments;
    @MockitoSpyBean private MediaObjectReader objects;
    @MockitoSpyBean private MediaReadSigner signer;
    private final List<String> accountIds = new ArrayList<>();
    private final List<String> objectKeys = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.report.attachment-access.redis-key-prefix", () -> PREFIX);
        registry.add("app.report.attachment-access.read-url-ttl-seconds", () -> 300);
        registry.add("app.report.attachment-access.user-per-minute-limit", () -> 3);
        registry.add("app.report.attachment-access.ip-per-minute-limit", () -> 5);
        registry.add("app.report.admin-query.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (!objectKeys.isEmpty()) {
            try (var minio = minio()) {
                for (String key : objectKeys) minio.removeObject(RemoveObjectArgs.builder().bucket(storage.bucket()).object(key).build());
            }
        }
        var keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String id : accountIds) jdbc.update("DELETE FROM report_attachment_access_audit WHERE actor_id=?", id);
        for (String id : accountIds) {
            jdbc.update("DELETE a FROM report_attachment a JOIN user_report r ON r.id=a.report_id WHERE r.reporter_id=?", id);
            jdbc.update("DELETE FROM user_report WHERE reporter_id=?", id);
        }
        for (String id : accountIds) {
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?", id);
            ContentTestSupport.clearWorkspace(jdbc, id);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", id);
            users.deleteById(id);
        }
    }

    @Test
    void issuesAWorkingPrivateMinioReadUrlAndPersistsAuditBeforeReturningIt() throws Exception {
        var moderator = account(UserRole.MODERATOR);
        var reporter = account(UserRole.USER);
        var seed = linkedAttachment(reporter);
        makeReady(reporter, seed.assetId());

        var response = access(seed.reportId(), seed.assetId(), token(moderator));

        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var json = JsonPath.parse(response.body());
        Map<?, ?> data = json.read("$.data", Map.class);
        assertEquals(Set.of("assetId", "url", "expiresAt", "contentType"), data.keySet());
        assertEquals(seed.assetId(), data.get("assetId"));
        assertEquals("image/png", data.get("contentType"));
        var audit = jdbc.queryForMap("SELECT * FROM report_attachment_access_audit WHERE actor_id=?", moderator.getId());
        assertEquals(seed.reportId(), audit.get("report_id"));
        assertEquals(seed.assetId(), audit.get("asset_id"));
        assertEquals("ADMIN_REPORT_ATTACHMENT_ACCESS", audit.get("action"));
        assertEquals(json.read("$.requestId", String.class), audit.get("request_id"));
        Instant expires = Instant.parse((String) data.get("expiresAt"));
        assertEquals(expires, ((LocalDateTime) audit.get("expires_at")).toInstant(ZoneOffset.UTC));
        assertEquals(300, Duration.between(((LocalDateTime) audit.get("occurred_at")).toInstant(ZoneOffset.UTC), expires).toSeconds());
        assertFalse(audit.values().stream()
                .anyMatch(value -> value instanceof String text && (text.contains("X-Amz-") || text.startsWith("private/"))));

        String url = (String) data.get("url");
        var image = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, image.statusCode());
        assertEquals("image/png", image.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(image.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertArrayEquals(PNG, image.body());
        var unsigned = http.send(HttpRequest.newBuilder(URI.create(url.substring(0, url.indexOf('?'))))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertTrue(unsigned.statusCode() == 403 || unsigned.statusCode() == 404);
    }

    @Test
    void unrelatedReportAndAssetPairsReturn404BeforeCheckingReadiness() throws Exception {
        var actor = account(UserRole.ADMIN);
        var reporter = account(UserRole.USER);
        var first = linkedAttachment(reporter);
        var second = linkedAttachment(reporter);
        String token = token(actor);

        problem(access(first.reportId(), second.assetId(), token), 404, "RESOURCE_NOT_FOUND");
        problem(access(id(), first.assetId(), token), 404, "RESOURCE_NOT_FOUND");
        problem(access(first.reportId(), id(), token), 404, "RESOURCE_NOT_FOUND");
        verifyNoInteractions(objects, signer);
        assertEquals(0, auditCount(actor));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WRONG_OWNER", "WRONG_SCENE"})
    void onlyTheReportersOwnReportAttachmentCanBeSigned(String invalid) throws Exception {
        var actor = account(UserRole.ADMIN);
        var seed = linkedAttachment(account(UserRole.USER));
        if ("WRONG_OWNER".equals(invalid)) {
            jdbc.update("UPDATE media_asset SET owner_id=? WHERE id=?", actor.getId(), seed.assetId());
        } else {
            jdbc.update("UPDATE media_asset SET scene='AVATAR' WHERE id=?", seed.assetId());
        }

        problem(access(seed.reportId(), seed.assetId(), token(actor)), 404, "RESOURCE_NOT_FOUND");
        verifyNoInteractions(objects, signer);
        assertEquals(0, auditCount(actor));
    }

    @Test
    void aLinkedAttachmentStillBeingProcessedReturns409WithoutASignature() throws Exception {
        var actor = account(UserRole.MODERATOR);
        var seed = linkedAttachment(account(UserRole.USER));

        problem(access(seed.reportId(), seed.assetId(), token(actor)), 409, "MEDIA_NOT_READY");
        verifyNoInteractions(objects, signer);
        assertEquals(0, auditCount(actor));
    }

    @Test
    void onlyAuthorizedOperatorsCanRequestAttachmentAccess() throws Exception {
        var reporter = account(UserRole.USER);
        var seed = linkedAttachment(reporter);
        var moderator = account(UserRole.MODERATOR);
        String token = token(moderator);
        problem(access(seed.reportId(), seed.assetId(), null), 401, "AUTHENTICATION_REQUIRED");
        problem(access(seed.reportId(), seed.assetId(), token(reporter)), 403, "PERMISSION_DENIED");
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", moderator.getId());
        problem(access(seed.reportId(), seed.assetId(), token), 403, "ACCOUNT_RESTRICTED");
        verifyNoInteractions(attachments, objects, signer);
        assertEquals(0, auditCount(moderator));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE", "LINK", "OBJECT"})
    void changesDuringHeadCannotProduceAStaleGrant(String change) throws Exception {
        var actor = account(UserRole.MODERATOR);
        var reporter = account(UserRole.USER);
        var seed = linkedAttachment(reporter);
        makeReady(reporter, seed.assetId());
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            call.callRealMethod();
            switch (change) {
                case "ROLE" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='MODERATOR'", actor.getId());
                case "LINK" -> jdbc.update("DELETE FROM report_attachment WHERE report_id=? AND asset_id=?", seed.reportId(), seed.assetId());
                default -> jdbc.update("UPDATE media_asset SET ready_object_etag='changed' WHERE id=?", seed.assetId());
            }
            return null;
        }).when(objects).verify(any());

        var response = access(seed.reportId(), seed.assetId(), token(actor));
        switch (change) {
            case "ROLE" -> problem(response, 403, "PERMISSION_DENIED");
            case "LINK" -> problem(response, 404, "RESOURCE_NOT_FOUND");
            default -> problem(response, 503, "DEPENDENCY_UNAVAILABLE");
        }
        verifyNoInteractions(signer);
        assertEquals(0, auditCount(actor));
    }

    @Test
    void aMissingOrMismatchedMinioResultIsNeverSigned() throws Exception {
        var actor = account(UserRole.ADMIN);
        var reporter = account(UserRole.USER);
        var seed = linkedAttachment(reporter);
        String key = makeReady(reporter, seed.assetId());
        String token = token(actor);
        jdbc.update("UPDATE media_asset SET ready_object_etag='different' WHERE id=?", seed.assetId());
        problem(access(seed.reportId(), seed.assetId(), token), 503, "DEPENDENCY_UNAVAILABLE");
        try (var minio = minio()) {
            minio.removeObject(RemoveObjectArgs.builder().bucket(storage.bucket()).object(key).build());
        }

        problem(access(seed.reportId(), seed.assetId(), token), 503, "DEPENDENCY_UNAVAILABLE");
        verifyNoInteractions(signer);
        assertEquals(0, auditCount(actor));
    }

    @Test
    void auditFailureRollsBackTheRecordAndDoesNotReleaseASignedUrl() throws Exception {
        var actor = account(UserRole.ADMIN);
        var reporter = account(UserRole.USER);
        var seed = linkedAttachment(reporter);
        makeReady(reporter, seed.assetId());
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            var row = call.getArgument(0, AccessAudit.class);
            assertEquals(1, sqlSession.insert(ReportAttachmentAccessMapper.class.getName() + ".insertAudit", row));
            throw new DataAccessResourceFailureException("Test report attachment audit write failure");
        }).when(attachments).insertAudit(any());

        var response = access(seed.reportId(), seed.assetId(), token(actor));
        problem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("X-Amz-"));
        assertEquals(0, auditCount(actor));
    }

    @Test
    void validatesBothPathIdsAndRejectsQueryParametersAndBodies() throws Exception {
        var actor = account(UserRole.ADMIN);
        var seed = linkedAttachment(account(UserRole.USER));
        String token = token(actor);
        problem(access("bad.id", seed.assetId(), token), 422, "VALIDATION_ERROR");
        problem(access(seed.reportId(), "a".repeat(65), token), 422, "VALIDATION_ERROR");
        String path = path(seed.reportId(), seed.assetId());
        problem(send(path + "?scope=all", token, HttpRequest.BodyPublishers.noBody()), 400, "MALFORMED_REQUEST");
        problem(send(path, token, HttpRequest.BodyPublishers.ofString("{}")), 400, "MALFORMED_REQUEST");
        verifyNoInteractions(attachments, objects, signer);
        assertEquals(0, auditCount(actor));
    }

    @Test
    void usesIndependentAccountAndSourceLimitsAndAuditsEveryGrant() throws Exception {
        var first = account(UserRole.MODERATOR);
        var second = account(UserRole.ADMIN);
        var reporter = account(UserRole.USER);
        var seed = linkedAttachment(reporter);
        makeReady(reporter, seed.assetId());
        String firstToken = token(first);
        String secondToken = token(second);
        for (int i = 0; i < 3; i++) {
            assertEquals(200, access(seed.reportId(), seed.assetId(), firstToken).statusCode());
        }
        rateLimited(access(seed.reportId(), seed.assetId(), firstToken));
        for (int i = 0; i < 2; i++) {
            assertEquals(200, access(seed.reportId(), seed.assetId(), secondToken).statusCode());
        }
        rateLimited(access(seed.reportId(), seed.assetId(), secondToken));
        assertEquals(200, send(PATH, firstToken, HttpRequest.BodyPublishers.noBody()).statusCode());
        assertEquals(3, auditCount(first));
        assertEquals(2, auditCount(second));
    }

    @Test
    void documentsTheTwoPathParametersPermissionAndMediaAccessSchema() throws Exception {
        var response = send("/v3/api-docs", null, HttpRequest.BodyPublishers.noBody());
        assertEquals(200, response.statusCode());
        var doc = JsonPath.parse(response.body());
        String operation = "$.paths['" + path("{reportId}", "{assetId}") + "'].get";
        assertEquals("getReportAttachmentAccess", doc.read(operation + ".operationId", String.class));
        assertEquals(List.of("report:read"), doc.read(operation + "['x-permissions']", List.class));
        assertEquals(Set.of("reportId", "assetId"), Set.copyOf(doc.read(operation + ".parameters[*].name", List.class)));
        assertEquals(List.of("path", "path"), doc.read(operation + ".parameters[*].in", List.class));
        assertFalse(doc.read(operation, Map.class).containsKey("requestBody"));
        String responseRef = doc.read(operation + ".responses['200'].content['application/json'].schema['$ref']", String.class);
        String responseSchema = responseRef.substring(responseRef.lastIndexOf('/') + 1);
        assertEquals("#/components/schemas/MediaAccess",
                doc.read("$.components.schemas['" + responseSchema + "'].properties.data['$ref']", String.class));
    }

    private record Seed(String reportId, String assetId) { }

    private Seed linkedAttachment(UserAccount reporter) {
        String content = id();
        jdbc.update("""
                INSERT INTO community_content(id, author_id, kind, lifecycle, publication_status, version, created_at, updated_at)
                VALUES (?, ?, 'ARTICLE', 'ACTIVE', 'TAKEN_DOWN', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, content, reporter.getId());
        String report = id();
        jdbc.update("""
                INSERT INTO user_report(id, reporter_id, target_type, content_id, reason_code, description,
                    state, version, created_at, resolved_at)
                VALUES (?, ?, 'CONTENT', ?, 'SPAM', '附件测试举报', 'RESOLVED', 2, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, report, reporter.getId(), content);
        String asset = id();
        jdbc.update("""
                INSERT INTO media_asset(id, owner_id, scene, file_name, declared_content_type, declared_size_bytes,
                    storage_key, state, created_at)
                VALUES (?, ?, 'REPORT_ATTACHMENT', 'evidence.png', 'image/png', ?, ?, 'PROCESSING', UTC_TIMESTAMP(6))
                """, asset, reporter.getId(), PNG.length, "private/" + reporter.getId() + "/" + asset + "/source");
        jdbc.update("INSERT INTO report_attachment(report_id, asset_id, position) VALUES (?, ?, 0)", report, asset);
        return new Seed(report, asset);
    }

    private String makeReady(UserAccount owner, String assetId) throws Exception {
        String key = "private/" + owner.getId() + "/" + assetId + "/ready/" + id();
        objectKeys.add(key);
        String etag;
        try (var minio = minio()) {
            etag = minio.putObject(PutObjectArgs.builder().bucket(storage.bucket()).object(key).contentType("image/png")
                    .stream(new ByteArrayInputStream(PNG), (long) PNG.length, -1L).build()).etag();
        }
        jdbc.update("""
                UPDATE media_asset SET state='READY', actual_content_type='image/png', actual_size_bytes=?,
                    ready_object_key=?, ready_object_etag=?, ready_storage_id=? WHERE id=?
                """, PNG.length, key, etag, storage.identity(), assetId);
        return key;
    }

    private MinioClient minio() {
        return MinioClient.builder().endpoint(storage.endpoint()).region(storage.region())
                .credentials(storage.accessKey(), storage.secretKey()).build();
    }

    private UserAccount account(UserRole role) {
        var account = LoginTestSupport.account();
        account.setId(id());
        account.setEmail("report-attachment-" + account.getId() + "@example.test");
        account.setEmailNormalized(account.getEmail());
        accountIds.add(account.getId());
        users.insert(account);
        jdbc.update("INSERT INTO app_user_role(user_id, role_code, created_at) VALUES (?, ?, UTC_TIMESTAMP(6))",
                account.getId(), role.name());
        return account;
    }

    private HttpResponse<String> access(String reportId, String assetId, String token) throws Exception {
        return send(path(reportId, assetId), token, HttpRequest.BodyPublishers.noBody());
    }

    private static String path(String reportId, String assetId) {
        return PATH + "/" + reportId + "/attachments/" + assetId + "/access";
    }

    private HttpResponse<String> send(String path, String token, HttpRequest.BodyPublisher body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).method("GET", body);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int auditCount(UserAccount actor) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM report_attachment_access_audit WHERE actor_id=?",
                Integer.class, actor.getId());
    }

    private static void rateLimited(HttpResponse<String> response) {
        problem(response, 429, "RATE_LIMITED");
        assertTrue(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow()) >= 1);
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode());
        assertEquals(code, JsonPath.parse(response.body()).read("$.code", String.class));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    private String token(UserAccount user) {
        return sessions.create(user.getId(), user.getAuthVersion(), "report-attachment-access-it").accessToken();
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
