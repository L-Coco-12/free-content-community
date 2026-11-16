package com.freecontent.community.content.interaction.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.interaction.service.ContentBookmarkService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/me/bookmarks")
@Tag(name="互动")
public class ContentBookmarkController {
    private final ContentBookmarkService service;
    public ContentBookmarkController(ContentBookmarkService service){this.service=service;}
    @PutMapping("/{contentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId="bookmarkContent",summary="收藏内容",security=@SecurityRequirement(name="bearerAuth"))
    @ApiErrorResponses(RESOURCE_NOT_FOUND)
    public ResponseEntity<Void> bookmark(
            @Parameter(schema=@Schema(minLength=1,maxLength=64,pattern="^[A-Za-z0-9_-]+$")) @PathVariable String contentId,
            @Parameter(hidden=true) @AuthenticationPrincipal SessionPrincipal actor,HttpServletRequest request){
        requireNoInput(request);
        service.bookmark(contentId,actor,request.getRemoteAddr());return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/{contentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId="unbookmarkContent",summary="取消本人收藏",security=@SecurityRequirement(name="bearerAuth"))
    @ApiErrorResponses
    public ResponseEntity<Void> unbookmark(
            @Parameter(schema=@Schema(minLength=1,maxLength=64,pattern="^[A-Za-z0-9_-]+$")) @PathVariable String contentId,
            @Parameter(hidden=true) @AuthenticationPrincipal SessionPrincipal actor,HttpServletRequest request){
        requireNoInput(request);
        service.unbookmark(contentId,actor,request.getRemoteAddr());return ResponseEntity.noContent().build();
    }
    private void requireNoInput(HttpServletRequest request){
        if(!request.getParameterMap().isEmpty() || request.getContentLengthLong()>0 || request.getHeader("Transfer-Encoding")!=null)
            throw new BusinessException(MALFORMED_REQUEST,"收藏接口不接受查询参数或请求体");
    }
}
