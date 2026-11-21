package com.freecontent.community.user.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.dto.AdminUser;
import com.freecontent.community.user.dto.RestrictionRequest;
import com.freecontent.community.user.service.UserRestrictionService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "用户管理")
public class UserRestrictionController {
    private final UserRestrictionService service;

    public UserRestrictionController(UserRestrictionService service) {
        this.service = service;
    }

    @PutMapping(value = "/api/v1/admin/users/{userId}/restriction",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "restrictUser", summary = "设置用户限制状态",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses({ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.RESOURCE_VERSION_CONFLICT,
            ErrorCode.LAST_ADMIN_PROTECTED, ErrorCode.PAYLOAD_TOO_LARGE, ErrorCode.UNSUPPORTED_MEDIA_TYPE})
    public ApiResponse<AdminUser> restrictUser(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @PathVariable String userId, @Valid @RequestBody RestrictionRequest input,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "账号限制不接受查询参数");
        }
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return ApiResponse.of(service.restrict(userId, input, principal, request.getRemoteAddr(), requestId), requestId);
    }
}
