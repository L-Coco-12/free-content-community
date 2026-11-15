package com.freecontent.community.media.job;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.media.config.MediaProcessingProperties;
import com.freecontent.community.media.mapper.MediaProcessingTaskMapper;
import com.freecontent.community.media.mapper.MediaProcessingOutputMapper;
import com.freecontent.community.media.processing.*;
import com.freecontent.community.media.entity.MediaProcessingTask;
import com.freecontent.community.media.service.transaction.MediaProcessingTransactionService;
import com.freecontent.community.media.storage.MediaProcessingStorage;
import com.freecontent.community.user.mapper.*;
import com.jayway.jsonpath.JsonPath;
import io.minio.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.TransientDataAccessResourceException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.net.http.*;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "app.media.processing.enabled=false", "app.media.upload-cleanup.enabled=false",
        "app.auth.email-challenge.delivery-enabled=false"})
class MediaProcessingFlowIT {
    private static final String REDIS_PREFIX="test:media-processing:"+UUID.randomUUID()+":";
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountMapper users;
    @Autowired UserRoleMapper roles;
    @Autowired RedisSessionStore sessions;
    @Autowired StringRedisTemplate redis;
    @Autowired StorageProperties storage;
    @Autowired MediaProcessingWorker worker;
    @Autowired MediaProcessingTransactionService transactions;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean MediaProcessingStorage processingStorage;
    @MockitoSpyBean MediaProcessingTaskMapper taskMapper;
    @MockitoSpyBean MediaProcessingOutputMapper outputMapper;
    @MockitoSpyBean MediaProcessingProperties processingProperties;
    private String owner,token;
    private final List<String> keys=new ArrayList<>();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void isolateQuotas(DynamicPropertyRegistry registry) {
        for(String group:List.of("upload-creation","upload-completion","task-query","asset-query"))
            registry.add("app.media."+group+".redis-key-prefix",()->REDIS_PREFIX);
    }

    @BeforeEach void setup() {
        var user=LoginTestSupport.account(); owner=UUID.randomUUID().toString().replace("-", "");
        user.setId(owner); user.setEmail("processing-"+owner+"@example.test"); user.setEmailNormalized(user.getEmail());
        users.insert(user); roles.insertDefaultRole(owner,user.getCreatedAt());
        token=sessions.create(owner,user.getAuthVersion(),"media-processing-test").accessToken();
    }

    @AfterEach void cleanup() throws Exception {
        keys.addAll(jdbc.queryForList("SELECT ready_object_key FROM media_asset WHERE owner_id=? AND ready_object_key IS NOT NULL",String.class,owner));
        keys.addAll(jdbc.queryForList("SELECT o.object_key FROM media_processing_output o JOIN media_processing_task t ON t.id=o.task_id WHERE t.owner_id=?",String.class,owner));
        try (var minio=minio()) {
            for(String key:new HashSet<>(keys)) minio.removeObject(RemoveObjectArgs.builder().bucket(storage.bucket()).object(key).build());
        }
        for(String table:List.of("media_upload_completion_request","media_processing_task","media_upload_request","media_upload_session","media_asset"))
            jdbc.update("DELETE FROM "+table+" WHERE owner_id=?",owner);
        jdbc.update("DELETE FROM app_user_role WHERE user_id=?",owner); users.deleteById(owner);
        var quotaKeys=redis.keys(REDIS_PREFIX+"*");
        if(quotaKeys!=null && !quotaKeys.isEmpty()) redis.delete(quotaKeys);
    }

