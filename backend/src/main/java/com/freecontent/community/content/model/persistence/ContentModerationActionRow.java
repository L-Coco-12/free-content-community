package com.freecontent.community.content.model.persistence;

import java.time.LocalDateTime;

public record ContentModerationActionRow(String id,String contentId,String reasonCode,String reason,String operatorId,int resultingVersion,LocalDateTime createdAt) { }
