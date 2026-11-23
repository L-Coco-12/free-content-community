package com.freecontent.community.content.comment.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.content.comment.dto.AdminComment;
import com.freecontent.community.content.comment.dto.AdminCommentPage;
import com.freecontent.community.content.comment.service.AdminCommentQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "运营评论")
public class AdminCommentQueryController {
    private static final Set<String> PARAMETERS = Set.of("page", "pageSize", "contentId", "authorId", "state");
    private final AdminCommentQueryService service;

    public AdminCommentQueryController(AdminCommentQueryService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/v1/admin/comments/{commentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getAdminComment", summary = "运营读取评论",
            description = "需要审核员或管理员的 comment:moderate:read 权限。返回指定评论的原文、内部状态和版本。",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses(ErrorCode.RESOURCE_NOT_FOUND)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "运营评论详情")
    public ApiResponse<AdminComment> get(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @PathVariable String commentId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal actor, HttpServletRequest request) {
        if (request.getQueryString() != null || request.getContentLengthLong() > 0
                || request.getHeader("Transfer-Encoding") != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "运营评论详情不接受查询参数或请求体");
        }
        return ApiResponse.of(service.get(commentId, actor, request.getRemoteAddr()), MDC.get(RequestIdFilter.MDC_KEY));
    }

    @GetMapping(value = "/api/v1/admin/comments", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listAdminComments", summary = "运营查询评论",
            description = "需要审核员或管理员的 comment:moderate:read 权限。返回原文和内部状态，按创建时间及ID倒序。",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "运营评论分页列表")
    public ApiResponse<AdminCommentPage> list(
            @Parameter(schema = @Schema(type = "integer", minimum = "1", defaultValue = "1"))
            @RequestParam(required = false) String page,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
            @RequestParam(required = false) String pageSize,
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @RequestParam(required = false) String contentId,
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @RequestParam(required = false) String authorId,
            @Parameter(schema = @Schema(allowableValues = {"VISIBLE", "HIDDEN", "DELETED"}))
            @RequestParam(required = false) String state,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal actor, HttpServletRequest request) {
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "运营评论列表不接受请求体");
        }
        for (var parameter : request.getParameterMap().entrySet()) {
            if (!PARAMETERS.contains(parameter.getKey()) || parameter.getValue().length != 1) {
                throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "查询参数未定义或重复", null, null,
                        List.of(ProblemDetailResponse.Violation.query(parameter.getKey(), "参数未定义或重复")));
            }
        }
        return ApiResponse.of(service.list(page, pageSize, contentId, authorId, state, actor, request.getRemoteAddr()),
                MDC.get(RequestIdFilter.MDC_KEY));
    }
}
