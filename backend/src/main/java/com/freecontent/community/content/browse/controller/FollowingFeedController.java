package com.freecontent.community.content.browse.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.content.browse.dto.ContentPageView;
import com.freecontent.community.content.browse.service.FollowingFeedService;
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
@Tag(name = "信息分发")
public class FollowingFeedController {
    private static final Set<String> PARAMETERS = Set.of("cursor", "limit");
    private final FollowingFeedService service;

    public FollowingFeedController(FollowingFeedService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/v1/feeds/following", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getFollowingFeed", summary = "浏览关注作者的信息流",
            description = "查询当前关注作者的公开内容，按首次发布时间和内容ID倒序。每页重新查询关注关系。",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses({ErrorCode.CURSOR_INVALID, ErrorCode.CURSOR_EXPIRED})
    public ApiResponse<ContentPageView> list(
            @Parameter(schema = @Schema(maxLength = 4096)) @RequestParam(required = false) String cursor,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "50", defaultValue = "20"))
            @RequestParam(required = false) String limit,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal actor,
            HttpServletRequest request) {
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "关注流不接受请求体");
        }
        for (var parameter : request.getParameterMap().entrySet()) {
            if (!PARAMETERS.contains(parameter.getKey()) || parameter.getValue().length != 1) {
                throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "查询参数未定义或重复", null, null,
                        List.of(ProblemDetailResponse.Violation.query(parameter.getKey(), "参数未定义或重复")));
            }
        }
        return ApiResponse.of(service.list(cursor, limit, actor, request.getRemoteAddr()), MDC.get(RequestIdFilter.MDC_KEY));
    }
}
