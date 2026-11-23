package com.freecontent.community.report.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse.Violation;
import com.freecontent.community.report.dto.AdminReport;
import com.freecontent.community.report.dto.AdminReportPage;
import com.freecontent.community.report.service.AdminReportQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "运营举报")
public class AdminReportQueryController {
    private static final Set<String> PARAMETERS = Set.of("page", "pageSize", "state");
    private final AdminReportQueryService service;

    public AdminReportQueryController(AdminReportQueryService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/v1/admin/reports", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listAdminReports", summary = "查询举报待办",
            description = "需要 report:read 权限。待办按提交时间和 ID 正序，历史记录按提交时间和 ID 倒序。",
            security = @SecurityRequirement(name = "bearerAuth"),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-permissions", value = "[\"report:read\"]", parseValue = true)))
    @ApiErrorResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "运营举报分页列表")
    public ApiResponse<AdminReportPage> list(
            @Parameter(schema = @Schema(type = "integer", minimum = "1", defaultValue = "1"))
            @RequestParam(required = false) String page,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
            @RequestParam(required = false) String pageSize,
            @Parameter(schema = @Schema(allowableValues = {"OPEN", "RESOLVED", "DISMISSED"}, defaultValue = "OPEN"))
            @RequestParam(required = false) String state,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal actor, HttpServletRequest request) {
        if (hasRequestBody(request)) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "运营举报列表不接受请求体");
        }
        for (var parameter : request.getParameterMap().entrySet()) {
            if (!PARAMETERS.contains(parameter.getKey()) || parameter.getValue().length != 1) {
                throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "查询参数未定义或重复", null, null,
                        List.of(Violation.query(parameter.getKey(), "参数未定义或重复")));
            }
        }
        var result = service.list(page, pageSize, state, actor, request.getRemoteAddr());
        return ApiResponse.of(result, MDC.get(RequestIdFilter.MDC_KEY));
    }

    @GetMapping(value = "/api/v1/admin/reports/{reportId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getAdminReport", summary = "读取举报详情",
            description = "需要 report:read 权限。返回指定举报的当前处理状态、举报人 ID 和附件 ID。",
            security = @SecurityRequirement(name = "bearerAuth"),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-permissions", value = "[\"report:read\"]", parseValue = true)))
    @ApiErrorResponses(ErrorCode.RESOURCE_NOT_FOUND)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "运营举报详情")
    public ApiResponse<AdminReport> get(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @PathVariable String reportId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal actor, HttpServletRequest request) {
        if (request.getQueryString() != null || hasRequestBody(request)) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "运营举报详情不接受查询参数或请求体");
        }
        var result = service.get(reportId, actor, request.getRemoteAddr());
        return ApiResponse.of(result, MDC.get(RequestIdFilter.MDC_KEY));
    }

    private static boolean hasRequestBody(HttpServletRequest request) {
        return request.getContentLengthLong() > 0 || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null;
    }
}
