package com.freecontent.community.report.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.report.dto.ReportRecord;
import com.freecontent.community.report.dto.ReportSubmissionRequest;

public interface ReportSubmissionService {
    ReportRecord create(ReportSubmissionRequest request, String key, SessionPrincipal actor, String sourceIp);
}
