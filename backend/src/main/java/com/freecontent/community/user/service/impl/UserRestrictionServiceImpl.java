package com.freecontent.community.user.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.user.dto.AdminUser;
import com.freecontent.community.user.dto.RestrictionRequest;
import com.freecontent.community.user.ratelimit.UserRestrictionRateLimiter;
import com.freecontent.community.user.service.UserRestrictionService;
import com.freecontent.community.user.service.transaction.UserRestrictionTransactionService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class UserRestrictionServiceImpl implements UserRestrictionService {
    private static final Logger log = LoggerFactory.getLogger(UserRestrictionServiceImpl.class);
    private final UserRestrictionTransactionService transactions;
    private final UserRestrictionRateLimiter limiter;
    private final RedisSessionStore sessions;

    public UserRestrictionServiceImpl(UserRestrictionTransactionService transactions,
                                       UserRestrictionRateLimiter limiter, RedisSessionStore sessions) {
        this.transactions = transactions;
        this.limiter = limiter;
        this.sessions = sessions;
    }

    @Override
    public AdminUser restrict(String userId, RestrictionRequest request, SessionPrincipal principal,
                              String sourceIp, String requestId) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (userId == null || !userId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "用户ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "userId", "仅允许1到64位字母、数字、下划线及短横线")));
        }
        limiter.acquire(principal.user().id(), sourceIp);
        UserRestrictionTransactionService.Result result;
        try {
            result = transactions.restrict(userId, request, principal, requestId);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "账号限制数据服务暂不可用");
        }

        if (result.revokeBeforeAuthVersion() != null) {
            try {
                sessions.revokeBeforeVersion(userId, result.revokeBeforeAuthVersion());
            } catch (BusinessException ex) {
                if (ex.errorCode() != ErrorCode.DEPENDENCY_UNAVAILABLE) throw ex;
                // The committed auth_version already rejects all older sessions.
                log.warn("Account disabled; Redis session cleanup failed, userId={}", userId);
            }
        }
        return result.user();
    }
}
