package com.freecontent.community.notification.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.notification.dto.BatchReadRequest;
import com.freecontent.community.notification.dto.BatchReadResult;
import com.freecontent.community.notification.dto.NotificationPage;
import com.freecontent.community.notification.dto.UnreadCount;
import com.freecontent.community.notification.service.MyNotificationsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@Tag(name = "通知")
public class MyNotificationsController {
    private static final Set<String> PARAMETERS = Set.of("cursor", "limit", "readState");
    private final MyNotificationsService service;

    public MyNotificationsController(MyNotificationsService service) {
        this.service = service;
    }

    @PostMapping(value = "/api/v1/me/notifications/read-batches",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "readNotificationBatch", summary = "批量标记本人通知已读",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses({ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.PAYLOAD_TOO_LARGE, ErrorCode.UNSUPPORTED_MEDIA_TYPE})
    public ApiResponse<BatchReadResult> markBatchRead(@Valid @RequestBody BatchReadRequest input,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "批量标记已读不接受查询参数");
        }
        var result = service.markBatchRead(input.notificationIds(), principal, request.getRemoteAddr());
        return ApiResponse.of(result, MDC.get(RequestIdFilter.MDC_KEY));
    }

    @PutMapping("/api/v1/me/notifications/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "readNotification", summary = "标记本人单条通知已读",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses(ErrorCode.RESOURCE_NOT_FOUND)
    public ResponseEntity<Void> markRead(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @PathVariable String notificationId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest request) {
        if (request.getQueryString() != null || request.getContentLengthLong() > 0
                || request.getHeader("Transfer-Encoding") != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "标记通知已读不接受查询参数或请求体");
        }
        service.markRead(notificationId, principal, request.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/api/v1/me/notifications/unread-count", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getUnreadNotificationCount", summary = "查询本人未读通知数",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses
    public ApiResponse<UnreadCount> unreadCount(
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest request) {
        if (request.getQueryString() != null || request.getContentLengthLong() > 0
                || request.getHeader("Transfer-Encoding") != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "未读通知数查询不接受查询参数或请求体");
        }
        var count = service.unreadCount(principal, request.getRemoteAddr());
        return ApiResponse.of(count, MDC.get(RequestIdFilter.MDC_KEY));
    }

    @GetMapping(value = "/api/v1/me/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listMyNotifications", summary = "查询本人通知",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses({ErrorCode.CURSOR_INVALID, ErrorCode.CURSOR_EXPIRED})
    public ApiResponse<NotificationPage> list(
            @Parameter(schema = @Schema(maxLength = 4096)) @RequestParam(required = false) String cursor,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "50", defaultValue = "20"))
            @RequestParam(required = false) String limit,
            @Parameter(schema = @Schema(allowableValues = {"ALL", "UNREAD", "READ"}, defaultValue = "ALL"))
            @RequestParam(required = false) String readState,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest request) {
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "通知列表不接受请求体");
        }
        for (var entry : request.getParameterMap().entrySet()) {
            if (!PARAMETERS.contains(entry.getKey()) || entry.getValue().length != 1) {
                throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "查询参数未定义或重复", null, null,
                        List.of(ProblemDetailResponse.Violation.query(entry.getKey(), "参数未定义或重复")));
            }
        }
        var page = service.list(cursor, limit, readState, principal, request.getRemoteAddr());
        return ApiResponse.of(page, MDC.get(RequestIdFilter.MDC_KEY));
    }
}
