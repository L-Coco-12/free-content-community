package com.freecontent.community.content.moderation.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @TableName("content_take_down_request")
public class ContentTakeDownRequestRecord {
    private String id;
    private String createdBy;
    private String keyHash;
    private String fingerprint;
    private String state;
    private String contentId;
    private String actionId;
    private String resultSnapshot;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
