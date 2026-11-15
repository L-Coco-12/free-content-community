package com.freecontent.community.content.model.persistence;

import java.time.LocalDateTime;

public record TakeDownAudit(String id,String actionId,String contentId,String operatorId,String publishedRevisionId,
        String cancelledRevisionId,String beforeWorkingStatus,String invalidatedReviewId,int beforeContentVersion,int afterContentVersion,
        Integer beforeReviewVersion,Integer afterReviewVersion,String requestId,LocalDateTime occurredAt) { }
