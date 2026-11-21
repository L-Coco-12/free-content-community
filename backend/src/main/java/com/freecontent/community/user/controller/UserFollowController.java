package com.freecontent.community.user.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.service.UserFollowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/following")
@Tag(name = "关注")
public class UserFollowController {
    private final UserFollowService service;

    public UserFollowController(UserFollowService service) {
        this.service = service;
    }

    @PutMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "followUser", summary = "关注用户",
            description = "不能关注自己。首次关注同时保存站内通知，重复关注返回204。",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses(ErrorCode.RESOURCE_NOT_FOUND)
    public void follow(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @PathVariable String userId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal actor,
            HttpServletRequest request) {
        requireNoInput(request);
        service.follow(userId, actor, request.getRemoteAddr());
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "unfollowUser", summary = "取消本人关注",
            description = "仅删除本人对目标用户的关注关系。重复取消返回204。",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses
    public void unfollow(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @PathVariable String userId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal actor,
            HttpServletRequest request) {
        requireNoInput(request);
        service.unfollow(userId, actor, request.getRemoteAddr());
    }

    private void requireNoInput(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty() || request.getContentLengthLong() > 0
                || request.getHeader("Transfer-Encoding") != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "关注接口不接受查询参数或请求体");
        }
    }
}
