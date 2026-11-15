package com.freecontent.community.content.interaction.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.interaction.ratelimit.ContentLikeRateLimiter;
import com.freecontent.community.content.interaction.service.ContentLikeService;
import com.freecontent.community.content.interaction.service.transaction.ContentLikeTransactionService;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class ContentLikeServiceImpl implements ContentLikeService {
    private final ContentLikeTransactionService transactions;
    private final ContentLikeRateLimiter limiter;
    public ContentLikeServiceImpl(ContentLikeTransactionService transactions,ContentLikeRateLimiter limiter){this.transactions=transactions;this.limiter=limiter;}
    @Override public void like(String contentId,SessionPrincipal actor,String sourceIp) {
        validateAndAcquire(contentId,actor,sourceIp);
        try{transactions.like(contentId,actor);}
        catch(DataAccessException | TransactionException ex){throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"点赞数据服务暂不可用");}
    }
    @Override public void unlike(String contentId,SessionPrincipal actor,String sourceIp) {
        validateAndAcquire(contentId,actor,sourceIp);
        try{transactions.unlike(contentId,actor);}
        catch(DataAccessException | TransactionException ex){throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"取消点赞数据服务暂不可用");}
    }
    private void validateAndAcquire(String contentId,SessionPrincipal actor,String sourceIp) {
        if(actor==null || actor.user()==null)throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if(contentId==null || !contentId.matches("[A-Za-z0-9_-]{1,64}"))throw new BusinessException(ErrorCode.VALIDATION_ERROR,"内容ID格式不正确",null,null,
                List.of(new ProblemDetailResponse.Violation("path","contentId","仅允许1到64位字母、数字、下划线及短横线")));
        limiter.acquire(actor.user().id(),sourceIp);
    }
}
