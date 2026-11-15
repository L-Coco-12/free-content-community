package com.freecontent.community.content.workspace.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.workspace.dto.PublicationRequest;

public interface ContentRepublicationService {
    ContentWorkspace republish(String contentId, PublicationRequest input, SessionPrincipal principal, String sourceIp, String requestId);
}
