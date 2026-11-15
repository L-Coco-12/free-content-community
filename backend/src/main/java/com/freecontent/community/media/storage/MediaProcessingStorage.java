package com.freecontent.community.media.storage;

import com.freecontent.community.media.config.*;
import com.freecontent.community.media.entity.MediaProcessingTask;
import com.freecontent.community.media.processing.MediaProcessingException;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PreDestroy;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;
import java.io.*;
import java.time.*;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 所有网络 I/O 在业务事务外执行；条件 GET 固定源快照，PUT 使用已校验的同一份字节。 */
@Component
public class MediaProcessingStorage implements AutoCloseable {
    private final MinioClient client;
    private final StorageProperties storage;
    private final UploadPolicyProperties policy;
    public MediaProcessingStorage(StorageProperties storage,UploadPolicyProperties policy) {
        this.storage=storage; this.policy=policy;
        var http=new OkHttpClient.Builder().connectTimeout(3,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS)
                .writeTimeout(5,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build();
        client=MinioClient.builder().endpoint(storage.endpoint()).region(storage.region())
                .credentials(storage.accessKey(),storage.secretKey()).httpClient(http,true).build();
    }
    public byte[] read(MediaProcessingTask task) {
        if (!storage.identity().equals(task.getSignerId())) throw fail("STORAGE_CONFIG_CHANGED","上传存储配置已变化",false);
        String prefix="private/"+task.getOwnerId()+"/"+task.getAssetId()+"/source";
        if (!prefix.equals(task.getStorageKey())) throw fail("RESOURCE_STATE_CHANGED","上传来源不匹配",false);
        long size=task.getObjectSizeBytes();
        if (size<1 || size>policy.maxImageBytes() || size>Integer.MAX_VALUE-8)
            throw fail("IMAGE_LIMIT_EXCEEDED","文件字节数超出图片处理上限",false);
        try {
            var args=GetObjectArgs.builder().bucket(storage.bucket()).object(task.getStorageKey()).matchETag(task.getObjectEtag());
            if (task.getObjectVersionId()!=null && !task.getObjectVersionId().isBlank()) args.versionId(task.getObjectVersionId());
            try(var in=client.getObject(args.build())) {
                var headers=in.headers();
                String etag=headers.get("ETag");
                if (etag!=null && etag.startsWith("\"") && etag.endsWith("\"")) etag=etag.substring(1,etag.length()-1);
                String modified=headers.get("Last-Modified");
                LocalDateTime last=modified==null?null:LocalDateTime.ofInstant(ZonedDateTime.parse(modified,
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(),ZoneOffset.UTC);
                if (!Objects.equals(etag,task.getObjectEtag()) || !Objects.equals(headers.get("Content-Type"),task.getObjectContentType())
                        || Long.parseLong(headers.get("Content-Length"))!=size
                        || last==null || !last.equals(task.getObjectLastModified().withNano(0)))
                    throw fail("SOURCE_OBJECT_CHANGED","上传源文件已改变，请重新上传",false);
                byte[] bytes=in.readNBytes((int)size+1);
                if (bytes.length!=size) throw fail("SOURCE_OBJECT_CHANGED","上传源文件大小已改变",false);
                return bytes;
            }
        } catch (MediaProcessingException ex) { throw ex; }
        catch (ErrorResponseException ex) {
            if (java.util.Set.of("NoSuchKey","NoSuchObject","NoSuchVersion","PreconditionFailed").contains(ex.errorResponse().code()))
                throw fail("SOURCE_OBJECT_CHANGED","上传源文件不存在或已改变，请重新上传",false);
            throw fail("STORAGE_UNAVAILABLE","媒体存储暂不可用",true);
        } catch (Exception ex) { throw fail("STORAGE_UNAVAILABLE","媒体存储暂不可用",true); }
    }
    public record WrittenObject(String key,String etag,String storageId) { }
    public WrittenObject write(String key,byte[] bytes,String mime) {
        if (key==null || !key.matches("private/[A-Za-z0-9_-]{1,64}/[A-Za-z0-9_-]{1,64}/ready/[A-Za-z0-9_-]{1,64}"))
            throw fail("RESOURCE_STATE_CHANGED","结果对象路径不合法",false);
        try {
            // 已知长度的图片始终单 PUT；禁用 SDK 的分片重试，重试预算统一由任务租约控制。
            long partSize=Math.max(5*1024*1024L,bytes.length);
            var result=client.putObject(PutObjectArgs.builder().bucket(storage.bucket()).object(key).contentType(mime)
                    .stream(new ByteArrayInputStream(bytes),(long)bytes.length,partSize).maxRetries(0).build());
            var actual=client.statObject(StatObjectArgs.builder().bucket(storage.bucket()).object(key).build());
            if (actual.size()!=bytes.length || !mime.equals(actual.contentType()) || !result.etag().equals(actual.etag()))
                throw fail("STORAGE_UNAVAILABLE","结果对象核验失败",true);
            return new WrittenObject(key,result.etag(),storage.identity());
        } catch (MediaProcessingException ex) { throw ex; }
        catch(Exception ex) { throw fail("STORAGE_UNAVAILABLE","媒体结果保存暂不可用",true); }
    }
    public void deleteOutput(String key) {
        if (key==null || !key.matches("private/[A-Za-z0-9_-]{1,64}/[A-Za-z0-9_-]{1,64}/ready/[A-Za-z0-9_-]{1,64}"))
            throw fail("RESOURCE_STATE_CHANGED","禁止清理非结果对象",false);
        try { client.removeObject(RemoveObjectArgs.builder().bucket(storage.bucket()).object(key).build()); }
        catch(Exception ex) { throw fail("STORAGE_UNAVAILABLE","媒体结果清理暂不可用",true); }
    }
    private static MediaProcessingException fail(String code,String message,boolean retryable) { return new MediaProcessingException(code,message,retryable); }
    @Override @PreDestroy public void close() throws Exception { client.close(); }
}
