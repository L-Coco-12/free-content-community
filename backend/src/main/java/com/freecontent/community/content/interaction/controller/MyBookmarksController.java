package com.freecontent.community.content.interaction.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.browse.dto.ContentPageView;
import com.freecontent.community.content.interaction.service.MyBookmarksService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/me/bookmarks")
@Tag(name="互动")
public class MyBookmarksController {
    private final MyBookmarksService service;
    public MyBookmarksController(MyBookmarksService service){this.service=service;}
    @GetMapping(produces="application/json")
    @Operation(operationId="listMyBookmarks",summary="查询本人收藏中当前可见的内容",security=@SecurityRequirement(name="bearerAuth"))
    @ApiErrorResponses({CURSOR_INVALID,CURSOR_EXPIRED})
    public ApiResponse<ContentPageView> list(
            @Parameter(schema=@Schema(maxLength=4096)) @RequestParam(required=false) String cursor,
            @Parameter(schema=@Schema(type="integer",minimum="1",maximum="50",defaultValue="20")) @RequestParam(required=false) String limit,
            @Parameter(hidden=true) @AuthenticationPrincipal SessionPrincipal actor,HttpServletRequest request){
        if(request.getContentLengthLong()>0 || request.getHeader("Transfer-Encoding")!=null)throw new BusinessException(MALFORMED_REQUEST,"收藏列表查询不接受请求体");
        for(var entry:request.getParameterMap().entrySet()){
            if(!Set.of("cursor","limit").contains(entry.getKey()) || entry.getValue().length!=1)throw new BusinessException(MALFORMED_REQUEST,
                    "查询参数未定义或重复",null,null,List.of(ProblemDetailResponse.Violation.query(entry.getKey(),"参数未定义或重复")));
        }
        return ApiResponse.of(service.list(cursor,limit,actor,request.getRemoteAddr()),MDC.get(RequestIdFilter.MDC_KEY));
    }
}
