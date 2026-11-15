package com.freecontent.community.content.workspace.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.workspace.dto.PublicationRequest;
import com.freecontent.community.content.workspace.service.ContentRepublicationService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/creator/contents/{contentId}/publication")
@Tag(name = "创作中心")
public class ContentRepublicationController {
    private final ContentRepublicationService service;
    public ContentRepublicationController(ContentRepublicationService service) { this.service = service; }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "republishApprovedContent", summary = "重新公开最近已审核通过的版本", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses(value = {MALFORMED_REQUEST, AUTHENTICATION_REQUIRED, PERMISSION_DENIED, ACCOUNT_RESTRICTED, RESOURCE_NOT_FOUND,
            CONTENT_VERSION_CONFLICT, REMEDIATION_REQUIRED, CONTENT_NOT_EDITABLE, PAYLOAD_TOO_LARGE, UNSUPPORTED_MEDIA_TYPE,
            VALIDATION_ERROR, RATE_LIMITED, INTERNAL_ERROR, DEPENDENCY_UNAVAILABLE}, includeCommon = false)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "重新公开后的当前工作区")
    public ApiResponse<ContentWorkspace> republish(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String contentId,
            @Valid @RequestBody PublicationRequest input, @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new BusinessException(MALFORMED_REQUEST, "重新公开不接受查询参数", null, null,
                request.getParameterMap().keySet().stream().map(name -> ProblemDetailResponse.Violation.query(name, "未定义的查询参数")).toList());
        return ApiResponse.of(service.republish(contentId, input, principal, request.getRemoteAddr(), MDC.get(RequestIdFilter.MDC_KEY)), MDC.get(RequestIdFilter.MDC_KEY));
    }
}
