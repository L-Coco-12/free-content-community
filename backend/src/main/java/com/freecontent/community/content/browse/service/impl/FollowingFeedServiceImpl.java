package com.freecontent.community.content.browse.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.pagination.CursorPageParameters;
import com.freecontent.community.content.browse.cursor.PublicContentCursor;
import com.freecontent.community.content.browse.dto.ContentPageView;
import com.freecontent.community.content.browse.ratelimit.FollowingFeedRateLimiter;
import com.freecontent.community.content.browse.service.FollowingFeedService;
import com.freecontent.community.content.browse.service.transaction.FollowingFeedTransactionService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class FollowingFeedServiceImpl implements FollowingFeedService {
    private final PublicContentCursor cursors;
    private final FollowingFeedRateLimiter limiter;
    private final FollowingFeedTransactionService transactions;

    public FollowingFeedServiceImpl(PublicContentCursor cursors, FollowingFeedRateLimiter limiter,
                                    FollowingFeedTransactionService transactions) {
        this.cursors = cursors;
        this.limiter = limiter;
        this.transactions = transactions;
    }

    @Override
    public ContentPageView list(String cursor, String limit, SessionPrincipal actor, String sourceIp) {
        if (actor == null || actor.user() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        int size = CursorPageParameters.pageSize(limit);
        CursorPageParameters.requireCursorLength(cursor);
        String viewerId = actor.user().id();
        var query = cursors.openForFollowing(cursor, size, viewerId);
        limiter.acquire(viewerId, sourceIp);
        try {
            return transactions.list(query, actor);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "关注流查询暂不可用");
        }
    }
}
