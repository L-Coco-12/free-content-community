package com.freecontent.community.content.workspace.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.workspace.dto.PublicationRequest;
import com.freecontent.community.content.workspace.service.ContentRepublicationService;
import com.freecontent.community.content.workspace.service.transaction.ContentRepublicationTransactionService;
import com.freecontent.community.content.workspace.ratelimit.ContentRepublicationRateLimiter;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class ContentRepublicationServiceImpl implements ContentRepublicationService {
    private final ContentRepublicationTransactionService transactions;
    private final ContentRepublicationRateLimiter limiter;
    public ContentRepublicationServiceImpl(ContentRepublicationTransactionService transactions, ContentRepublicationRateLimiter limiter) {
        this.transactions = transactions; this.limiter = limiter;
    }
    @Override
    public ContentWorkspace republish(String contentId, PublicationRequest input, SessionPrincipal principal, String sourceIp, String requestId) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (contentId == null || !contentId.matches("^[A-Za-z0-9_-]{1,64}$")) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "内容ID格式不正确", null, null,
                List.of(new ProblemDetailResponse.Violation("path", "contentId", "仅允许1到64位字母、数字、下划线及短横线")));
        limiter.acquire(principal.user().id(), sourceIp);
        try {
            return transactions.republish(contentId, input, principal, requestId);
        } catch (DataAccessException | TransactionException failure) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "重新公开内容数据服务暂不可用");
        }
    }
}
