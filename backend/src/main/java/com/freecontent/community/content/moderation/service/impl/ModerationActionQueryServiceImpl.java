package com.freecontent.community.content.moderation.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.content.moderation.dto.ModerationAction;
import com.freecontent.community.content.moderation.ratelimit.ModerationActionQueryRateLimiter;
import com.freecontent.community.content.moderation.service.ModerationActionQueryService;
import com.freecontent.community.content.moderation.service.transaction.ModerationActionQueryTransactionService;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class ModerationActionQueryServiceImpl implements ModerationActionQueryService {
    private final ModerationActionQueryTransactionService transactions;
    private final ModerationActionQueryRateLimiter limiter;

    public ModerationActionQueryServiceImpl(ModerationActionQueryTransactionService transactions,
                                             ModerationActionQueryRateLimiter limiter) {
        this.transactions = transactions;
        this.limiter = limiter;
    }

    @Override
    public ModerationAction get(String actionId, SessionPrincipal actor, String sourceIp) {
        if (actor == null || actor.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (actionId == null || !actionId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "处置ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "actionId", "仅允许1到64位字母、数字、下划线及短横线")));
        }
        limiter.acquire(actor.user().id(), sourceIp);
        try {
            return transactions.read(actionId, actor);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "运营处置数据服务暂不可用");
        }
    }
}
