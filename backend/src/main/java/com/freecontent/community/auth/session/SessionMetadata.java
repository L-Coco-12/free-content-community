package com.freecontent.community.auth.session;

import java.time.Instant;

/** 供账号模块展示的会话投影；不携带凭证、凭证摘要或认证版本。 */
public record SessionMetadata(String id,Instant createdAt,Instant expiresAt,Instant lastSeenAt,String deviceLabel) { }
