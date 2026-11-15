package com.freecontent.community.content.moderation.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.content.moderation.dto.ModerationAction;
import com.freecontent.community.content.moderation.dto.TakeDownRequest;
import com.freecontent.community.content.moderation.service.ContentTakeDownService;
import com.freecontent.community.content.moderation.service.transaction.ContentTakeDownTransactionService;
import com.freecontent.community.content.moderation.ratelimit.ContentTakeDownRateLimiter;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ContentTakeDownServiceImpl implements ContentTakeDownService {
    private static final Logger log = LoggerFactory.getLogger(ContentTakeDownServiceImpl.class);
    private final ContentTakeDownTransactionService transactions;
    private final JsonMapper json;
    private final ContentTakeDownRateLimiter limiter;
    public ContentTakeDownServiceImpl(ContentTakeDownTransactionService transactions, JsonMapper json, ContentTakeDownRateLimiter limiter) {
        this.transactions = transactions; this.json = json;
        this.limiter = limiter;
    }
    @Override
    public ModerationAction takeDown(String contentId, TakeDownRequest input, String key, SessionPrincipal principal, String sourceIp, String requestId) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (contentId == null || !contentId.matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "内容ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "contentId", "仅允许1到64位字母、数字、下划线及短横线")));
        }
        if (key == null || key.codePointCount(0, key.length()) < 16 || key.codePointCount(0, key.length()) > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "幂等键长度必须为16到128个字符", null, null,
                    List.of(new ProblemDetailResponse.Violation("header", "Idempotency-Key", "长度必须为16到128个字符")));
        }
        var normalized = new LinkedHashMap<String, Object>();
        normalized.put("contentId", contentId); normalized.put("expectedVersion", input.getExpectedVersion());
        normalized.put("reasonCode", input.getReasonCode().name()); normalized.put("reason", input.getReason());
        String keyHash = TokenDigest.sha256(key);
        String fingerprint = TokenDigest.sha256(json.writeValueAsString(normalized));
        ContentTakeDownTransactionService.Claim claim = null;
        try {
            claim = claimWithRetry(contentId, input, keyHash, fingerprint, principal);
            if (claim.replay() != null) return claim.replay();
            limiter.acquire(principal.user().id(),sourceIp);
            return transactions.finish(claim, principal, requestId);
        } catch (RuntimeException ex) {
            if (claim != null && claim.replay() == null) {
                try { transactions.release(claim); }
                catch (RuntimeException cleanup) { log.warn("Take-down reservation cleanup pending, reservationId={}", claim.request().getId()); }
            }
            if (ex instanceof DataAccessException || ex instanceof TransactionException) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "强制下架数据服务暂不可用");
            }
            throw ex;
        }
    }
    private ContentTakeDownTransactionService.Claim claimWithRetry(String contentId, TakeDownRequest input, String keyHash, String fingerprint, SessionPrincipal principal) {
        try { return transactions.claim(contentId, input, keyHash, fingerprint, principal); }
        catch (DuplicateKeyException race) { return transactions.claim(contentId, input, keyHash, fingerprint, principal); }
    }
}
