package com.freecontent.community.media.service.transaction;

import com.freecontent.community.media.config.*;
import com.freecontent.community.media.entity.*;
import com.freecontent.community.media.mapper.*;
import com.freecontent.community.media.processing.ImageValidator.ValidatedImage;
import com.freecontent.community.media.storage.MediaProcessingStorage.WrittenObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** 锁序：任务→上传会话→素材→结果记录。存储读写及图片解码均不占用这些锁。 */
@Service
public class MediaProcessingTransactionService {
    private final MediaProcessingTaskMapper tasks;
    private final MediaUploadMapper uploads;
    private final MediaAssetMapper assets;
    private final MediaProcessingOutputMapper outputs;
    private final MediaProcessingProperties properties;
    private final StorageProperties storage;
    private final Clock clock;
    public MediaProcessingTransactionService(MediaProcessingTaskMapper tasks,MediaUploadMapper uploads,MediaAssetMapper assets,
            MediaProcessingOutputMapper outputs,MediaProcessingProperties properties,StorageProperties storage,Clock clock) {
        this.tasks=tasks; this.uploads=uploads; this.assets=assets; this.outputs=outputs;
        this.properties=properties; this.storage=storage; this.clock=clock;
    }
    public record Claim(MediaProcessingTask task,MediaAsset asset,String token,String outputKey) { }

    @Transactional
    public Claim claim(String id) {
        var task=tasks.lock(id); var now=now();
        if (task==null || !"MEDIA_PROCESSING".equals(task.getType())) return null;
        boolean pending="PENDING".equals(task.getState()) && (task.getAvailableAt()==null || !task.getAvailableAt().isAfter(now));
        boolean expired="RUNNING".equals(task.getState()) && (task.getLeaseUntil()==null || !task.getLeaseUntil().isAfter(now));
        if (!pending && !expired) return null;
        var asset=lockedAsset(task);
        if (!eligible(task,asset)) { terminal(task,asset,"RESOURCE_STATE_CHANGED","上传或素材状态已变化",false); return null; }
        if (!storage.identity().equals(task.getSignerId())) { terminal(task,asset,"STORAGE_CONFIG_CHANGED","上传存储配置已变化",false); return null; }
        if (task.getAttemptCount()>=properties.maxAttempts()) { terminal(task,asset,"ATTEMPTS_EXHAUSTED","媒体处理重试次数已用尽",true); return null; }
        String token=UUID.randomUUID().toString().replace("-", "");
        task.setState("RUNNING"); task.setLeaseToken(token); task.setLeaseUntil(now.plusSeconds(properties.leaseSeconds()));
        task.setAttemptCount(task.getAttemptCount()+1); task.setProgressPercent(10); task.setRetryable(false);
        task.setErrorCode(null); task.setErrorMessage(null); task.setAvailableAt(null); task.setUpdatedAt(now);
        tasks.saveState(task);
        String key="private/"+task.getOwnerId()+"/"+task.getAssetId()+"/ready/"+token;
        var output=new MediaProcessingOutput(); output.setId(token); output.setTaskId(task.getId());
        output.setObjectKey(key); output.setStorageId(storage.identity()); output.setState("RESERVED"); output.setCreatedAt(now);
        output.setAvailableAt(task.getLeaseUntil().plusSeconds(properties.orphanGraceSeconds())); outputs.insert(output);
        return new Claim(task,asset,token,key);
    }

    @Transactional
    public boolean succeed(Claim claim,ValidatedImage image,long size,WrittenObject object) {
        var task=tasks.lock(claim.task().getId());
        if (!owns(task,claim.token())) return false;
        var asset=lockedAsset(task);
        var output=outputs.selectById(claim.token());
        if (!eligible(task,asset) || !sameSource(claim,task,asset) || output==null || !"RESERVED".equals(output.getState())
                || !Objects.equals(output.getObjectKey(),object.key()) || !Objects.equals(claim.outputKey(),object.key())
                || !Objects.equals(object.storageId(),storage.identity()) || !Objects.equals(task.getSignerId(),object.storageId())
                || object.etag()==null || object.etag().isBlank() || size!=task.getObjectSizeBytes()
                || !image.contentType().equals(asset.getDeclaredContentType())) {
            terminal(task,asset,"RESOURCE_STATE_CHANGED","处理期间上传来源或素材状态已变化",false); return false;
        }
        asset.setState("READY"); asset.setActualContentType(image.contentType()); asset.setActualSizeBytes(size);
        asset.setWidth(image.width()); asset.setHeight(image.height());
        asset.setReadyObjectKey(object.key()); asset.setReadyObjectEtag(object.etag()); asset.setReadyStorageId(object.storageId());
        if (assets.updateById(asset)!=1) throw new IllegalStateException("素材结果提交失败");
        output.setState("BOUND"); outputs.updateById(output);
        task.setState("SUCCEEDED"); task.setProgressPercent(100); task.setRetryable(false);
        task.setErrorCode(null); task.setErrorMessage(null); clearLease(task); tasks.saveState(task);
        return true;
    }

    @Transactional
    public void fail(Claim claim,String code,String message,boolean retryable) {
        var task=tasks.lock(claim.task().getId());
        if (!owns(task,claim.token())) return;
        var asset=lockedAsset(task);
        if (retryable && eligible(task,asset) && task.getAttemptCount()<properties.maxAttempts()) {
            long delay=Math.min(300L,(long)properties.retryBaseSeconds()*(1L<<Math.min(10,task.getAttemptCount()-1)));
            task.setState("PENDING"); task.setProgressPercent(0); task.setRetryable(false);
            task.setErrorCode(null); task.setErrorMessage(null); task.setAvailableAt(now().plusSeconds(delay));
            clearLease(task); tasks.saveState(task);
        } else terminal(task,asset,code,message,retryable);
    }

