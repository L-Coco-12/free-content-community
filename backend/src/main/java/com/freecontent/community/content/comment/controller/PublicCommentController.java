package com.freecontent.community.content.comment.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.comment.dto.CommentPage;
import com.freecontent.community.content.comment.dto.CommentView;
import com.freecontent.community.content.comment.service.PublicCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "评论")
public class PublicCommentController {
    private static final Set<String> PAGE_PARAMETERS = Set.of("cursor", "limit");
    private final PublicCommentService service;

    public PublicCommentController(PublicCommentService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/v1/comments/{commentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getPublicComment", summary = "读取当前可见的评论或删除占位",
            description = "允许游客读取。携带 Token 时必须有效。隐藏或删除的顶级评论仅在有可见回复时提供占位。")
    @ApiErrorResponses(value = {ErrorCode.MALFORMED_REQUEST, ErrorCode.AUTHENTICATION_REQUIRED, ErrorCode.RESOURCE_NOT_FOUND,
            ErrorCode.VALIDATION_ERROR, ErrorCode.RATE_LIMITED, ErrorCode.INTERNAL_ERROR, ErrorCode.DEPENDENCY_UNAVAILABLE},
            includeCommon = false)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "可见评论或顶级楼层占位")
    public ApiResponse<CommentView> read(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @PathVariable String commentId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal viewer, HttpServletRequest request) {
        if (request.getQueryString() != null || request.getContentLengthLong() > 0
                || request.getHeader("Transfer-Encoding") != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "公开评论详情不接受查询参数或请求体");
        }
        return ApiResponse.of(service.read(commentId, viewer, request.getRemoteAddr()), MDC.get(RequestIdFilter.MDC_KEY));
    }

    @GetMapping(value = "/api/v1/contents/{contentId}/comments", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listContentComments", summary = "浏览顶级评论",
            description = "按创建时间和ID倒序读取公开作品的顶级评论。保留仍有可见回复的隐藏或删除楼层占位。")
    @ApiErrorResponses(value = {ErrorCode.MALFORMED_REQUEST, ErrorCode.AUTHENTICATION_REQUIRED, ErrorCode.RESOURCE_NOT_FOUND,
            ErrorCode.CURSOR_INVALID, ErrorCode.CURSOR_EXPIRED, ErrorCode.VALIDATION_ERROR, ErrorCode.RATE_LIMITED,
            ErrorCode.INTERNAL_ERROR, ErrorCode.DEPENDENCY_UNAVAILABLE}, includeCommon = false)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "顶级评论分页")
    public ApiResponse<CommentPage> list(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @PathVariable String contentId,
            @Parameter(schema = @Schema(maxLength = 4096)) @RequestParam(required = false) String cursor,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "50", defaultValue = "20"))
            @RequestParam(required = false) String limit,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal viewer, HttpServletRequest request) {
        validatePageRequest(request);
        return ApiResponse.of(service.list(contentId, cursor, limit, viewer, request.getRemoteAddr()),
                MDC.get(RequestIdFilter.MDC_KEY));
    }

    @GetMapping(value = "/api/v1/comments/{commentId}/replies", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listCommentReplies", summary = "浏览楼层回复",
            description = "按创建时间和ID正序读取公开作品中顶级楼层的可见回复。")
    @ApiErrorResponses(value = {ErrorCode.MALFORMED_REQUEST, ErrorCode.AUTHENTICATION_REQUIRED, ErrorCode.RESOURCE_NOT_FOUND,
            ErrorCode.CURSOR_INVALID, ErrorCode.CURSOR_EXPIRED, ErrorCode.VALIDATION_ERROR, ErrorCode.RATE_LIMITED,
            ErrorCode.INTERNAL_ERROR, ErrorCode.DEPENDENCY_UNAVAILABLE}, includeCommon = false)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "顶级楼层的可见回复")
    public ApiResponse<CommentPage> listReplies(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @PathVariable String commentId,
            @Parameter(schema = @Schema(maxLength = 4096)) @RequestParam(required = false) String cursor,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "50", defaultValue = "20"))
            @RequestParam(required = false) String limit,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal viewer, HttpServletRequest request) {
        validatePageRequest(request);
        return ApiResponse.of(service.listReplies(commentId, cursor, limit, viewer, request.getRemoteAddr()),
                MDC.get(RequestIdFilter.MDC_KEY));
    }

    private static void validatePageRequest(HttpServletRequest request) {
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "评论列表不接受请求体");
        }
        for (var parameter : request.getParameterMap().entrySet()) {
            if (!PAGE_PARAMETERS.contains(parameter.getKey()) || parameter.getValue().length != 1) {
                throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "查询参数未定义或重复");
            }
        }
    }
}
