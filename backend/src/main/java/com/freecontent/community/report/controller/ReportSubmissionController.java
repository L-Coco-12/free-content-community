package com.freecontent.community.report.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.report.dto.ReportRecord;
import com.freecontent.community.report.dto.ReportSubmissionRequest;
import com.freecontent.community.report.service.ReportSubmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "举报")
public class ReportSubmissionController {
    private final ReportSubmissionService service;

    public ReportSubmissionController(ReportSubmissionService service) {
        this.service = service;
    }

    @PostMapping(value = "/api/v1/reports",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createReport", summary = "提交内容或评论举报",
            description = "目标必须当前可见。附件必须为本人已就绪的举报素材。同一举报人对同一目标最多有一条待处理举报。",
            security = @SecurityRequirement(name = "bearerAuth"),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-idempotency-required", value = "true", parseValue = true)))
    @ApiErrorResponses({ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.REPORT_ALREADY_OPEN, ErrorCode.MEDIA_NOT_READY,
            ErrorCode.IDEMPOTENCY_KEY_REUSED, ErrorCode.REQUEST_IN_PROGRESS,
            ErrorCode.PAYLOAD_TOO_LARGE, ErrorCode.UNSUPPORTED_MEDIA_TYPE})
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "举报已提交",
            headers = @Header(name = "Location", description = "/api/v1/me/reports/{reportId}",
                    schema = @Schema(type = "string")))
    public ResponseEntity<ApiResponse<ReportRecord>> create(
            @Parameter(description = "同一调用者重试时复用，有效期 24 小时", schema = @Schema(minLength = 16, maxLength = 128))
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ReportSubmissionRequest body,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal actor, HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "提交举报不接受查询参数");
        }
        if (Collections.list(request.getHeaders("Idempotency-Key")).size() != 1) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "Idempotency-Key 只允许提供一次");
        }
        var result = service.create(body, key, actor, request.getRemoteAddr());
        return ResponseEntity.created(URI.create("/api/v1/me/reports/" + result.id()))
                .body(ApiResponse.of(result, MDC.get(RequestIdFilter.MDC_KEY)));
    }
}
