package com.freecontent.community.content.workspace.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.workspace.dto.CreateDraftRequest;
import com.freecontent.community.content.workspace.service.ContentDraftCreationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/creator/contents/{contentId}/drafts")
@Tag(name = "创作中心")
public class ContentDraftCreationController {
    private final ContentDraftCreationService service;
    public ContentDraftCreationController(ContentDraftCreationService service) { this.service = service; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createContentDraft", summary = "从历史修订创建新草稿", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses(value = {MALFORMED_REQUEST, AUTHENTICATION_REQUIRED, PERMISSION_DENIED, ACCOUNT_RESTRICTED, RESOURCE_NOT_FOUND,
            CONTENT_VERSION_CONFLICT, DRAFT_ALREADY_EXISTS, CONTENT_NOT_EDITABLE, IDEMPOTENCY_KEY_REUSED, REQUEST_IN_PROGRESS,
            PAYLOAD_TOO_LARGE, UNSUPPORTED_MEDIA_TYPE, VALIDATION_ERROR, RATE_LIMITED, INTERNAL_ERROR, DEPENDENCY_UNAVAILABLE}, includeCommon = false)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "新草稿已创建",
            headers = @Header(name = "Location", description = "/api/v1/creator/contents/{contentId}/revisions/{revisionId}", schema = @Schema(type = "string")))
    public ResponseEntity<ApiResponse<ContentWorkspace>> create(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String contentId,
            @Valid @RequestBody CreateDraftRequest input,
            @Parameter(schema = @Schema(minLength = 16, maxLength = 128)) @RequestHeader("Idempotency-Key") String key,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw new BusinessException(MALFORMED_REQUEST, "创建草稿不接受查询参数", null, null,
                    request.getParameterMap().keySet().stream().map(name -> ProblemDetailResponse.Violation.query(name, "未定义的查询参数")).toList());
        }
        var headers = request.getHeaders("Idempotency-Key");
        headers.nextElement();
        if (headers.hasMoreElements()) throw new BusinessException(MALFORMED_REQUEST, "幂等请求头不能重复", null, null,
                List.of(new ProblemDetailResponse.Violation("header", "Idempotency-Key", "请求头不能重复")));
        var result = service.create(contentId, input, key, principal, request.getRemoteAddr());
        return ResponseEntity.created(URI.create("/api/v1/creator/contents/" + result.id() + "/revisions/" + result.workingRevision().id()))
                .body(ApiResponse.of(result, MDC.get(RequestIdFilter.MDC_KEY)));
    }
}