    public record CleanupClaim(String id,String token,String key) { }

    @Transactional
    public CleanupClaim claimCleanup(String id) {
        var visible=outputs.selectById(id);
        if (visible==null) return null;
        var task=tasks.lock(visible.getTaskId());
        if (task==null) return null;
        var asset=assets.findOwnedForUpdate(task.getAssetId(),task.getOwnerId());
        var output=outputs.lock(id); var now=now();
        if (output==null || "BOUND".equals(output.getState())
                || !storage.identity().equals(output.getStorageId()) || output.getAvailableAt().isAfter(now)
                || ("CLEANING".equals(output.getState()) && output.getLeaseUntil()!=null && output.getLeaseUntil().isAfter(now))) return null;
        if (asset==null || Objects.equals(asset.getReadyObjectKey(),output.getObjectKey())) return null;
        if (!output.getObjectKey().equals("private/"+task.getOwnerId()+"/"+task.getAssetId()+"/ready/"+output.getId())) return null;
        if (owns(task,output.getId())) return null;
        output.setState("CLEANING"); output.setLeaseToken(UUID.randomUUID().toString().replace("-", ""));
        output.setLeaseUntil(now.plusSeconds(properties.leaseSeconds())); outputs.saveState(output);
        return new CleanupClaim(id,output.getLeaseToken(),output.getObjectKey());
    }

    @Transactional
    public void finishCleanup(CleanupClaim claim,boolean succeeded) {
        var output=outputs.lock(claim.id()); var now=now();
        if (output==null || !"CLEANING".equals(output.getState()) || !claim.token().equals(output.getLeaseToken())
                || output.getLeaseUntil()==null || !output.getLeaseUntil().isAfter(now)) return;
        output.setState(succeeded?"CLEANED":"RESERVED"); output.setLeaseToken(null); output.setLeaseUntil(null);
        // 已清理记录每天复查：被暂停的旧进程可能在清理后才执行迟到 PUT。
        output.setAvailableAt(succeeded?now.plusDays(1):now.plusSeconds(Math.max(30,properties.retryBaseSeconds())));
        outputs.saveState(output);
    }

    private MediaAsset lockedAsset(MediaProcessingTask task) {
        var upload=uploads.findOwnedForShare(task.getUploadId(),task.getOwnerId());
        if (upload==null || !"COMPLETED".equals(upload.getState()) || !Objects.equals(upload.getAssetId(),task.getAssetId())
                || !Objects.equals(upload.getStorageKey(),task.getStorageKey()) || !Objects.equals(upload.getSignerId(),task.getSignerId())
                || !Objects.equals(upload.getContentType(),task.getObjectContentType())) return null;
        return assets.findOwnedForUpdate(task.getAssetId(),task.getOwnerId());
    }
    private boolean eligible(MediaProcessingTask task,MediaAsset asset) {
        return asset!=null && "PROCESSING".equals(asset.getState()) && asset.getReadyObjectKey()==null
                && Objects.equals(asset.getStorageKey(),task.getStorageKey())
                && Objects.equals(asset.getDeclaredContentType(),task.getObjectContentType())
                && Objects.equals(asset.getDeclaredSizeBytes(),task.getObjectSizeBytes())
                && Objects.equals(asset.getExpectedSha256(),task.getExpectedSha256())
                && Objects.equals(task.getStorageKey(),"private/"+task.getOwnerId()+"/"+task.getAssetId()+"/source");
    }
    private boolean sameSource(Claim claim,MediaProcessingTask task,MediaAsset asset) {
        var old=claim.task();
        return Objects.equals(old.getUploadId(),task.getUploadId()) && Objects.equals(old.getOwnerId(),task.getOwnerId())
                && Objects.equals(old.getAssetId(),task.getAssetId()) && Objects.equals(old.getStorageKey(),task.getStorageKey())
                && Objects.equals(old.getSignerId(),task.getSignerId()) && Objects.equals(old.getObjectEtag(),task.getObjectEtag())
                && Objects.equals(old.getObjectVersionId(),task.getObjectVersionId()) && Objects.equals(old.getObjectSizeBytes(),task.getObjectSizeBytes())
                && Objects.equals(old.getObjectContentType(),task.getObjectContentType()) && Objects.equals(old.getObjectLastModified(),task.getObjectLastModified())
                && Objects.equals(old.getExpectedSha256(),task.getExpectedSha256()) && Objects.equals(claim.asset().getScene(),asset.getScene());
    }
    private boolean owns(MediaProcessingTask task,String token) {
        return task!=null && "RUNNING".equals(task.getState()) && token.equals(task.getLeaseToken())
                && task.getLeaseUntil()!=null && task.getLeaseUntil().isAfter(now());
    }
    private void terminal(MediaProcessingTask task,MediaAsset asset,String code,String message,boolean retryable) {
        if (eligible(task,asset)) { asset.setState("FAILED"); assets.updateById(asset); }
        task.setState("FAILED"); task.setRetryable(retryable); task.setErrorCode(code); task.setErrorMessage(message);
        clearLease(task); tasks.saveState(task);
    }
    private void clearLease(MediaProcessingTask task) { task.setLeaseToken(null); task.setLeaseUntil(null); task.setUpdatedAt(now()); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC); }
}
