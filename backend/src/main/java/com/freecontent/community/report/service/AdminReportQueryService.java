package com.freecontent.community.report.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.report.dto.AdminReport;
import com.freecontent.community.report.dto.AdminReportPage;

public interface AdminReportQueryService {
    AdminReportPage list(String page, String pageSize, String state, SessionPrincipal actor, String sourceIp);
    AdminReport get(String reportId, SessionPrincipal actor, String sourceIp);
}
