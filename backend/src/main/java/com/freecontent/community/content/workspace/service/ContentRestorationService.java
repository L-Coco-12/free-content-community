package com.freecontent.community.content.workspace.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.workspace.dto.VersionRequest;

public interface ContentRestorationService {
    ContentWorkspace restore(String contentId, VersionRequest input, String key, SessionPrincipal principal, String sourceIp, String requestId);
}
