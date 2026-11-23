package com.freecontent.community.content.comment.dto;

import com.freecontent.community.user.dto.PublicUserView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(name = "Comment", requiredProperties = {"id", "contentId", "rootCommentId", "replyToCommentId", "author",
        "body", "state", "replyCount", "createdAt"})
public record CommentView(
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String id,
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String contentId,
        @Schema(types = {"string", "null"}, minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String rootCommentId,
        @Schema(types = {"string", "null"}, minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String replyToCommentId,
        PublicUserView author,
        @Schema(types = {"string", "null"}, maxLength = 2000) String body,
        @Schema(allowableValues = {"VISIBLE", "DELETED_PLACEHOLDER"}) String state,
        @Schema(minimum = "0") long replyCount, Instant createdAt) { }
