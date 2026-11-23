package com.freecontent.community.report.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.report.dto.ReportPage;
import com.freecontent.community.report.dto.ReportRecord;

public interface MyReportsService {
    ReportPage list(String page, String pageSize, SessionPrincipal actor, String sourceIp);
    ReportRecord get(String reportId, SessionPrincipal actor, String sourceIp);
}
