package com.freecontent.community.content.workspace.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.workspace.dto.VersionRequest;
import com.freecontent.community.content.workspace.service.ContentRestorationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/creator/contents/{contentId}/restorations")
@Tag(name = "创作中心")
public class ContentRestorationController {
    private final ContentRestorationService service;
    public ContentRestorationController(ContentRestorationService service) { this.service = service; }
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "restoreMyContent", summary = "从回收站恢复本人内容", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses(value = {MALFORMED_REQUEST, AUTHENTICATION_REQUIRED, PERMISSION_DENIED, ACCOUNT_RESTRICTED, RESOURCE_NOT_FOUND,
            CONTENT_VERSION_CONFLICT, CONTENT_NOT_EDITABLE, IDEMPOTENCY_KEY_REUSED, REQUEST_IN_PROGRESS, PAYLOAD_TOO_LARGE,
            UNSUPPORTED_MEDIA_TYPE, VALIDATION_ERROR, RATE_LIMITED, INTERNAL_ERROR, DEPENDENCY_UNAVAILABLE}, includeCommon = false)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "恢复后的工作区")
    public ApiResponse<ContentWorkspace> restore(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String contentId,
            @Valid @RequestBody VersionRequest input,
            @Parameter(schema = @Schema(minLength = 16, maxLength = 128)) @RequestHeader("Idempotency-Key") String key,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new BusinessException(MALFORMED_REQUEST, "恢复内容不接受查询参数");
        var headers = request.getHeaders("Idempotency-Key"); headers.nextElement();
        if (headers.hasMoreElements()) throw new BusinessException(MALFORMED_REQUEST, "幂等请求头不能重复");
        return ApiResponse.of(service.restore(contentId, input, key, principal, request.getRemoteAddr(), MDC.get(RequestIdFilter.MDC_KEY)), MDC.get(RequestIdFilter.MDC_KEY));
    }
}
