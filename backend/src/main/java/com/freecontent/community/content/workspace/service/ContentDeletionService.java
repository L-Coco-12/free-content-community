package com.freecontent.community.content.workspace.service;

import com.freecontent.community.auth.security.SessionPrincipal;

public interface ContentDeletionService {
    void delete(String contentId, String expectedVersion, SessionPrincipal principal, String sourceIp, String requestId);
}
