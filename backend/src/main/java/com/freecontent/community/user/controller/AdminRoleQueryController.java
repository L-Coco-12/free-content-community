package com.freecontent.community.user.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.dto.RoleList;
import com.freecontent.community.user.service.AdminRoleQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "用户管理")
public class AdminRoleQueryController {
    private final AdminRoleQueryService service;

    public AdminRoleQueryController(AdminRoleQueryService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/v1/admin/roles", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listRoles", summary = "查询预置角色及权限",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses
    public ApiResponse<RoleList> listRoles(
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest request) {
        if (request.getQueryString() != null || request.getContentLengthLong() > 0
                || request.getHeader("Transfer-Encoding") != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "角色查询不接受查询参数或请求体");
        }
        return ApiResponse.of(service.list(principal, request.getRemoteAddr()), MDC.get(RequestIdFilter.MDC_KEY));
    }
}
