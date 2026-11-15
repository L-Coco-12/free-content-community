package com.freecontent.community.content.model.persistence;

import java.time.LocalDateTime;

/** 实际重新公开的最小业务审计参数。 */
public record PublicationRepublishAudit(String id, String actorId, String contentId, String publishedRevisionId,
        int beforeContentVersion, int afterContentVersion, String requestId, LocalDateTime occurredAt) { }
