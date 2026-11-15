package com.freecontent.community.user.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.service.PublicAvatarService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name="公开资料")
public class PublicAvatarController {
    private final PublicAvatarService service;
    public PublicAvatarController(PublicAvatarService service){this.service=service;}
    @GetMapping("/{userId}/avatar")
    @ResponseStatus(HttpStatus.FOUND)
    @Operation(operationId="getPublicUserAvatar",summary="读取当前公开头像",description="允许游客；携带Token时必须验证。仅当前绑定头像可读取，302跳转到短期READY对象地址。")
    @ApiErrorResponses(value={MALFORMED_REQUEST,AUTHENTICATION_REQUIRED,RESOURCE_NOT_FOUND,MEDIA_NOT_READY,
            VALIDATION_ERROR,RATE_LIMITED,INTERNAL_ERROR,DEPENDENCY_UNAVAILABLE},includeCommon=false)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="302",description="当前头像短期读取地址",content=@Content,
            headers={@Header(name="Location",schema=@Schema(type="string",format="uri")),
                    @Header(name="Cache-Control",schema=@Schema(type="string",allowableValues="no-store")),
                    @Header(name="X-Request-Id",schema=@Schema(type="string"))})
    public ResponseEntity<Void> getAvatar(
            @Parameter(schema=@Schema(minLength=1,maxLength=64,pattern="^[A-Za-z0-9_-]+$")) @PathVariable String userId,
            @Parameter(schema=@Schema(minLength=1,maxLength=64,pattern="^[A-Za-z0-9_-]+$")) @RequestParam String assetId,
            @Parameter(hidden=true) @AuthenticationPrincipal SessionPrincipal viewer,HttpServletRequest request) {
        var invalid=request.getParameterMap().entrySet().stream().filter(entry->!entry.getKey().equals("assetId") || entry.getValue().length!=1)
                .map(entry->ProblemDetailResponse.Violation.query(entry.getKey(),"未定义或重复的查询参数")).toList();
        if(!invalid.isEmpty())throw new BusinessException(MALFORMED_REQUEST,"头像读取查询参数不合法",null,null,invalid);
        var access=service.getAvatar(userId,assetId,viewer,request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(access.url())).cacheControl(CacheControl.noStore()).build();
    }
}
