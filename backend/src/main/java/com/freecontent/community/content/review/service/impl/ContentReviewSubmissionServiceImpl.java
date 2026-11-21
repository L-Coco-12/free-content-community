package com.freecontent.community.content.review.service.impl;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.content.review.dto.SubmitReviewRequest;
import com.freecontent.community.content.review.ratelimit.ContentReviewSubmissionRateLimiter;
import com.freecontent.community.content.review.service.ContentReviewSubmissionService;
import com.freecontent.community.content.review.service.transaction.ContentReviewSubmissionTransactionService;

import tools.jackson.databind.json.JsonMapper;

@Service
public class ContentReviewSubmissionServiceImpl implements ContentReviewSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(ContentReviewSubmissionServiceImpl.class);
    private final ContentReviewSubmissionTransactionService transactions;
    private final JsonMapper json;
    private final ContentReviewSubmissionRateLimiter limiter;

    public ContentReviewSubmissionServiceImpl(ContentReviewSubmissionTransactionService transactions, JsonMapper json, ContentReviewSubmissionRateLimiter limiter) {
        this.transactions = transactions;
        this.json = json;
        this.limiter = limiter;
    }

    @Override
    public ReviewResult submit(String contentId, SubmitReviewRequest input, String key, SessionPrincipal principal, String sourceIp) {
        if (principal == null || principal.user() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        validateParameters(contentId, key);
        // 事务处理：尝试获取审核提交的事务锁，若已存在相同请求的锁，则直接返回之前的结果；否则进行限流检查，并完成审核提交。
        ContentReviewSubmissionTransactionService.Claim claim = null;
        try {
            // 计算幂等键的指纹，用于在事务中验证请求的一致性。
            String fingerprint = TokenDigest.sha256(json.writeValueAsString(List.of(contentId, input.getExpectedVersion(), input.getRevisionId())));
            // 事务处理：尝试获取审核提交的事务锁，若已存在相同请求的锁，则直接返回之前的结果；否则进行限流检查，并完成审核提交。
            claim = transactions.claim(contentId, input, TokenDigest.sha256(key), fingerprint, principal);
            if (claim.replay() != null) {
                return claim.replay();
            }
            // 限流检查：在事务锁成功获取后，进行限流检查，确保同一账号在单位时间内不会提交过多的审核请求。
            limiter.acquire(principal.user().id(), sourceIp);
            // 完成审核提交：在事务锁和限流检查通过后，执行实际的审核提交操作，并返回结果。
            return transactions.finish(claim, principal);
        } catch (RuntimeException ex) {
            if (claim != null && claim.replay() == null) {
                try {
                    transactions.release(claim);
                } catch (RuntimeException cleanup) {
                    log.warn("Review submission reservation cleanup pending, reservationId={}", claim.request().getId());
                }
            }
            if (ex instanceof DataAccessException || ex instanceof TransactionException) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "审核提交数据服务暂不可用");
            }
            throw ex;
        }
    }

    private void validateParameters(String contentId, String key) {
        if (contentId == null || !contentId.matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "内容ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "contentId", "仅允许1到64位字母、数字、下划线及短横线")));
        }
        if (key == null || key.codePointCount(0, key.length()) < 16 || key.codePointCount(0, key.length()) > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "幂等键长度必须为16到128个字符", null, null,
                    List.of(new ProblemDetailResponse.Violation("header", "Idempotency-Key", "长度必须为16到128个字符")));
        }
    }
}
