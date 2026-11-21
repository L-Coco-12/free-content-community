package com.freecontent.community.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"markedCount"})
public record BatchReadResult(@Schema(minimum = "0", description = "本次新标记数量") int markedCount) { }
