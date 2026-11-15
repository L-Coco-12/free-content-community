package com.freecontent.community.media.job;

import com.freecontent.community.media.config.MediaProcessingProperties;
import com.freecontent.community.media.mapper.MediaProcessingTaskMapper;
import com.freecontent.community.media.mapper.MediaProcessingOutputMapper;
import com.freecontent.community.media.processing.*;
import com.freecontent.community.media.service.transaction.MediaProcessingTransactionService;
import com.freecontent.community.media.storage.MediaProcessingStorage;
import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 调度只派发工作；单执行线程及 busy 门闩限制在途文件数，不积累无界解码队列。 */
@Component
public class MediaProcessingWorker {
    private static final Logger log=LoggerFactory.getLogger(MediaProcessingWorker.class);
    private final MediaProcessingTaskMapper tasks;
    private final MediaProcessingOutputMapper outputs;
    private final MediaProcessingTransactionService transactions;
    private final MediaProcessingStorage storage;
    private final ImageValidator images;
    private final MediaProcessingProperties properties;
    private final Clock clock;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{
        var thread=new Thread(r,"media-processing"); thread.setDaemon(true); return thread;
    });
    public MediaProcessingWorker(MediaProcessingTaskMapper tasks,MediaProcessingOutputMapper outputs,MediaProcessingTransactionService transactions,
            MediaProcessingStorage storage,ImageValidator images,MediaProcessingProperties properties,Clock clock) {
        this.tasks=tasks; this.outputs=outputs; this.transactions=transactions; this.storage=storage; this.images=images; this.properties=properties; this.clock=clock;
    }
    @Scheduled(fixedDelayString="${app.media.processing.poll-delay-millis:1000}")
    public void poll() {
        if (!properties.enabled() || !busy.compareAndSet(false,true)) return;
        try { executor.execute(()->{
            try {
                var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
                for(String id:tasks.findDue(now)) processTask(id);
                for(String id:outputs.findDue(now)) cleanupOutput(id);
            } catch(RuntimeException ex) { log.warn("Media processing poll failed, errorType={}",ex.getClass().getSimpleName()); }
            finally { busy.set(false); }
        }); } catch(RejectedExecutionException ex) { busy.set(false); }
    }
    /** 单任务入口供受控运维和真实存储集成测试使用；不作为 HTTP 接口。 */
    public void processTask(String id) {
        var claim=transactions.claim(id);
        if (claim==null) return;
        try {
            byte[] bytes=storage.read(claim.task());
            var image=images.validate(bytes,claim.asset().getDeclaredContentType(),claim.task().getExpectedSha256());
            var object=storage.write(claim.outputKey(),bytes,image.contentType());
            transactions.succeed(claim,image,bytes.length,object);
        } catch(ImageValidationException ex) {
            boolean retryable="PROCESSING_INTERRUPTED".equals(ex.code()) || Thread.currentThread().isInterrupted();
            transactions.fail(claim,ex.code(),ex.getMessage(),retryable);
        }
        catch(MediaProcessingException ex) { transactions.fail(claim,ex.code(),ex.getMessage(),ex.retryable()); }
        catch(IOException ex) { transactions.fail(claim,"INVALID_IMAGE","图片无法完整解码",false); }
        // 数据库提交失败不伪装成解码失败；保留租约和结果登记，过期接管并补偿。
    }
    public void cleanupOutput(String id) {
        var claim=transactions.claimCleanup(id);
        if (claim==null) return;
        try { storage.deleteOutput(claim.key()); }
        catch(MediaProcessingException ex) { transactions.finishCleanup(claim,false); return; }
        transactions.finishCleanup(claim,true);
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
