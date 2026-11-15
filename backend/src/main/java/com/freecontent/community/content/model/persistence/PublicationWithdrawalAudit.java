package com.freecontent.community.content.model.persistence;

import java.time.LocalDateTime;

/** 实际撤回的最小审计参数，未终结审核时相关字段为null。 */
public record PublicationWithdrawalAudit(String id, String actorId, String contentId, String publishedRevisionId,
        String invalidatedReviewId, String cancelledRevisionId, int beforeContentVersion, int afterContentVersion,
        Integer beforeReviewVersion, Integer afterReviewVersion, String requestId, LocalDateTime occurredAt) { }
