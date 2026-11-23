package com.freecontent.community.content.comment.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.content.comment.dto.HideCommentRequest;
import com.freecontent.community.content.comment.ratelimit.CommentHideRateLimiter;
import com.freecontent.community.content.comment.service.CommentHideService;
import com.freecontent.community.content.comment.service.transaction.CommentHideTransactionService;
import com.freecontent.community.content.comment.service.transaction.CommentHideTransactionService.Claim;
import com.freecontent.community.content.comment.service.transaction.CommentHideTransactionService.Input;
import com.freecontent.community.content.moderation.dto.ModerationAction;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CommentHideServiceImpl implements CommentHideService {
    private static final Logger log = LoggerFactory.getLogger(CommentHideServiceImpl.class);
    private static final Pattern EDGE_WHITESPACE = Pattern.compile("^\\s+|\\s+$", Pattern.UNICODE_CHARACTER_CLASS);
    private final CommentHideTransactionService transactions;
    private final CommentHideRateLimiter limiter;
    private final JsonMapper json;

    public CommentHideServiceImpl(CommentHideTransactionService transactions, CommentHideRateLimiter limiter, JsonMapper json) {
        this.transactions = transactions;
        this.limiter = limiter;
        this.json = json;
    }

    @Override
    public ModerationAction hide(String commentId, HideCommentRequest request, String key, SessionPrincipal actor,
                                 String sourceIp, String requestId) {
        if (actor == null || actor.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (commentId == null || !commentId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw invalid("path", "commentId", "仅允许1到64位字母、数字、下划线及短横线");
        }
        int keyLength = key == null ? 0 : key.codePointCount(0, key.length());
        if (keyLength < 16 || keyLength > 128) throw invalid("header", "Idempotency-Key", "长度必须为16到128个字符");
        String reason = EDGE_WHITESPACE.matcher(request.getReason()).replaceAll("");
        if (reason.isEmpty()) throw invalid("body", "reason", "去除首尾空白后不能为空");
        var input = new Input(commentId, request.getExpectedVersion(), reason);

        Claim claim = null;
        try {
            claim = transactions.claim(input, TokenDigest.sha256(key), TokenDigest.sha256(json.writeValueAsString(input)), actor);
            if (claim.replay() != null) return claim.replay();
            limiter.acquire(claim.operatorId(), sourceIp);
            return transactions.finish(claim, actor, requestId);
        } catch (RuntimeException ex) {
            if (claim != null && claim.replay() == null) {
                try {
                    transactions.release(claim);
                } catch (RuntimeException cleanup) {
                    log.warn("Comment-hide reservation cleanup pending for actor {}", claim.operatorId());
                }
            }
            if (ex instanceof DataAccessException || ex instanceof TransactionException) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "评论隐藏数据服务暂不可用");
            }
            throw ex;
        }
    }

    private static BusinessException invalid(String location, String field, String reason) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "评论隐藏参数校验失败", null, null,
                List.of(new ProblemDetailResponse.Violation(location, field, reason)));
    }
}
