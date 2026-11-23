package com.freecontent.community.report.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.report.dto.AdminReport;
import com.freecontent.community.report.dto.AdminReportPage;
import com.freecontent.community.report.dto.ReportRecord.State;
import com.freecontent.community.report.mapper.AdminReportQueryMapper;
import com.freecontent.community.report.mapper.AdminReportQueryMapper.AttachmentRow;
import com.freecontent.community.report.mapper.AdminReportQueryMapper.ReportRow;
import java.math.BigInteger;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

@Service
public class AdminReportQueryTransactionService {
    private final AdminReportReadAuthorization authorization;
    private final AdminReportQueryMapper reports;

    public AdminReportQueryTransactionService(AdminReportReadAuthorization authorization, AdminReportQueryMapper reports) {
        this.authorization = authorization;
        this.reports = reports;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminReportPage list(BigInteger page, int pageSize, State state, SessionPrincipal principal) {
        authorization.requirePermission(principal);
        long total = reports.count(state.name());
        var offset = page.subtract(BigInteger.ONE).multiply(BigInteger.valueOf(pageSize));
        if (offset.compareTo(BigInteger.valueOf(total)) >= 0) {
            return new AdminReportPage(List.of(), page, pageSize, total);
        }
        var rows = reports.page(state.name(), offset.longValueExact(), pageSize);
        var reportIds = rows.stream().map(ReportRow::id).toList();
        var attachments = reports.attachments(reportIds).stream()
                .collect(groupingBy(AttachmentRow::reportId, mapping(AttachmentRow::assetId, toList())));
        var items = rows.stream()
                .map(row -> view(row, attachments.getOrDefault(row.id(), List.of())))
                .toList();
        return new AdminReportPage(items, page, pageSize, total);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminReport read(String reportId, SessionPrincipal principal) {
        authorization.requirePermission(principal);
        var row = reports.findById(reportId);
        if (row == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        var attachmentIds = reports.attachments(List.of(row.id())).stream().map(AttachmentRow::assetId).toList();
        return view(row, attachmentIds);
    }

    static AdminReport view(ReportRow row, List<String> attachments) {
        return new AdminReport(row.id(), row.targetType(), row.targetId(), row.reasonCode(), row.description(),
                row.state(), row.version(), row.createdAt().toInstant(ZoneOffset.UTC),
                row.resolvedAt() == null ? null : row.resolvedAt().toInstant(ZoneOffset.UTC), row.resolution(),
                row.reporterId(), attachments);
    }
}
