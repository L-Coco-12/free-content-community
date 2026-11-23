package com.freecontent.community.content.comment.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.comment.dto.CommentView;
import com.freecontent.community.content.comment.dto.CreateCommentRequest;
import com.freecontent.community.content.comment.service.CommentCreationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Collections;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "评论")
public class CommentCreationController {
    private final CommentCreationService service;

    public CommentCreationController(CommentCreationService service) {
        this.service = service;
    }

    @PostMapping(value = "/api/v1/contents/{contentId}/comments",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createComment", summary = "发布评论或回复",
            description = "仅允许评论当前公开内容；回复必须指向同一作品中的可见评论。",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses({ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.IDEMPOTENCY_KEY_REUSED, ErrorCode.REQUEST_IN_PROGRESS,
            ErrorCode.PAYLOAD_TOO_LARGE, ErrorCode.UNSUPPORTED_MEDIA_TYPE})
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "评论已创建",
            headers = @Header(name = "Location", description = "/api/v1/comments/{commentId}",
                    schema = @Schema(type = "string")))
    public ResponseEntity<ApiResponse<CommentView>> create(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @PathVariable String contentId,
            @Parameter(schema = @Schema(minLength = 16, maxLength = 128))
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateCommentRequest body,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal actor, HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "评论发布不接受查询参数");
        }
        if (Collections.list(request.getHeaders("Idempotency-Key")).size() != 1) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "Idempotency-Key 只允许提供一次");
        }
        var result = service.create(contentId, body, key, actor, request.getRemoteAddr());
        return ResponseEntity.created(URI.create("/api/v1/comments/" + result.id()))
                .body(ApiResponse.of(result, MDC.get(RequestIdFilter.MDC_KEY)));
    }
}
