package com.freecontent.community.content.workspace.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.content.workspace.service.PublicationWithdrawalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/creator/contents/{contentId}/publication")
@Tag(name = "创作中心")
public class PublicationWithdrawalController {
    private final PublicationWithdrawalService service;
    public PublicationWithdrawalController(PublicationWithdrawalService service) { this.service = service; }

    @DeleteMapping
    @Operation(operationId = "withdrawMyPublication", summary = "主动撤回公开内容", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses(value = {MALFORMED_REQUEST, AUTHENTICATION_REQUIRED, PERMISSION_DENIED, ACCOUNT_RESTRICTED, RESOURCE_NOT_FOUND,
            CONTENT_VERSION_CONFLICT, REMEDIATION_REQUIRED, CONTENT_NOT_EDITABLE, VALIDATION_ERROR, RATE_LIMITED,
            INTERNAL_ERROR, DEPENDENCY_UNAVAILABLE}, includeCommon = false)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "已停止公开，无响应体")
    public ResponseEntity<Void> withdraw(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String contentId,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "2147483647")) @RequestParam String expectedVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) throws IOException {
        for (var entry : request.getParameterMap().entrySet()) {
            if (!entry.getKey().equals("expectedVersion") || entry.getValue().length != 1) {
                throw new BusinessException(MALFORMED_REQUEST, "查询参数未定义或重复", null, null,
                        List.of(ProblemDetailResponse.Violation.query(entry.getKey(), "仅允许单个expectedVersion")));
            }
        }
        if (request.getInputStream().read() != -1) throw new BusinessException(MALFORMED_REQUEST, "撤回公开内容不接受请求体");
        service.withdraw(contentId, expectedVersion, principal, request.getRemoteAddr(), MDC.get(RequestIdFilter.MDC_KEY));
        return ResponseEntity.noContent().build();
    }
}
