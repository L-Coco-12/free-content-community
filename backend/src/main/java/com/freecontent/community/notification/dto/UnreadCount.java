package com.freecontent.community.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(requiredProperties = {"count", "asOf"})
public record UnreadCount(@Schema(minimum = "0", description = "当前未读通知数") long count,
                          @Schema(description = "查询时间") Instant asOf) { }
