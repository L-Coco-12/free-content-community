package com.freecontent.community.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(name="Session",requiredProperties={"id","createdAt","expiresAt","lastSeenAt","current","deviceLabel"})
public record SessionView(@Schema(minLength=1,maxLength=64,pattern="^[A-Za-z0-9_-]+$") String id,
        Instant createdAt,Instant expiresAt,Instant lastSeenAt,boolean current,@Schema(maxLength=120) String deviceLabel) { }
