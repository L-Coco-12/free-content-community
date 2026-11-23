package com.freecontent.community.report.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse.Violation;
import com.freecontent.community.media.dto.MediaAccess;
import com.freecontent.community.media.storage.MediaObjectReader;
import com.freecontent.community.report.ratelimit.ReportAttachmentAccessRateLimiter;
import com.freecontent.community.report.service.ReportAttachmentAccessService;
import com.freecontent.community.report.service.transaction.ReportAttachmentAccessTransactionService;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class ReportAttachmentAccessServiceImpl implements ReportAttachmentAccessService {
    private final ReportAttachmentAccessTransactionService transactions;
    private final MediaObjectReader objects;
    private final ReportAttachmentAccessRateLimiter limiter;

    public ReportAttachmentAccessServiceImpl(ReportAttachmentAccessTransactionService transactions,
            MediaObjectReader objects, ReportAttachmentAccessRateLimiter limiter) {
        this.transactions = transactions;
        this.objects = objects;
        this.limiter = limiter;
    }

    @Override
    public MediaAccess getAccess(String reportId, String assetId, SessionPrincipal actor, String sourceIp, String requestId) {
        if (actor == null || actor.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        validateId(reportId, "reportId");
        validateId(assetId, "assetId");
        limiter.acquire(actor.user().id(), sourceIp);
        try {
            var checked = transactions.prepare(reportId, assetId, actor);
            objects.verify(checked);
            return transactions.finish(reportId, assetId, actor, checked, requestId);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "举报附件读取数据服务暂不可用");
        }
    }

    private static void validateId(String id, String field) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "资源ID格式不正确", null, null,
                    List.of(new Violation("path", field, "仅允许1到64位字母、数字、下划线及短横线")));
        }
    }
}
