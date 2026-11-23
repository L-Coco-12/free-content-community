package com.freecontent.community.report.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.report.dto.AdminReport;
import com.freecontent.community.report.dto.ReportDecisionRequest;
import com.freecontent.community.report.service.ReportDecisionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Collections;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "运营举报")
public class ReportDecisionController {
    private final ReportDecisionService service;

    public ReportDecisionController(ReportDecisionService service) {
        this.service = service;
    }

    @PostMapping(value = "/api/v1/admin/reports/{reportId}/decisions",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "decideReport", summary = "完成举报处理",
            description = "仅允许处理 OPEN 举报。采纳时必须关联同一目标上已执行的处置记录。",
            security = @SecurityRequirement(name = "bearerAuth"),
            extensions = @Extension(properties = {
                    @ExtensionProperty(name = "x-permissions", value = "[\"report:decide\"]", parseValue = true),
                    @ExtensionProperty(name = "x-idempotency-required", value = "true", parseValue = true)
            }))
    @ApiErrorResponses({ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.RESOURCE_VERSION_CONFLICT, ErrorCode.REPORT_STATE_CONFLICT,
            ErrorCode.IDEMPOTENCY_KEY_REUSED, ErrorCode.REQUEST_IN_PROGRESS, ErrorCode.PAYLOAD_TOO_LARGE,
            ErrorCode.UNSUPPORTED_MEDIA_TYPE})
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "举报处理结果")
    public ApiResponse<AdminReport> decide(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @PathVariable String reportId,
            @Parameter(description = "同一调用者重试时复用，有效期 24 小时", schema = @Schema(minLength = 16, maxLength = 128))
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ReportDecisionRequest input,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal actor, HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "举报处理不接受查询参数");
        }
        if (Collections.list(request.getHeaders("Idempotency-Key")).size() != 1) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "Idempotency-Key 只允许提供一次");
        }
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return ApiResponse.of(service.decide(reportId, input, key, actor, request.getRemoteAddr(), requestId), requestId);
    }
}
