package com.freecontent.community.content.comment.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.common.pagination.OffsetPageParameters;
import com.freecontent.community.content.comment.dto.AdminComment;
import com.freecontent.community.content.comment.dto.AdminCommentPage;
import com.freecontent.community.content.comment.ratelimit.AdminCommentQueryRateLimiter;
import com.freecontent.community.content.comment.service.AdminCommentQueryService;
import com.freecontent.community.content.comment.service.transaction.AdminCommentQueryTransactionService;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class AdminCommentQueryServiceImpl implements AdminCommentQueryService {
    private static final Set<String> STATES = Set.of("VISIBLE", "HIDDEN", "DELETED");
    private final AdminCommentQueryTransactionService transactions;
    private final AdminCommentQueryRateLimiter limiter;

    public AdminCommentQueryServiceImpl(AdminCommentQueryTransactionService transactions, AdminCommentQueryRateLimiter limiter) {
        this.transactions = transactions;
        this.limiter = limiter;
    }

    @Override
    public AdminCommentPage list(String page, String pageSize, String contentId, String authorId, String state,
                                 SessionPrincipal actor, String sourceIp) {
        requireActor(actor);
        var number = OffsetPageParameters.page(page);
        int size = OffsetPageParameters.pageSize(pageSize);
        validateOptionalId(contentId, "contentId");
        validateOptionalId(authorId, "authorId");
        if (state != null && !STATES.contains(state)) {
            throw invalid(ErrorCode.MALFORMED_REQUEST, "state", "不支持的评论状态");
        }

        limiter.acquire(actor.user().id(), sourceIp);
        try {
            return transactions.list(number, size, contentId, authorId, state, actor);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "运营评论查询数据服务暂不可用");
        }
    }

    @Override
    public AdminComment get(String commentId, SessionPrincipal actor, String sourceIp) {
        requireActor(actor);
        if (commentId == null || !commentId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "评论ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "commentId", "仅允许1到64位字母、数字、下划线及短横线")));
        }
        limiter.acquire(actor.user().id(), sourceIp);
        try {
            return transactions.read(commentId, actor);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "运营评论详情数据服务暂不可用");
        }
    }

    private static void requireActor(SessionPrincipal actor) {
        if (actor == null || actor.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
    }

    private static void validateOptionalId(String value, String field) {
        if (value != null && !value.matches("[A-Za-z0-9_-]{1,64}")) {
            throw invalid(ErrorCode.VALIDATION_ERROR, field, "仅允许1到64位字母、数字、下划线及短横线");
        }
    }

    private static BusinessException invalid(ErrorCode code, String field, String reason) {
        return new BusinessException(code, "查询参数不合法", null, null, List.of(ProblemDetailResponse.Violation.query(field, reason)));
    }
}
