package com.freecontent.community.user.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.user.dto.UserPage;
import com.freecontent.community.user.service.MyFollowingService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "关注")
public class MyFollowingController {
    private static final Set<String> PARAMETERS = Set.of("cursor", "limit");
    private final MyFollowingService service;

    public MyFollowingController(MyFollowingService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/v1/me/following", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listMyFollowing", summary = "查询本人关注列表",
            description = "按关注关系的创建时间和ID倒序，返回被关注用户的公开资料。",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses({ErrorCode.CURSOR_INVALID, ErrorCode.CURSOR_EXPIRED})
    public ApiResponse<UserPage> list(
            @Parameter(schema = @Schema(maxLength = 4096)) @RequestParam(required = false) String cursor,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "50", defaultValue = "20"))
            @RequestParam(required = false) String limit,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal actor,
            HttpServletRequest request) {
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "关注列表不接受请求体");
        }
        for (var parameter : request.getParameterMap().entrySet()) {
            if (!PARAMETERS.contains(parameter.getKey()) || parameter.getValue().length != 1) {
                throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "查询参数未定义或重复", null, null,
                        List.of(ProblemDetailResponse.Violation.query(parameter.getKey(), "参数未定义或重复")));
            }
        }
        var page = service.list(cursor, limit, actor, request.getRemoteAddr());
        return ApiResponse.of(page, MDC.get(RequestIdFilter.MDC_KEY));
    }
}
