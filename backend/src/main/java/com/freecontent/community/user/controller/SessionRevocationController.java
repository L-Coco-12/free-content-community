package com.freecontent.community.user.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.service.SessionRevocationService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/sessions")
@Tag(name="账号")
public class SessionRevocationController {
    private final SessionRevocationService service;
    public SessionRevocationController(SessionRevocationService service){this.service=service;}
    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId="revokeMySession",summary="撤销本人指定会话",security=@SecurityRequirement(name="bearerAuth"))
    @ApiErrorResponses
    public ResponseEntity<Void> revoke(
            @Parameter(schema=@Schema(minLength=1,maxLength=64,pattern="^[A-Za-z0-9_-]+$")) @PathVariable String sessionId,
            @Parameter(hidden=true) @AuthenticationPrincipal SessionPrincipal actor,HttpServletRequest request){
        if(!request.getParameterMap().isEmpty() || request.getContentLengthLong()>0 || request.getHeader("Transfer-Encoding")!=null)
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST,"本人会话撤销不接受查询参数或请求体");
        service.revoke(sessionId,actor,request.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
