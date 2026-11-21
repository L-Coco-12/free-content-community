package com.freecontent.community.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(name = "Notification", requiredProperties = {"id", "type", "title", "body", "targetType", "targetId", "readAt", "createdAt"})
public record NotificationView(
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String id,
        @Schema(allowableValues = {"COMMENT_REPLY", "NEW_FOLLOWER", "REVIEW_RESULT", "MODERATION_RESULT", "REPORT_RESULT", "MEDIA_RESULT"})
        String type,
        @Schema(maxLength = 120) String title,
        @Schema(maxLength = 500) String body,
        @Schema(types = {"string", "null"}, description = "目标类型，CONTENT、COMMENT、REVIEW、REPORT、TASK 或 null")
        String targetType,
        @Schema(types = {"string", "null"}, minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String targetId,
        @Schema(types = {"string", "null"}, format = "date-time") Instant readAt,
        Instant createdAt) { }
