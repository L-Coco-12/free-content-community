package com.freecontent.community.content.moderation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(requiredProperties = {"id", "targetType", "targetId", "action", "reason", "operatorId", "createdAt", "resultingVersion"})
public record ModerationAction(
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String id,
        @Schema(allowableValues = {"CONTENT", "COMMENT"}) String targetType,
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String targetId,
        @Schema(allowableValues = {"TAKE_DOWN", "HIDE_COMMENT"}) String action,
        @Schema(minLength = 1, maxLength = 1000) String reason,
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String operatorId,
        Instant createdAt, @Schema(minimum = "1", maximum = "2147483647") int resultingVersion) { }
