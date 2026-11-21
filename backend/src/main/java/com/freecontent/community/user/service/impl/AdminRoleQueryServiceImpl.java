package com.freecontent.community.user.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.dto.RoleList;
import com.freecontent.community.user.ratelimit.AdminRoleQueryRateLimiter;
import com.freecontent.community.user.service.AdminRoleQueryService;
import com.freecontent.community.user.service.transaction.AdminRoleQueryTransactionService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class AdminRoleQueryServiceImpl implements AdminRoleQueryService {
    private final AdminRoleQueryTransactionService transactions;
    private final AdminRoleQueryRateLimiter limiter;

    public AdminRoleQueryServiceImpl(AdminRoleQueryTransactionService transactions, AdminRoleQueryRateLimiter limiter) {
        this.transactions = transactions;
        this.limiter = limiter;
    }

    @Override
    public RoleList list(SessionPrincipal principal, String sourceIp) {
        if (principal == null || principal.user() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        limiter.acquire(principal.user().id(), sourceIp);
        try {
            return transactions.list(principal);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "角色查询数据服务暂不可用");
        }
    }
}
