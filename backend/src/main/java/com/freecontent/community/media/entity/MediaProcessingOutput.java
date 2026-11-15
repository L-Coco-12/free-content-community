package com.freecontent.community.media.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter @TableName("media_processing_output")
public class MediaProcessingOutput {
    private String id;
    private String taskId;
    private String objectKey;
    private String storageId;
    private String state;
    private LocalDateTime createdAt;
    private LocalDateTime availableAt;
    private String leaseToken;
    private LocalDateTime leaseUntil;
}
