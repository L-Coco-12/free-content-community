package com.freecontent.community.notification.model;

import java.time.LocalDateTime;

public record ModerationNoticeInsert(String id,String recipientId,String sourceEventId,String contentId,String body,LocalDateTime createdAt) { }
