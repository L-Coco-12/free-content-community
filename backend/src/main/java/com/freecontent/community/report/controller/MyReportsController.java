package com.freecontent.community.report.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse.Violation;
import com.freecontent.community.report.dto.ReportPage;
import com.freecontent.community.report.dto.ReportRecord;
import com.freecontent.community.report.service.MyReportsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
@Tag(name = "举报")
public class MyReportsController {
    private static final Set<String> PARAMETERS = Set.of("page", "pageSize");
    private final MyReportsService service;

    public MyReportsController(MyReportsService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/v1/me/reports", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listMyReports", summary = "查询本人举报记录",
            description = "仅返回当前账号提交的举报，按提交时间和 ID 倒序。",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "本人举报分页列表")
    public ApiResponse<ReportPage> list(
            @Parameter(schema = @Schema(type = "integer", minimum = "1", defaultValue = "1"))
            @RequestParam(required = false) String page,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
            @RequestParam(required = false) String pageSize,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal actor, HttpServletRequest request) {
        if (hasRequestBody(request)) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "本人举报列表不接受请求体");
        }
        for (var parameter : request.getParameterMap().entrySet()) {
            if (!PARAMETERS.contains(parameter.getKey()) || parameter.getValue().length != 1) {
                throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "查询参数未定义或重复", null, null,
                        List.of(Violation.query(parameter.getKey(), "参数未定义或重复")));
            }
        }
        var result = service.list(page, pageSize, actor, request.getRemoteAddr());
        return ApiResponse.of(result, MDC.get(RequestIdFilter.MDC_KEY));
    }

    @GetMapping(value = "/api/v1/me/reports/{reportId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getMyReport", summary = "查询本人举报处理结果",
            description = "仅返回本人提交的举报。目标下架或隐藏后仍可查询该举报。",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses(ErrorCode.RESOURCE_NOT_FOUND)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "本人举报详情")
    public ApiResponse<ReportRecord> get(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @PathVariable String reportId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal actor, HttpServletRequest request) {
        if (request.getQueryString() != null || hasRequestBody(request)) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "本人举报详情不接受查询参数或请求体");
        }
        var result = service.get(reportId, actor, request.getRemoteAddr());
        return ApiResponse.of(result, MDC.get(RequestIdFilter.MDC_KEY));
    }

    private static boolean hasRequestBody(HttpServletRequest request) {
        return request.getContentLengthLong() > 0 || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null;
    }
}
