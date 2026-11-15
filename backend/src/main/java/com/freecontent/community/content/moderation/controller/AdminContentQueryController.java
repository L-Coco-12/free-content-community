package com.freecontent.community.content.moderation.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.content.dto.WorkspacePage;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.moderation.service.AdminContentQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/admin/contents")
@Tag(name = "运营内容")
public class AdminContentQueryController {
    private static final Set<String> PARAMETERS = Set.of("page", "pageSize", "authorId", "lifecycle", "publicationStatus");
    private final AdminContentQueryService service;
    public AdminContentQueryController(AdminContentQueryService service) { this.service = service; }

    @GetMapping(value = "/{contentId}", produces = "application/json")
    @Operation(operationId = "getAdminContent", summary = "运营读取内容工作区", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses(value = {MALFORMED_REQUEST, AUTHENTICATION_REQUIRED, PERMISSION_DENIED, ACCOUNT_RESTRICTED, RESOURCE_NOT_FOUND,
            VALIDATION_ERROR, RATE_LIMITED, INTERNAL_ERROR, DEPENDENCY_UNAVAILABLE}, includeCommon = false)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "指定内容的当前工作区")
    public ApiResponse<ContentWorkspace> get(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String contentId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new BusinessException(MALFORMED_REQUEST, "后台内容详情不接受查询参数", null, null,
                request.getParameterMap().keySet().stream().map(name -> ProblemDetailResponse.Violation.query(name, "未定义的查询参数")).toList());
        return ApiResponse.of(service.get(contentId, principal, request.getRemoteAddr()), MDC.get(RequestIdFilter.MDC_KEY));
    }
    @GetMapping(produces = "application/json")
    @Operation(operationId = "listAdminContents", summary = "运营查询全部状态内容", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses(value = {MALFORMED_REQUEST, AUTHENTICATION_REQUIRED, PERMISSION_DENIED, ACCOUNT_RESTRICTED,
            VALIDATION_ERROR, RATE_LIMITED, INTERNAL_ERROR, DEPENDENCY_UNAVAILABLE}, includeCommon = false)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "全平台内容工作区分页列表")
    public ApiResponse<WorkspacePage> list(
            @Parameter(schema = @Schema(type = "integer", minimum = "1", defaultValue = "1")) @RequestParam(required = false) String page,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20")) @RequestParam(required = false) String pageSize,
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @RequestParam(required = false) String authorId,
            @Parameter(schema = @Schema(allowableValues = {"ACTIVE", "DELETED"}, description = "省略查全部")) @RequestParam(required = false) String lifecycle,
            @Parameter(schema = @Schema(allowableValues = {"UNPUBLISHED", "PUBLISHED", "WITHDRAWN", "TAKEN_DOWN"})) @RequestParam(required = false) String publicationStatus,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        for (var entry : request.getParameterMap().entrySet()) {
            if (!PARAMETERS.contains(entry.getKey()) || entry.getValue().length != 1) throw new BusinessException(MALFORMED_REQUEST, "查询参数未定义或重复", null, null,
                    List.of(ProblemDetailResponse.Violation.query(entry.getKey(), "参数未定义或重复")));
        }
        return ApiResponse.of(service.list(page, pageSize, authorId, lifecycle, publicationStatus, principal, request.getRemoteAddr()), MDC.get(RequestIdFilter.MDC_KEY));
    }
}
