package com.freecontent.community.media.job;

import com.freecontent.community.media.config.MediaProcessingProperties;
import com.freecontent.community.media.entity.*;
import com.freecontent.community.media.mapper.*;
import com.freecontent.community.media.processing.ImageValidator;
import com.freecontent.community.media.service.transaction.MediaProcessingTransactionService;
import com.freecontent.community.media.storage.MediaProcessingStorage;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MediaProcessingWorkerTest {
    @Test void shutdownInterruptionIsRetryableRatherThanAFileValidationFailure() throws Exception {
        var transactions=mock(MediaProcessingTransactionService.class);
        var storage=mock(MediaProcessingStorage.class);
        var task=new MediaProcessingTask(); var asset=new MediaAsset(); asset.setDeclaredContentType("image/png");
        var claim=new MediaProcessingTransactionService.Claim(task,asset,"token","unused-key");
        when(transactions.claim("task")).thenReturn(claim);
        var out=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB),"png",out);
        when(storage.read(task)).thenReturn(out.toByteArray());
        var properties=new MediaProcessingProperties(false,1000,120,3,5,120,8192,8192,25_000_000,100,100_000_000,30);
        var worker=new MediaProcessingWorker(mock(MediaProcessingTaskMapper.class),mock(MediaProcessingOutputMapper.class),
                transactions,storage,new ImageValidator(),properties,Clock.systemUTC());
        try {
            Thread.currentThread().interrupt(); worker.processTask("task");
        } finally { Thread.interrupted(); worker.close(); }
        verify(transactions).fail(eq(claim),eq("PROCESSING_INTERRUPTED"),anyString(),eq(true));
        verify(storage,never()).write(anyString(),any(),anyString());
    }
}
