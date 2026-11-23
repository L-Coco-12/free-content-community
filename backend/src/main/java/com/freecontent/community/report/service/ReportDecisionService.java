package com.freecontent.community.report.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.report.dto.AdminReport;
import com.freecontent.community.report.dto.ReportDecisionRequest;

public interface ReportDecisionService {
    AdminReport decide(String reportId, ReportDecisionRequest request, String key, SessionPrincipal actor,
                       String sourceIp, String requestId);
}
