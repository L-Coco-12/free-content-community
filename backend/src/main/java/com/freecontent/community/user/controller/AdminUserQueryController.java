package com.freecontent.community.user.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.user.dto.AdminUser;
import com.freecontent.community.user.dto.AdminUserPage;
import com.freecontent.community.user.service.AdminUserQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "用户管理")
public class AdminUserQueryController {
    private static final Set<String> PARAMETERS = Set.of("page", "pageSize", "userId", "status");
    private final AdminUserQueryService service;

    public AdminUserQueryController(AdminUserQueryService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/v1/admin/users/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getAdminUser", summary = "读取用户管理信息",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses(ErrorCode.RESOURCE_NOT_FOUND)
    public ApiResponse<AdminUser> getAdminUser(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @PathVariable String userId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest request) {
        if (request.getQueryString() != null || request.getContentLengthLong() > 0
                || request.getHeader("Transfer-Encoding") != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "后台用户详情不接受查询参数或请求体");
        }
        var data = service.get(userId, principal, request.getRemoteAddr());
        return ApiResponse.of(data, MDC.get(RequestIdFilter.MDC_KEY));
    }

    @GetMapping(value = "/api/v1/admin/users", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listAdminUsers", summary = "查询脱敏用户列表",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses
    public ApiResponse<AdminUserPage> listAdminUsers(
            @Parameter(schema = @Schema(type = "integer", minimum = "1", defaultValue = "1"))
            @RequestParam(required = false) String page,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
            @RequestParam(required = false) String pageSize,
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @RequestParam(required = false) String userId,
            @Parameter(schema = @Schema(allowableValues = {"ACTIVE", "WRITE_BLOCKED", "DISABLED"}))
            @RequestParam(required = false) String status,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest request) {
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "后台用户列表不接受请求体");
        }
        for (var entry : request.getParameterMap().entrySet()) {
            if (!PARAMETERS.contains(entry.getKey()) || entry.getValue().length != 1) {
                throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "查询参数未定义或重复", null, null,
                        List.of(ProblemDetailResponse.Violation.query(entry.getKey(), "参数未定义或重复")));
            }
        }
        var data = service.list(page, pageSize, userId, status, principal, request.getRemoteAddr());
        return ApiResponse.of(data, MDC.get(RequestIdFilter.MDC_KEY));
    }
}
