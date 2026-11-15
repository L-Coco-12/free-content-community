package com.freecontent.community.content.interaction.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.interaction.ratelimit.ContentBookmarkRateLimiter;
import com.freecontent.community.content.interaction.service.ContentBookmarkService;
import com.freecontent.community.content.interaction.service.transaction.ContentBookmarkTransactionService;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class ContentBookmarkServiceImpl implements ContentBookmarkService {
    private final ContentBookmarkTransactionService transactions;
    private final ContentBookmarkRateLimiter limiter;
    public ContentBookmarkServiceImpl(ContentBookmarkTransactionService transactions,ContentBookmarkRateLimiter limiter){this.transactions=transactions;this.limiter=limiter;}
    @Override public void bookmark(String contentId,SessionPrincipal actor,String sourceIp){
        if(actor==null || actor.user()==null)throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if(contentId==null || !contentId.matches("[A-Za-z0-9_-]{1,64}"))throw new BusinessException(ErrorCode.VALIDATION_ERROR,"内容ID格式不正确",null,null,
                List.of(new ProblemDetailResponse.Violation("path","contentId","仅允许1到64位字母、数字、下划线及短横线")));
        limiter.acquire(actor.user().id(),sourceIp);
        try{transactions.bookmark(contentId,actor);}
        catch(DataAccessException | TransactionException ex){throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"收藏数据服务暂不可用");}
    }
}
