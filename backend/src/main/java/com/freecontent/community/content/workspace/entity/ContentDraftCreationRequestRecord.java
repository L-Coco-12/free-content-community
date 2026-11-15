package com.freecontent.community.content.workspace.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @TableName("content_draft_creation_request")
public class ContentDraftCreationRequestRecord {
    private String id;
    private String createdBy;
    private String keyHash;
    private String fingerprint;
    private String state;
    private String contentId;
    private String revisionId;
    private String resultSnapshot;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
