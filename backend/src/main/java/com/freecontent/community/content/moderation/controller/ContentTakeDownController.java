package com.freecontent.community.content.moderation.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.*;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.content.moderation.dto.*;
import com.freecontent.community.content.moderation.service.ContentTakeDownService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/admin/contents/{contentId}/take-downs")
@Tag(name = "运营内容")
public class ContentTakeDownController {
    private final ContentTakeDownService service;
    public ContentTakeDownController(ContentTakeDownService service) { this.service = service; }
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "takeDownContent", summary = "强制下架并记录处置", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses(value = {MALFORMED_REQUEST, AUTHENTICATION_REQUIRED, PERMISSION_DENIED, ACCOUNT_RESTRICTED, RESOURCE_NOT_FOUND,
            CONTENT_VERSION_CONFLICT, CONTENT_NOT_EDITABLE, IDEMPOTENCY_KEY_REUSED, REQUEST_IN_PROGRESS, PAYLOAD_TOO_LARGE,
            UNSUPPORTED_MEDIA_TYPE, VALIDATION_ERROR, RATE_LIMITED, INTERNAL_ERROR, DEPENDENCY_UNAVAILABLE}, includeCommon = false)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "下架处置已创建")
    public ResponseEntity<ApiResponse<ModerationAction>> takeDown(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String contentId,
            @Valid @RequestBody TakeDownRequest input,
            @Parameter(schema = @Schema(minLength = 16, maxLength = 128)) @RequestHeader("Idempotency-Key") String key,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new BusinessException(MALFORMED_REQUEST, "强制下架不接受查询参数");
        var headers = request.getHeaders("Idempotency-Key"); headers.nextElement();
        if (headers.hasMoreElements()) throw new BusinessException(MALFORMED_REQUEST, "幂等请求头不能重复");
        var result = service.takeDown(contentId, input, key, principal, request.getRemoteAddr(), MDC.get(RequestIdFilter.MDC_KEY));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result, MDC.get(RequestIdFilter.MDC_KEY)));
    }
}
