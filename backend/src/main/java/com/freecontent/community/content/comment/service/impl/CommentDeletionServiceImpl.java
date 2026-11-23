package com.freecontent.community.content.comment.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.content.comment.ratelimit.CommentWriteRateLimiter;
import com.freecontent.community.content.comment.service.CommentDeletionService;
import com.freecontent.community.content.comment.service.transaction.CommentDeletionTransactionService;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class CommentDeletionServiceImpl implements CommentDeletionService {
    private final CommentWriteRateLimiter limiter;
    private final CommentDeletionTransactionService transactions;

    public CommentDeletionServiceImpl(CommentWriteRateLimiter limiter, CommentDeletionTransactionService transactions) {
        this.limiter = limiter;
        this.transactions = transactions;
    }

    @Override
    public void delete(String commentId, SessionPrincipal actor, String sourceIp) {
        if (actor == null || actor.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (commentId == null || !commentId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "评论ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "commentId", "仅允许1到64位字母、数字、下划线及短横线")));
        }
        limiter.acquire(actor.user().id(), sourceIp);
        try {
            transactions.delete(commentId, actor);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "评论删除数据服务暂不可用");
        }
    }
}
