package com.freecontent.community.user.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.dto.SessionListView;
import com.freecontent.community.user.service.MySessionsService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/sessions")
@Tag(name="账号")
public class MySessionsController {
    private final MySessionsService service;
    public MySessionsController(MySessionsService service){this.service=service;}
    @GetMapping(produces="application/json")
    @Operation(operationId="listMySessions",summary="查询本人有效登录会话",security=@SecurityRequirement(name="bearerAuth"))
    @ApiErrorResponses
    public ApiResponse<SessionListView> list(@Parameter(hidden=true) @AuthenticationPrincipal SessionPrincipal actor,HttpServletRequest request){
        if(!request.getParameterMap().isEmpty() || request.getContentLengthLong()>0 || request.getHeader("Transfer-Encoding")!=null)
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST,"本人会话查询不接受查询参数或请求体");
        return ApiResponse.of(service.list(actor,request.getRemoteAddr()),MDC.get(RequestIdFilter.MDC_KEY));
    }
}
