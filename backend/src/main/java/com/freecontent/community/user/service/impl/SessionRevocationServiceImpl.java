package com.freecontent.community.user.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.ratelimit.SessionRevocationRateLimiter;
import com.freecontent.community.user.service.SessionRevocationService;
import com.freecontent.community.user.service.transaction.SessionRevocationTransactionService;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class SessionRevocationServiceImpl implements SessionRevocationService {
    private final SessionRevocationTransactionService transactions;
    private final SessionRevocationRateLimiter limiter;
    public SessionRevocationServiceImpl(SessionRevocationTransactionService transactions,SessionRevocationRateLimiter limiter){this.transactions=transactions;this.limiter=limiter;}
    @Override public void revoke(String sessionId,SessionPrincipal actor,String sourceIp){
        if(actor==null || actor.user()==null)throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if(sessionId==null || !sessionId.matches("[A-Za-z0-9_-]{1,64}"))throw new BusinessException(ErrorCode.VALIDATION_ERROR,"会话ID格式不正确",null,null,
                List.of(new ProblemDetailResponse.Violation("path","sessionId","仅允许1到64位字母、数字、下划线及短横线")));
        limiter.acquire(actor.user().id(),sourceIp);
        try{transactions.revoke(sessionId,actor);}
        catch(DataAccessException | TransactionException ex){throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"本人会话撤销数据服务暂不可用");}
    }
}
