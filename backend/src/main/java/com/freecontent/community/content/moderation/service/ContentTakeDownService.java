package com.freecontent.community.content.moderation.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.moderation.dto.*;

public interface ContentTakeDownService {
    ModerationAction takeDown(String contentId, TakeDownRequest input, String key, SessionPrincipal principal, String sourceIp, String requestId);
}
