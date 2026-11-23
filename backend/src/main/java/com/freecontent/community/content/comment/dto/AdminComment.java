package com.freecontent.community.content.comment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(requiredProperties = {"id", "contentId", "authorId", "body", "state", "version", "createdAt"})
public record AdminComment(
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String id,
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String contentId,
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String authorId,
        @Schema(description = "原始纯文本，仅限授权运营查看") String body,
        @Schema(allowableValues = {"VISIBLE", "HIDDEN", "DELETED"}) String state,
        @Schema(minimum = "1", maximum = "2147483647") int version,
        Instant createdAt) { }
