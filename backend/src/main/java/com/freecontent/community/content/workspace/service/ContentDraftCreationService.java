package com.freecontent.community.content.workspace.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.workspace.dto.CreateDraftRequest;

public interface ContentDraftCreationService {
    ContentWorkspace create(String contentId, CreateDraftRequest input, String key, SessionPrincipal principal, String sourceIp);
}
