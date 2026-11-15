package com.freecontent.community.content.workspace.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.content.workspace.service.PublicationWithdrawalService;
import com.freecontent.community.content.workspace.service.transaction.PublicationWithdrawalTransactionService;
import com.freecontent.community.content.workspace.ratelimit.PublicationWithdrawalRateLimiter;
import java.math.BigInteger;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class PublicationWithdrawalServiceImpl implements PublicationWithdrawalService {
    private final PublicationWithdrawalTransactionService transactions;
    private final PublicationWithdrawalRateLimiter limiter;
    public PublicationWithdrawalServiceImpl(PublicationWithdrawalTransactionService transactions, PublicationWithdrawalRateLimiter limiter) {
        this.transactions = transactions; this.limiter = limiter;
    }
    @Override
    public void withdraw(String contentId, String expectedVersion, SessionPrincipal principal, String sourceIp, String requestId) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (contentId == null || !contentId.matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "内容ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "contentId", "仅允许1到64位字母、数字、下划线及短横线")));
        }
        if (expectedVersion == null || !expectedVersion.matches("-?[0-9]+")) {
            throw versionError(ErrorCode.MALFORMED_REQUEST, "版本必须是整数");
        }
        BigInteger version = new BigInteger(expectedVersion);
        if (version.signum() <= 0 || version.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw versionError(ErrorCode.VALIDATION_ERROR, "版本必须在1到2147483647之间");
        }
        limiter.acquire(principal.user().id(), sourceIp);
        try {
            transactions.withdraw(contentId, version.intValueExact(), principal, requestId);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "撤回公开内容数据服务暂不可用");
        }
    }

    private BusinessException versionError(ErrorCode code, String reason) {
        return new BusinessException(code, "内容版本参数不合法", null, null,
                List.of(ProblemDetailResponse.Violation.query("expectedVersion", reason)));
    }
}
