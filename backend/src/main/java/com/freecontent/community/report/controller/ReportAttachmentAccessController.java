package com.freecontent.community.report.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.media.dto.MediaAccess;
import com.freecontent.community.report.service.ReportAttachmentAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "运营举报")
public class ReportAttachmentAccessController {
    private final ReportAttachmentAccessService service;

    public ReportAttachmentAccessController(ReportAttachmentAccessService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/v1/admin/reports/{reportId}/attachments/{assetId}/access",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getReportAttachmentAccess", summary = "读取举报附件",
            description = "需要 report:read 权限。只为已关联的举报附件签发短时读取地址，并记录访问审计。",
            security = @SecurityRequirement(name = "bearerAuth"),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-permissions", value = "[\"report:read\"]", parseValue = true)))
    @ApiErrorResponses({ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.MEDIA_NOT_READY})
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "举报附件的短时私有读取地址")
    public ApiResponse<MediaAccess> getAccess(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @PathVariable String reportId,
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @PathVariable String assetId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal actor, HttpServletRequest request) {
        if (request.getQueryString() != null || request.getContentLengthLong() > 0
                || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "举报附件访问不接受查询参数或请求体");
        }
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        var result = service.getAccess(reportId, assetId, actor, request.getRemoteAddr(), requestId);
        return ApiResponse.of(result, requestId);
    }
}
