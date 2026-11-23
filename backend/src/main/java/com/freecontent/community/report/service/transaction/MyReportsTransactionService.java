package com.freecontent.community.report.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.report.dto.ReportPage;
import com.freecontent.community.report.dto.ReportRecord;
import com.freecontent.community.report.mapper.ReportMapper;
import com.freecontent.community.report.mapper.ReportMapper.ReportRow;
import com.freecontent.community.user.service.AccountAccessService;
import java.math.BigInteger;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MyReportsTransactionService {
    private final AccountAccessService accounts;
    private final ReportMapper reports;

    public MyReportsTransactionService(AccountAccessService accounts, ReportMapper reports) {
        this.accounts = accounts;
        this.reports = reports;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportPage list(BigInteger page, int pageSize, SessionPrincipal principal) {
        String reporter = authorize(principal);
        long total = reports.countOwned(reporter);
        var offset = page.subtract(BigInteger.ONE).multiply(BigInteger.valueOf(pageSize));
        if (offset.compareTo(BigInteger.valueOf(total)) >= 0) {
            return new ReportPage(List.of(), page, pageSize, total);
        }
        var items = reports.pageOwned(reporter, offset.longValueExact(), pageSize).stream()
                .map(MyReportsTransactionService::view)
                .toList();
        return new ReportPage(items, page, pageSize, total);
    }

    @Transactional(readOnly = true)
    public ReportRecord read(String reportId, SessionPrincipal principal) {
        String reporter = authorize(principal);
        var row = reports.findOwnedRecord(reportId, reporter);
        if (row == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return view(row);
    }

    private String authorize(SessionPrincipal principal) {
        var account = accounts.findByIdForShare(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (account.disabled()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!account.emailVerified()) throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        return account.id();
    }

    private static ReportRecord view(ReportRow row) {
        return new ReportRecord(row.id(), row.targetType(), row.targetId(), row.reasonCode(), row.description(),
                row.state(), row.version(), row.createdAt().toInstant(ZoneOffset.UTC),
                row.resolvedAt() == null ? null : row.resolvedAt().toInstant(ZoneOffset.UTC), row.resolution());
    }
}
