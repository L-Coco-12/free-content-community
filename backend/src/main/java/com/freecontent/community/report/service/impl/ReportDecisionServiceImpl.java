package com.freecontent.community.report.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse.Violation;
import com.freecontent.community.report.dto.AdminReport;
import com.freecontent.community.report.dto.ReportDecisionRequest;
import com.freecontent.community.report.dto.ReportDecisionRequest.Decision;
import com.freecontent.community.report.ratelimit.ReportDecisionRateLimiter;
import com.freecontent.community.report.service.ReportDecisionService;
import com.freecontent.community.report.service.transaction.ReportDecisionTransactionService;
import com.freecontent.community.report.service.transaction.ReportDecisionTransactionService.Claim;
import com.freecontent.community.report.service.transaction.ReportDecisionTransactionService.Input;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ReportDecisionServiceImpl implements ReportDecisionService {
    private static final Logger log = LoggerFactory.getLogger(ReportDecisionServiceImpl.class);
    private static final Pattern EDGE_WHITESPACE = Pattern.compile("^\\s+|\\s+$", Pattern.UNICODE_CHARACTER_CLASS);
    private final ReportDecisionTransactionService transactions;
    private final ReportDecisionRateLimiter limiter;
    private final JsonMapper json;

    public ReportDecisionServiceImpl(ReportDecisionTransactionService transactions, ReportDecisionRateLimiter limiter, JsonMapper json) {
        this.transactions = transactions;
        this.limiter = limiter;
        this.json = json;
    }

    @Override
    public AdminReport decide(String reportId, ReportDecisionRequest request, String key, SessionPrincipal actor,
                              String sourceIp, String requestId) {
        if (actor == null || actor.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (reportId == null || !reportId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw invalid("path", "reportId", "仅允许1到64位字母、数字、下划线及短横线");
        }
        int keyLength = key == null ? 0 : key.codePointCount(0, key.length());
        if (keyLength < 16 || keyLength > 128) throw invalid("header", "Idempotency-Key", "长度必须为16到128个字符");
        String reason = EDGE_WHITESPACE.matcher(request.getReason()).replaceAll("");
        if (reason.isEmpty()) throw invalid("body", "reason", "去除首尾空白后不能为空");
        if (request.getDecision() == Decision.RESOLVE && request.getModerationActionId() == null) {
            throw invalid("body", "moderationActionId", "采纳举报须关联已执行的处置记录");
        }
        var input = new Input(reportId, request.getExpectedVersion(), request.getDecision(), reason, request.getModerationActionId());

        Claim claim = null;
        try {
            claim = transactions.claim(input, TokenDigest.sha256(key), TokenDigest.sha256(json.writeValueAsString(input)), actor);
            if (claim.replay() != null) return claim.replay();
            limiter.acquire(claim.operatorId(), sourceIp);
            return transactions.finish(claim, actor, requestId);
        } catch (RuntimeException ex) {
            if (claim != null && claim.leaseToken() != null) {
                try {
                    transactions.release(claim);
                } catch (RuntimeException cleanup) {
                    log.warn("Report decision reservation cleanup pending for operator {}", claim.operatorId());
                }
            }
            if (ex instanceof DataAccessException || ex instanceof TransactionException) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "举报处理数据服务暂不可用");
            }
            throw ex;
        }
    }

    private static BusinessException invalid(String location, String field, String reason) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "举报处理参数校验失败", null, null,
                List.of(new Violation(location, field, reason)));
    }
}
