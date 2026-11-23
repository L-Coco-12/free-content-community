package com.freecontent.community.report.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse.Violation;
import com.freecontent.community.report.dto.ReportRecord;
import com.freecontent.community.report.dto.ReportSubmissionRequest;
import com.freecontent.community.report.dto.ReportSubmissionRequest.ReasonCode;
import com.freecontent.community.report.ratelimit.ReportSubmissionRateLimiter;
import com.freecontent.community.report.service.ReportSubmissionService;
import com.freecontent.community.report.service.transaction.ReportSubmissionTransactionService;
import com.freecontent.community.report.service.transaction.ReportSubmissionTransactionService.Claim;
import com.freecontent.community.report.service.transaction.ReportSubmissionTransactionService.Input;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ReportSubmissionServiceImpl implements ReportSubmissionService {
    private static final Logger log = LoggerFactory.getLogger(ReportSubmissionServiceImpl.class);
    private static final Pattern BLANK = Pattern.compile("\\s*", Pattern.UNICODE_CHARACTER_CLASS);
    private final ReportSubmissionTransactionService transactions;
    private final ReportSubmissionRateLimiter limiter;
    private final JsonMapper json;

    public ReportSubmissionServiceImpl(ReportSubmissionTransactionService transactions,
                                       ReportSubmissionRateLimiter limiter, JsonMapper json) {
        this.transactions = transactions;
        this.limiter = limiter;
        this.json = json;
    }

    @Override
    public ReportRecord create(ReportSubmissionRequest request, String key, SessionPrincipal actor, String sourceIp) {
        if (actor == null || actor.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        int keyLength = key == null ? 0 : key.codePointCount(0, key.length());
        if (keyLength < 16 || keyLength > 128) throw invalid("header", "Idempotency-Key", "长度必须为16到128个字符");
        if (request.getReasonCode() == ReasonCode.OTHER && BLANK.matcher(request.getDescription()).matches()) {
            throw invalid("body", "description", "OTHER 原因必须提供非空说明");
        }
        var attachments = List.copyOf(request.getAttachmentAssetIds());
        if (new HashSet<>(attachments).size() != attachments.size()) {
            throw invalid("body", "attachmentAssetIds", "附件 ID 不得重复");
        }

        var input = new Input(request.getTargetType(), request.getTargetId(), request.getReasonCode(),
                request.getDescription(), attachments);
        Claim claim = null;
        try {
            claim = transactions.claim(input, TokenDigest.sha256(key), TokenDigest.sha256(json.writeValueAsString(input)), actor);
            if (claim.replay() != null) return claim.replay();
            limiter.acquire(claim.reporterId(), sourceIp);
            return transactions.finish(claim, actor);
        } catch (RuntimeException ex) {
            if (claim != null && claim.leaseToken() != null) {
                try {
                    transactions.release(claim);
                } catch (RuntimeException cleanup) {
                    log.warn("Report submission reservation cleanup pending for reporter {}", claim.reporterId());
                }
            }
            if (ex instanceof DataAccessException || ex instanceof TransactionException) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "举报提交数据服务暂不可用");
            }
            throw ex;
        }
    }

    private static BusinessException invalid(String location, String field, String detail) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "举报提交参数校验失败", null, null,
                List.of(new Violation(location, field, detail)));
    }
}
