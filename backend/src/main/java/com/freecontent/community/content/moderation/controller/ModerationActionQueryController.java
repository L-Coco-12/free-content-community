package com.freecontent.community.content.moderation.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.moderation.dto.ModerationAction;
import com.freecontent.community.content.moderation.service.ModerationActionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "运营内容")
public class ModerationActionQueryController {
    private final ModerationActionQueryService service;

    public ModerationActionQueryController(ModerationActionQueryService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/v1/admin/moderation-actions/{actionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getModerationAction", summary = "读取已执行的内容或评论处置",
            description = "需要 moderation:read 权限。按 ID 返回已执行处置的原始原因和结果版本。",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses(ErrorCode.RESOURCE_NOT_FOUND)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "已执行的处置详情")
    public ApiResponse<ModerationAction> get(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @PathVariable String actionId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal actor, HttpServletRequest request) {
        if (request.getQueryString() != null || request.getContentLengthLong() > 0
                || request.getHeader("Transfer-Encoding") != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "运营处置详情不接受查询参数或请求体");
        }
        return ApiResponse.of(service.get(actionId, actor, request.getRemoteAddr()), MDC.get(RequestIdFilter.MDC_KEY));
    }
}
