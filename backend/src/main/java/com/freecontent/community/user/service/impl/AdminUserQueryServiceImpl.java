package com.freecontent.community.user.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.user.dto.AdminUser;
import com.freecontent.community.user.dto.AdminUserPage;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.ratelimit.AdminUserQueryRateLimiter;
import com.freecontent.community.user.service.AdminUserQueryService;
import com.freecontent.community.user.service.transaction.AdminUserQueryTransactionService;
import java.math.BigInteger;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class AdminUserQueryServiceImpl implements AdminUserQueryService {
    private final AdminUserQueryTransactionService transactions;
    private final AdminUserQueryRateLimiter limiter;

    public AdminUserQueryServiceImpl(AdminUserQueryTransactionService transactions, AdminUserQueryRateLimiter limiter) {
        this.transactions = transactions;
        this.limiter = limiter;
    }

    @Override
    public AdminUser get(String userId, SessionPrincipal principal, String sourceIp) {
        requirePrincipal(principal);
        if (userId == null || !userId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "用户ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "userId", "仅允许1到64位字母、数字、下划线及短横线")));
        }
        limiter.acquire(principal.user().id(), sourceIp);
        try {
            return transactions.get(userId, principal);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "后台用户查询数据服务暂不可用");
        }
    }

    @Override
    public AdminUserPage list(String page, String pageSize, String userId, String status,
                              SessionPrincipal principal, String sourceIp) {
        requirePrincipal(principal);
        BigInteger number = pagination(page, "page", 1, null);
        int size = pagination(pageSize, "pageSize", 20, 100).intValueExact();
        if (userId != null && !userId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw invalid(ErrorCode.VALIDATION_ERROR, "userId", "仅允许1到64位字母、数字、下划线及短横线");
        }
        UserAccount.Status state = parseStatus(status);

        limiter.acquire(principal.user().id(), sourceIp);
        try {
            return transactions.list(number, size, userId, state, principal);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "后台用户查询数据服务暂不可用");
        }
    }

    private void requirePrincipal(SessionPrincipal principal) {
        if (principal == null || principal.user() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }

    private BigInteger pagination(String raw, String field, int defaultValue, Integer maximum) {
        if (raw == null) return BigInteger.valueOf(defaultValue);
        if (!raw.matches("-?[0-9]+")) {
            throw invalid(ErrorCode.MALFORMED_REQUEST, field, "必须为整数");
        }
        BigInteger value = new BigInteger(raw);
        if (value.signum() <= 0 || maximum != null && value.compareTo(BigInteger.valueOf(maximum)) > 0) {
            throw invalid(ErrorCode.VALIDATION_ERROR, field, maximum == null ? "必须大于等于1" : "必须在1到" + maximum + "之间");
        }
        return value;
    }

    private UserAccount.Status parseStatus(String raw) {
        if (raw == null) return null;
        try {
            return UserAccount.Status.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw invalid(ErrorCode.MALFORMED_REQUEST, "status", "不支持的账号状态");
        }
    }

    private BusinessException invalid(ErrorCode code, String field, String reason) {
        return new BusinessException(code, "查询参数不合法", null, null,
                List.of(ProblemDetailResponse.Violation.query(field, reason)));
    }
}
