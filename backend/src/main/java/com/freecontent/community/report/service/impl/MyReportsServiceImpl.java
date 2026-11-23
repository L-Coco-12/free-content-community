package com.freecontent.community.report.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse.Violation;
import com.freecontent.community.common.pagination.OffsetPageParameters;
import com.freecontent.community.report.dto.ReportPage;
import com.freecontent.community.report.dto.ReportRecord;
import com.freecontent.community.report.ratelimit.ReportQueryRateLimiter;
import com.freecontent.community.report.service.MyReportsService;
import com.freecontent.community.report.service.transaction.MyReportsTransactionService;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class MyReportsServiceImpl implements MyReportsService {
    private final MyReportsTransactionService transactions;
    private final ReportQueryRateLimiter limiter;

    public MyReportsServiceImpl(MyReportsTransactionService transactions, ReportQueryRateLimiter limiter) {
        this.transactions = transactions;
        this.limiter = limiter;
    }

    @Override
    public ReportPage list(String page, String pageSize, SessionPrincipal actor, String sourceIp) {
        requireActor(actor);
        var number = OffsetPageParameters.page(page);
        int size = OffsetPageParameters.pageSize(pageSize);
        limiter.acquire(actor.user().id(), sourceIp);
        try {
            return transactions.list(number, size, actor);
        } catch (DataAccessException | TransactionException ex) {
            throw unavailable();
        }
    }

    @Override
    public ReportRecord get(String reportId, SessionPrincipal actor, String sourceIp) {
        requireActor(actor);
        if (reportId == null || !reportId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "举报ID格式不正确", null, null,
                    List.of(new Violation("path", "reportId", "仅允许1到64位字母、数字、下划线及短横线")));
        }
        limiter.acquire(actor.user().id(), sourceIp);
        try {
            return transactions.read(reportId, actor);
        } catch (DataAccessException | TransactionException ex) {
            throw unavailable();
        }
    }

    private static void requireActor(SessionPrincipal actor) {
        if (actor == null || actor.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "本人举报查询数据服务暂不可用");
    }
}
