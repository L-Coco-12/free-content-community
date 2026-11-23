package com.freecontent.community.content.comment.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.comment.dto.HideCommentRequest;
import com.freecontent.community.content.comment.service.CommentHideService;
import com.freecontent.community.content.moderation.dto.ModerationAction;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Collections;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "运营评论")
public class CommentHideController {
    private final CommentHideService service;

    public CommentHideController(CommentHideService service) {
        this.service = service;
    }

    @PostMapping(value = "/api/v1/admin/comments/{commentId}/hides",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "hideComment", summary = "隐藏违规评论",
            description = "需要 comment:hide 权限。校验版本及可见状态，成功后生成运营处置记录。",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses({ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.RESOURCE_VERSION_CONFLICT, ErrorCode.COMMENT_STATE_CONFLICT,
            ErrorCode.IDEMPOTENCY_KEY_REUSED, ErrorCode.REQUEST_IN_PROGRESS, ErrorCode.PAYLOAD_TOO_LARGE,
            ErrorCode.UNSUPPORTED_MEDIA_TYPE})
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "评论隐藏处置已创建")
    public ApiResponse<ModerationAction> hide(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @PathVariable String commentId,
            @Parameter(schema = @Schema(minLength = 16, maxLength = 128))
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody HideCommentRequest input,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal actor, HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "隐藏评论不接受查询参数");
        }
        if (Collections.list(request.getHeaders("Idempotency-Key")).size() != 1) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "Idempotency-Key 只允许提供一次");
        }
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return ApiResponse.of(service.hide(commentId, input, key, actor, request.getRemoteAddr(), requestId), requestId);
    }
}
