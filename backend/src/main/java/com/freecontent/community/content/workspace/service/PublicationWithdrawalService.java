package com.freecontent.community.content.workspace.service;

import com.freecontent.community.auth.security.SessionPrincipal;

public interface PublicationWithdrawalService {
    void withdraw(String contentId, String expectedVersion, SessionPrincipal principal, String sourceIp, String requestId);
}
