package com.freecontent.community.user.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.dto.SessionListView;
import com.freecontent.community.user.ratelimit.SessionQueryRateLimiter;
import com.freecontent.community.user.service.MySessionsService;
import com.freecontent.community.user.service.transaction.MySessionsTransactionService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class MySessionsServiceImpl implements MySessionsService {
    private final MySessionsTransactionService transactions;
    private final SessionQueryRateLimiter limiter;
    public MySessionsServiceImpl(MySessionsTransactionService transactions,SessionQueryRateLimiter limiter){this.transactions=transactions;this.limiter=limiter;}
    @Override public SessionListView list(SessionPrincipal actor,String sourceIp){
        if(actor==null || actor.user()==null)throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        limiter.acquire(actor.user().id(),sourceIp);
        try{return transactions.list(actor);}
        catch(DataAccessException | TransactionException ex){throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"本人会话查询数据服务暂不可用");}
    }
}
