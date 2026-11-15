package com.freecontent.community.content.model.persistence;

import java.time.LocalDateTime;

public record ContentRestorationAudit(String id, String actorId, String contentId, String publishedRevisionId,
        String beforePublicationStatus, String afterPublicationStatus, int beforeContentVersion, int afterContentVersion,
        String requestId, LocalDateTime occurredAt) { }
