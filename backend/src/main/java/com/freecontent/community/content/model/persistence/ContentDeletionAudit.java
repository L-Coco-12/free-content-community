package com.freecontent.community.content.model.persistence;

import java.time.LocalDateTime;

/** 实际移入回收站的最小业务审计；从未发布时历史发布指针为空。 */
public record ContentDeletionAudit(String id, String actorId, String contentId, String publicationStatus, String publishedRevisionId,
        String invalidatedReviewId, String cancelledRevisionId, int beforeContentVersion, int afterContentVersion,
        Integer beforeReviewVersion, Integer afterReviewVersion, String requestId, LocalDateTime occurredAt) { }