    @Test void orphanWrittenBeforeProcessCrashIsReclaimedWithoutTouchingSourceOrReadyObjects() throws Exception {
        var bytes=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);
        var upload=upload(bytes.toByteArray(),"image/png","CONTENT_IMAGE");
        var claim=transactions.claim(upload.task());
        processingStorage.write(claim.outputKey(),bytes.toByteArray(),"image/png");
        jdbc.update("UPDATE media_processing_output SET available_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",claim.token());
        // 活跃执行者的结果不能清理，即使计划时间已经到达。
        worker.cleanupOutput(claim.token());
        assertTrue(exists(claim.outputKey()));
        jdbc.update("UPDATE media_processing_task SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 5 MINUTE WHERE id=?",upload.task());
        worker.cleanupOutput(claim.token());
        assertFalse(exists(claim.outputKey())); assertTrue(exists(upload.source()));
        worker.processTask(upload.task()); assertEquals("READY",assetState(upload));
        String ready=jdbc.queryForObject("SELECT ready_object_key FROM media_asset WHERE id=?",String.class,upload.asset());
        String bound=jdbc.queryForObject("SELECT id FROM media_processing_output WHERE task_id=? AND state='BOUND'",String.class,upload.task());
        jdbc.update("UPDATE media_processing_output SET available_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",bound);
        worker.cleanupOutput(bound); assertTrue(exists(ready));
    }

    private boolean exists(String key) throws Exception {
        try(var minio=minio()) {
            try { minio.statObject(StatObjectArgs.builder().bucket(storage.bucket()).object(key).build()); return true; }
            catch(io.minio.errors.ErrorResponseException ex) {
                if(Set.of("NoSuchKey","NoSuchObject").contains(ex.errorResponse().code())) return false;
                throw ex;
            }
        }
    }

    @Test void cleanedJournalCanReclaimAnObjectWrittenLateByAResumedOldProcess() throws Exception {
        byte[] bytes=image("png"); var upload=upload(bytes,"image/png","AVATAR");
        var claim=transactions.claim(upload.task());
        jdbc.update("UPDATE media_processing_task SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",upload.task());
        jdbc.update("UPDATE media_processing_output SET available_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",claim.token());
        worker.cleanupOutput(claim.token());
        // 模拟一个在 PUT 前被长时间暂停、随后恢复的旧进程；结果登记不可丢失。
        processingStorage.write(claim.outputKey(),bytes,"image/png");
        jdbc.update("UPDATE media_processing_output SET available_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",claim.token());
        worker.cleanupOutput(claim.token());
        assertFalse(exists(claim.outputKey()));
    }

    @Test void acceptedAvatarBecomesReadyAndStoresTheExactValidatedBytesSeparately() throws Exception {
        var bytes=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB),"png",bytes);
        byte[] png=bytes.toByteArray();
        var upload=upload(png,"image/png","AVATAR");
        assertEquals("PROCESSING",assetState(upload));
        worker.processTask(upload.task());
        assertEquals("SUCCEEDED",taskState(upload));
        assertEquals("READY",assetState(upload));
        assertEquals(100,jdbc.queryForObject("SELECT progress_percent FROM media_processing_task WHERE id=?",Integer.class,upload.task()));
        var asset=jdbc.queryForMap("SELECT * FROM media_asset WHERE id=?",upload.asset());
        String ready=(String)asset.get("ready_object_key");
        assertTrue(ready.matches("private/"+owner+"/"+upload.asset()+"/ready/[A-Za-z0-9_-]{1,64}"));
        assertNotEquals(upload.source(),ready); assertEquals(storage.identity(),asset.get("ready_storage_id"));
        assertEquals("image/png",asset.get("actual_content_type"));
        assertEquals(3,((Number)asset.get("width")).intValue()); assertEquals(2,((Number)asset.get("height")).intValue());
        try(var minio=minio(); var in=minio.getObject(GetObjectArgs.builder().bucket(storage.bucket()).object(ready).build())) {
            assertArrayEquals(png,in.readAllBytes());
        }
        var task=send("GET","/api/v1/tasks/"+upload.task(),null);
        assertEquals(200,task.statusCode(),task.body()); assertEquals("SUCCEEDED",JsonPath.read(task.body(),"$.data.state"));
        var view=send("GET","/api/v1/media/assets/"+upload.asset(),null);
        assertEquals(200,view.statusCode(),view.body()); assertEquals("READY",JsonPath.read(view.body(),"$.data.state"));
        assertFalse(task.body().contains("leaseToken")); assertFalse(view.body().contains("readyObjectKey"));
    }

    @ParameterizedTest @ValueSource(strings={"image/jpeg","image/gif","lossy.webp","lossless-alpha.webp","animated.webp"})
    void supportedImagesAndAnimationsKeepTheirOriginalBytes(String format) throws Exception {
        byte[] bytes; String mime=format;
        if (format.endsWith(".webp")) {
            try(var in=getClass().getResourceAsStream("/media/"+format)) { bytes=in.readAllBytes(); }
            mime="image/webp";
        } else bytes=image(format.substring(6));
        var upload=upload(bytes,mime,"CONTENT_IMAGE"); worker.processTask(upload.task());
        assertEquals("SUCCEEDED",taskState(upload)); assertEquals("READY",assetState(upload));
        String ready=jdbc.queryForObject("SELECT ready_object_key FROM media_asset WHERE id=?",String.class,upload.asset());
        try(var minio=minio();var in=minio.getObject(GetObjectArgs.builder().bucket(storage.bucket()).object(ready).build())) {
            assertArrayEquals(bytes,in.readAllBytes());
        }
    }

    @Test void reportAttachmentImageAlsoUsesTheProcessingPipeline() throws Exception {
        var upload=upload(image("png"),"image/png","REPORT_ATTACHMENT"); worker.processTask(upload.task());
        assertEquals("READY",assetState(upload));
    }

    @Test void unrecognizableFileFailsBothTaskAndAssetAndExposesOnlyFixedReason() throws Exception {
        var upload=upload("not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8),"image/png","AVATAR");
        worker.processTask(upload.task());
        assertFailure(upload,"INVALID_IMAGE");
        var response=send("GET","/api/v1/tasks/"+upload.task(),null);
        assertEquals(200,response.statusCode()); assertEquals("INVALID_IMAGE",JsonPath.read(response.body(),"$.data.errorCode"));
        assertFalse(response.body().contains(upload.source())); assertFalse(response.body().contains(storage.endpoint()));
    }

    @Test void declarationMismatchFailsAfterReadingTheRealFile() throws Exception {
        var upload=upload(image("jpeg"),"image/png","AVATAR"); worker.processTask(upload.task());
        assertFailure(upload,"MEDIA_TYPE_MISMATCH");
    }

    @Test void sourceOverwriteAfterCompletionCannotBePromotedToReady() throws Exception {
        byte[] bytes=image("png"); var upload=upload(bytes,"image/png","CONTENT_IMAGE");
        bytes[bytes.length/2]^=1; put(upload.source(),bytes,"image/png");
        worker.processTask(upload.task()); assertFailure(upload,"SOURCE_OBJECT_CHANGED");
    }

    @Test void transientStorageFailuresBackOffAndEventuallyFinishInsteadOfStayingPendingForever() throws Exception {
        var upload=upload(image("png"),"image/png","AVATAR");
        doThrow(new MediaProcessingException("STORAGE_UNAVAILABLE","媒体存储暂不可用",true)).when(processingStorage).read(any());
        worker.processTask(upload.task()); assertEquals("PENDING",taskState(upload)); assertEquals("PROCESSING",assetState(upload));
        worker.processTask(upload.task()); assertEquals(1,attempts(upload));
        for(int i=1;i<3;i++) {
            jdbc.update("UPDATE media_processing_task SET available_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",upload.task());
            worker.processTask(upload.task());
        }
        assertFailure(upload,"STORAGE_UNAVAILABLE"); assertEquals(3,attempts(upload));
        assertTrue(jdbc.queryForObject("SELECT retryable FROM media_processing_task WHERE id=?",Boolean.class,upload.task()));
    }

    @Test void successfulRetryClearsErrorsAndBindsOnlyTheNewAttemptsObject() throws Exception {
        var upload=upload(image("png"),"image/png","AVATAR");
        doThrow(new MediaProcessingException("STORAGE_UNAVAILABLE","媒体存储暂不可用",true)).doCallRealMethod().when(processingStorage).read(any());
        worker.processTask(upload.task());
        jdbc.update("UPDATE media_processing_task SET available_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",upload.task());
        worker.processTask(upload.task()); assertEquals("SUCCEEDED",taskState(upload)); assertEquals(2,attempts(upload));
        assertNull(jdbc.queryForObject("SELECT error_code FROM media_processing_task WHERE id=?",String.class,upload.task()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM media_processing_output WHERE task_id=? AND state='BOUND'",Integer.class,upload.task()));
    }

    @Test void expiredTokenCanNeitherSucceedNorOverwriteAReclaimedTasksState() throws Exception {
        byte[] bytes=image("png"); var upload=upload(bytes,"image/png","AVATAR");
        var old=transactions.claim(upload.task());
        jdbc.update("UPDATE media_processing_task SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",upload.task());
        var current=transactions.claim(upload.task()); assertNotEquals(old.token(),current.token());
        var object=processingStorage.write(old.outputKey(),bytes,"image/png");
        assertFalse(transactions.succeed(old,new ImageValidator().validate(bytes,"image/png",null),bytes.length,object));
        transactions.fail(old,"INVALID_IMAGE","旧执行者",false);
        assertEquals("RUNNING",taskState(upload)); assertEquals(current.token(),jdbc.queryForObject("SELECT lease_token FROM media_processing_task WHERE id=?",String.class,upload.task()));
        var result=processingStorage.write(current.outputKey(),bytes,"image/png");
        assertTrue(transactions.succeed(current,new ImageValidator().validate(bytes,"image/png",null),bytes.length,result));
        assertEquals("READY",assetState(upload));
    }

    @Test void concurrentWorkersProduceOnlyOneCommittedAttemptAndRepeatedExecutionIsHarmless() throws Exception {
        var upload=upload(image("png"),"image/png","AVATAR");
        var pool=Executors.newFixedThreadPool(4);
        try {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<4;i++) futures.add(pool.submit(()->worker.processTask(upload.task())));
            for(var future:futures) future.get(20,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        worker.processTask(upload.task());
        assertEquals("SUCCEEDED",taskState(upload)); assertEquals(1,attempts(upload));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM media_processing_output WHERE task_id=?",Integer.class,upload.task()));
    }

    @Test void databaseCommitFailureRollsBackReadyAndRemainsRecoverable() throws Exception {
        var upload=upload(image("png"),"image/png","AVATAR");
        doAnswer(invocation->{
            MediaProcessingTask task=invocation.getArgument(0);
            if("SUCCEEDED".equals(task.getState())) throw new TransientDataAccessResourceException("injected completion failure");
            return sqlSession.update(MediaProcessingTaskMapper.class.getName()+".saveState",task);
        }).when(taskMapper).saveState(any());
        assertThrows(TransientDataAccessResourceException.class,()->worker.processTask(upload.task()));
        assertEquals("PROCESSING",assetState(upload)); assertEquals("RUNNING",taskState(upload));
        assertEquals("RESERVED",jdbc.queryForObject("SELECT state FROM media_processing_output WHERE task_id=?",String.class,upload.task()));
        doAnswer(invocation->sqlSession.update(MediaProcessingTaskMapper.class.getName()+".saveState",invocation.getArgument(0)))
                .when(taskMapper).saveState(any());
        jdbc.update("UPDATE media_processing_task SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",upload.task());
        worker.processTask(upload.task()); assertEquals("READY",assetState(upload)); assertEquals(2,attempts(upload));
    }

    @Test void scheduledDispatchProcessesBacklogWithoutUsingTheQueryEndpointsToDriveIt() throws Exception {
        var upload=upload(image("png"),"image/png","AVATAR");
        // 仅选择本测试的任务，避免调度消费共享开发库中其他用户的任务。
        doReturn(List.of(upload.task())).when(taskMapper).findDue(any());
        doReturn(List.of()).when(outputMapper).findDue(any());
        doReturn(true).when(processingProperties).enabled();
        worker.poll();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(()->"READY".equals(assetState(upload)));
        assertEquals("SUCCEEDED",taskState(upload));
    }

    @Test void storageIoRunsOutsideDatabaseTransactions() throws Exception {
        var upload=upload(image("png"),"image/png","AVATAR");
        doAnswer(invocation->{ assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()); return invocation.callRealMethod(); })
                .when(processingStorage).read(any());
        doAnswer(invocation->{ assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()); return invocation.callRealMethod(); })
                .when(processingStorage).write(anyString(),any(),anyString());
        worker.processTask(upload.task()); assertEquals("READY",assetState(upload));
    }

    @Test void largeReadyImageUsesOnePutSoNetworkBudgetDoesNotExpandIntoMultipartCalls() throws Exception {
        var storageFailure=new java.util.concurrent.atomic.AtomicReference<String>("");
        doAnswer(invocation->{
            try { return invocation.callRealMethod(); }
            catch(MediaProcessingException ex) { storageFailure.set("read: "+ex.code()+" "+ex.getMessage()); throw ex; }
        }).when(processingStorage).read(any());
        doAnswer(invocation->{
            try { return invocation.callRealMethod(); }
            catch(MediaProcessingException ex) { storageFailure.set("write: "+ex.code()+" "+ex.getMessage()); throw ex; }
        }).when(processingStorage).write(anyString(),any(),anyString());
        var picture=new BufferedImage(1500,1500,BufferedImage.TYPE_INT_RGB);
        var pixels=((java.awt.image.DataBufferInt)picture.getRaster().getDataBuffer()).getData();
        var random=new Random(42917);
        for(int i=0;i<pixels.length;i++) pixels[i]=random.nextInt(0x1000000);
        var buffer=new ByteArrayOutputStream(); ImageIO.write(picture,"png",buffer); picture.flush();
        byte[] bytes=buffer.toByteArray(); assertTrue(bytes.length>5*1024*1024); assertTrue(bytes.length<10*1024*1024);
        var upload=upload(bytes,"image/png","CONTENT_IMAGE"); worker.processTask(upload.task());
        assertEquals("READY",assetState(upload),storageFailure::get);
        String ready=jdbc.queryForObject("SELECT ready_object_key FROM media_asset WHERE id=?",String.class,upload.asset());
        try(var minio=minio()) {
            var object=minio.statObject(StatObjectArgs.builder().bucket(storage.bucket()).object(ready).build());
            // 真实 MinIO 的分片结果带 -N 后缀，此处只作为存储集成证据，业务不解析 ETag。
            assertFalse(object.etag().matches(".*-[0-9]+$"),"READY 写入不应拆成多次网络调用");
            try(var in=minio.getObject(GetObjectArgs.builder().bucket(storage.bucket()).object(ready).build())) { assertArrayEquals(bytes,in.readAllBytes()); }
        }
    }

    private void assertFailure(Upload upload,String code) {
        assertEquals("FAILED",taskState(upload)); assertEquals("FAILED",assetState(upload));
        assertEquals(code,jdbc.queryForObject("SELECT error_code FROM media_processing_task WHERE id=?",String.class,upload.task()));
        assertNull(jdbc.queryForObject("SELECT ready_object_key FROM media_asset WHERE id=?",String.class,upload.asset()));
    }
    private int attempts(Upload upload) { return jdbc.queryForObject("SELECT attempt_count FROM media_processing_task WHERE id=?",Integer.class,upload.task()); }
    private byte[] image(String format) throws Exception {
        var bytes=new ByteArrayOutputStream(); assertTrue(ImageIO.write(new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB),format,bytes)); return bytes.toByteArray();
    }

    private Upload upload(byte[] bytes,String mime,String scene) throws Exception {
        var created=send("POST","/api/v1/media/uploads","{\"scene\":\""+scene+"\",\"fileName\":\"test\",\"contentType\":\""+mime+"\",\"sizeBytes\":"+bytes.length+"}");
        assertEquals(201,created.statusCode(),created.body());
        String id=JsonPath.read(created.body(),"$.data.id"),asset=JsonPath.read(created.body(),"$.data.assetId");
        String source=jdbc.queryForObject("SELECT storage_key FROM media_upload_session WHERE id=?",String.class,id); keys.add(source);
        put(source,bytes,mime);
        var completed=send("POST","/api/v1/media/uploads/"+id+"/completion","{\"expectedVersion\":1}");
        assertEquals(202,completed.statusCode(),completed.body());
        return new Upload(id,asset,JsonPath.read(completed.body(),"$.data.taskId"),source);
    }
    private void put(String key,byte[] bytes,String mime) throws Exception {
        try(var minio=minio()) { minio.putObject(PutObjectArgs.builder().bucket(storage.bucket()).object(key).contentType(mime)
                .stream(new ByteArrayInputStream(bytes),(long)bytes.length,-1L).build()); }
    }
    private MinioClient minio() { return MinioClient.builder().endpoint(storage.endpoint()).region(storage.region())
            .credentials(storage.accessKey(),storage.secretKey()).build(); }
    private String assetState(Upload u) { return jdbc.queryForObject("SELECT state FROM media_asset WHERE id=?",String.class,u.asset()); }
    private String taskState(Upload u) { return jdbc.queryForObject("SELECT state FROM media_processing_task WHERE id=?",String.class,u.task()); }
    private HttpResponse<String> send(String method,String path,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20))
                .header("Authorization","Bearer "+token);
        if(body!=null) request.header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString());
        return http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private record Upload(String id,String asset,String task,String source) { }
}
