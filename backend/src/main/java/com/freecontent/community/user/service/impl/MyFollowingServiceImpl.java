package com.freecontent.community.user.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.pagination.CursorPageParameters;
import com.freecontent.community.user.cursor.MyFollowingCursor;
import com.freecontent.community.user.dto.UserPage;
import com.freecontent.community.user.ratelimit.UserFollowQueryRateLimiter;
import com.freecontent.community.user.service.MyFollowingService;
import com.freecontent.community.user.service.transaction.MyFollowingTransactionService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class MyFollowingServiceImpl implements MyFollowingService {
    private final MyFollowingCursor cursors;
    private final UserFollowQueryRateLimiter limiter;
    private final MyFollowingTransactionService transactions;

    public MyFollowingServiceImpl(MyFollowingCursor cursors, UserFollowQueryRateLimiter limiter,
                                  MyFollowingTransactionService transactions) {
        this.cursors = cursors;
        this.limiter = limiter;
        this.transactions = transactions;
    }

    @Override
    public UserPage list(String cursor, String limit, SessionPrincipal actor, String sourceIp) {
        if (actor == null || actor.user() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        int size = CursorPageParameters.pageSize(limit);
        CursorPageParameters.requireCursorLength(cursor);
        var query = cursors.open(cursor, actor.user().id(), size);
        limiter.acquire(query.userId(), sourceIp);
        try {
            return transactions.list(query, actor);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "关注列表查询暂不可用");
        }
    }
}
