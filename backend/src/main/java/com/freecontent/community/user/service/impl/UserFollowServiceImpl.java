package com.freecontent.community.user.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.user.ratelimit.UserFollowRateLimiter;
import com.freecontent.community.user.service.UserFollowService;
import com.freecontent.community.user.service.transaction.UserFollowTransactionService;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class UserFollowServiceImpl implements UserFollowService {
    private final UserFollowTransactionService transactions;
    private final UserFollowRateLimiter limiter;

    public UserFollowServiceImpl(UserFollowTransactionService transactions, UserFollowRateLimiter limiter) {
        this.transactions = transactions;
        this.limiter = limiter;
    }

    @Override
    public void follow(String userId, SessionPrincipal actor, String sourceIp) {
        validateAndAcquire(userId, actor, sourceIp);
        try {
            transactions.follow(userId, actor);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "关注服务暂不可用");
        }
    }

    @Override
    public void unfollow(String userId, SessionPrincipal actor, String sourceIp) {
        validateAndAcquire(userId, actor, sourceIp);
        try {
            transactions.unfollow(userId, actor);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "关注服务暂不可用");
        }
    }

    private void validateAndAcquire(String userId, SessionPrincipal actor, String sourceIp) {
        if (actor == null || actor.user() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (userId == null || !userId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "用户ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "userId", "仅允许1到64位字母、数字、下划线及短横线")));
        }
        limiter.acquire(actor.user().id(), sourceIp);
    }
}
