package com.freecontent.community.report.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse.Violation;
import com.freecontent.community.common.pagination.OffsetPageParameters;
import com.freecontent.community.report.dto.AdminReport;
import com.freecontent.community.report.dto.AdminReportPage;
import com.freecontent.community.report.dto.ReportRecord.State;
import com.freecontent.community.report.ratelimit.AdminReportQueryRateLimiter;
import com.freecontent.community.report.service.AdminReportQueryService;
import com.freecontent.community.report.service.transaction.AdminReportQueryTransactionService;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class AdminReportQueryServiceImpl implements AdminReportQueryService {
    private final AdminReportQueryTransactionService transactions;
    private final AdminReportQueryRateLimiter limiter;

    public AdminReportQueryServiceImpl(AdminReportQueryTransactionService transactions, AdminReportQueryRateLimiter limiter) {
        this.transactions = transactions;
        this.limiter = limiter;
    }

    @Override
    public AdminReportPage list(String page, String pageSize, String state, SessionPrincipal actor, String sourceIp) {
        requireActor(actor);
        var number = OffsetPageParameters.page(page);
        int size = OffsetPageParameters.pageSize(pageSize);
        State filter = parseState(state);
        limiter.acquire(actor.user().id(), sourceIp);
        try {
            return transactions.list(number, size, filter, actor);
        } catch (DataAccessException | TransactionException ex) {
            throw unavailable();
        }
    }

    @Override
    public AdminReport get(String reportId, SessionPrincipal actor, String sourceIp) {
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
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "运营举报查询数据服务暂不可用");
    }

    private static State parseState(String raw) {
        if (raw == null) return State.OPEN;
        try {
            return State.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "查询参数不合法", null, null,
                    List.of(Violation.query("state", "必须为 OPEN、RESOLVED 或 DISMISSED")));
        }
    }
}
