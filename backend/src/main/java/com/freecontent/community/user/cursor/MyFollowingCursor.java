package com.freecontent.community.user.cursor;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.pagination.CursorTokenCodec;
import com.freecontent.community.user.config.UserFollowQueryProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class MyFollowingCursor {
    private static final String PURPOSE = "v1:GET:/api/v1/me/following:follow.createdAt,id:DESC";
    private final CursorTokenCodec codec;
    private final Clock clock;
    private final int ttlSeconds;

    public MyFollowingCursor(UserFollowQueryProperties properties, JsonMapper json, Clock clock) {
        this.codec = new CursorTokenCodec(properties.cursorKeyBase64(), PURPOSE, json);
        this.clock = clock;
        this.ttlSeconds = properties.cursorTtlSeconds();
    }

    public Query open(String token, String userId, int limit) {
        Instant now = clock.instant();
        if (token == null) {
            Instant snapshot = now.truncatedTo(ChronoUnit.MICROS);
            return new Query(userId, limit, snapshot, snapshot.plusSeconds(ttlSeconds), null, null);
        }

        var payload = codec.decode(token, Payload.class);
        if (payload == null || !userId.equals(payload.userId()) || limit != payload.limit()
                || payload.lastId() == null || !payload.lastId().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(ErrorCode.CURSOR_INVALID);
        }
        Query query;
        try {
            var snapshot = Instant.parse(payload.snapshotAt());
            var expires = Instant.parse(payload.expiresAt());
            var last = Instant.parse(payload.lastAt());
            if (!expires.isAfter(snapshot) || last.isAfter(snapshot)) throw new IllegalArgumentException();
            query = new Query(userId, limit, snapshot, expires, last, payload.lastId());
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.CURSOR_INVALID);
        }
        if (!query.expiresAt().isAfter(now)) throw new BusinessException(ErrorCode.CURSOR_EXPIRED);
        return query;
    }

    public String next(Query query, Instant lastAt, String lastId) {
        return codec.encode(new Payload(query.userId(), query.limit(), query.snapshotAt().toString(),
                query.expiresAt().toString(), lastAt.toString(), lastId));
    }

    public record Query(String userId, int limit, Instant snapshotAt, Instant expiresAt, Instant lastAt, String lastId) { }
    private record Payload(String userId, int limit, String snapshotAt, String expiresAt, String lastAt, String lastId) { }
}
