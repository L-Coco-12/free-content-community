package com.freecontent.community.content.comment.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.content.comment.dto.CommentView;
import com.freecontent.community.content.comment.dto.CreateCommentRequest;
import com.freecontent.community.content.comment.ratelimit.CommentWriteRateLimiter;
import com.freecontent.community.content.comment.service.CommentCreationService;
import com.freecontent.community.content.comment.service.transaction.CommentCreationTransactionService;
import com.freecontent.community.content.comment.service.transaction.CommentCreationTransactionService.Claim;
import com.freecontent.community.content.comment.service.transaction.CommentCreationTransactionService.Input;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CommentCreationServiceImpl implements CommentCreationService {
    private static final Logger log = LoggerFactory.getLogger(CommentCreationServiceImpl.class);
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern EDGE_WHITESPACE = Pattern.compile("^\\s+|\\s+$", Pattern.UNICODE_CHARACTER_CLASS);

    private final CommentCreationTransactionService transactions;
    private final CommentWriteRateLimiter limiter;
    private final JsonMapper json;

    public CommentCreationServiceImpl(CommentCreationTransactionService transactions,
                                      CommentWriteRateLimiter limiter, JsonMapper json) {
        this.transactions = transactions;
        this.limiter = limiter;
        this.json = json;
    }

    @Override
    public CommentView create(String contentId, CreateCommentRequest request, String key, SessionPrincipal actor, String sourceIp) {
        if (actor == null || actor.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (contentId == null || !ID.matcher(contentId).matches()) {
            throw invalid("path", "contentId", "仅允许1到64位字母、数字、下划线及短横线");
        }
        int keyLength = key == null ? 0 : key.codePointCount(0, key.length());
        if (keyLength < 16 || keyLength > 128) throw invalid("header", "Idempotency-Key", "长度必须为16到128个字符");
        String body = EDGE_WHITESPACE.matcher(request.getBody()).replaceAll("");
        if (body.isEmpty()) throw invalid("body", "body", "去除首尾空白后不能为空");

        var input = new Input(contentId, body, request.getReplyToCommentId());
        Claim claim = null;
        try {
            claim = transactions.claim(input, TokenDigest.sha256(key), TokenDigest.sha256(json.writeValueAsString(input)), actor);
            if (claim.replay() != null) return claim.replay();
            limiter.acquire(claim.actorId(), sourceIp);
            return transactions.finish(claim, actor);
        } catch (RuntimeException ex) {
            if (claim != null && claim.replay() == null) {
                try {
                    transactions.release(claim);
                } catch (RuntimeException cleanup) {
                    log.warn("Comment reservation cleanup pending for actor {}", claim.actorId());
                }
            }
            if (ex instanceof DataAccessException || ex instanceof TransactionException) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "评论发布数据服务暂不可用");
            }
            throw ex;
        }
    }

    private static BusinessException invalid(String location, String field, String detail) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "评论发布参数校验失败", null, null,
                List.of(new ProblemDetailResponse.Violation(location, field, detail)));
    }
}
