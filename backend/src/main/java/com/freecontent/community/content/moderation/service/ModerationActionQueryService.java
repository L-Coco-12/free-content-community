package com.freecontent.community.content.moderation.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.moderation.dto.ModerationAction;

public interface ModerationActionQueryService {
    ModerationAction get(String actionId, SessionPrincipal actor, String sourceIp);
}
