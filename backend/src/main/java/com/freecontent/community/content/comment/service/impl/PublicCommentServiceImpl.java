package com.freecontent.community.content.comment.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.common.pagination.CursorPageParameters;
import com.freecontent.community.content.comment.cursor.ContentCommentsCursor;
import com.freecontent.community.content.comment.cursor.ContentCommentsCursor.Kind;
import com.freecontent.community.content.comment.dto.CommentPage;
import com.freecontent.community.content.comment.dto.CommentView;
import com.freecontent.community.content.comment.ratelimit.PublicCommentRateLimiter;
import com.freecontent.community.content.comment.service.PublicCommentService;
import com.freecontent.community.content.comment.service.transaction.PublicCommentTransactionService;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class PublicCommentServiceImpl implements PublicCommentService {
    private final PublicCommentTransactionService transactions;
    private final PublicCommentRateLimiter limiter;
    private final ContentCommentsCursor cursors;

    public PublicCommentServiceImpl(PublicCommentTransactionService transactions, PublicCommentRateLimiter limiter,
                                    ContentCommentsCursor cursors) {
        this.transactions = transactions;
        this.limiter = limiter;
        this.cursors = cursors;
    }

    @Override
    public CommentView read(String commentId, SessionPrincipal viewer, String sourceIp) {
        requireId(commentId, "commentId", "评论ID格式不正确");
        limiter.acquire(viewer == null ? null : viewer.user().id(), sourceIp);
        try {
            return transactions.read(commentId);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "公开评论查询数据服务暂不可用");
        }
    }

    @Override
    public CommentPage list(String contentId, String cursor, String limit, SessionPrincipal viewer, String sourceIp) {
        requireId(contentId, "contentId", "内容ID格式不正确");
        return listPage(contentId, cursor, limit, viewer, sourceIp, Kind.ROOTS);
    }

    @Override
    public CommentPage listReplies(String rootCommentId, String cursor, String limit, SessionPrincipal viewer, String sourceIp) {
        requireId(rootCommentId, "commentId", "评论ID格式不正确");
        return listPage(rootCommentId, cursor, limit, viewer, sourceIp, Kind.REPLIES);
    }

    private CommentPage listPage(String resourceId, String cursor, String limit, SessionPrincipal viewer,
                                 String sourceIp, Kind kind) {
        int size = CursorPageParameters.pageSize(limit);
        CursorPageParameters.requireCursorLength(cursor);
        String viewerId = viewer == null ? null : viewer.user().id();
        var query = cursors.open(cursor, resourceId, viewerId, size, kind);
        limiter.acquire(viewerId, sourceIp);
        try {
            return transactions.list(query);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "公开评论查询数据服务暂不可用");
        }
    }

    private static void requireId(String id, String field, String message) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, message, null, null,
                    List.of(new ProblemDetailResponse.Violation("path", field, "仅允许1到64位字母、数字、下划线及短横线")));
        }
    }
}
