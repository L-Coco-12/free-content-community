package com.freecontent.community.report.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.media.dto.MediaAccess;

public interface ReportAttachmentAccessService {
    MediaAccess getAccess(String reportId, String assetId, SessionPrincipal actor, String sourceIp, String requestId);
}
