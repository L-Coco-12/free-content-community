package com.freecontent.community.content.comment.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.comment.dto.HideCommentRequest;
import com.freecontent.community.content.moderation.dto.ModerationAction;

public interface CommentHideService {
    ModerationAction hide(String commentId, HideCommentRequest request, String key, SessionPrincipal actor,
                          String sourceIp, String requestId);
}
