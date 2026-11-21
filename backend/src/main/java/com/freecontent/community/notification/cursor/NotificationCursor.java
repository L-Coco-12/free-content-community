package com.freecontent.community.notification.cursor;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.pagination.CursorTokenCodec;
import com.freecontent.community.notification.config.NotificationCursorProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class NotificationCursor {
    public enum ReadState { ALL, UNREAD, READ }

    public record Query(String userId, ReadState readState, int limit, Instant snapshotAt, Instant expiresAt,
                        Instant lastAt, String lastId) { }

    private record Payload(String userId, ReadState readState, int limit, String snapshotAt, String expiresAt,
                           String lastAt, String lastId) { }

    private final NotificationCursorProperties properties;
    private final Clock clock;
    private final CursorTokenCodec codec;

    public NotificationCursor(NotificationCursorProperties properties, JsonMapper json, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.codec = new CursorTokenCodec(properties.keyBase64(), "v1:GET:/api/v1/me/notifications:createdAt,id:DESC", json);
    }

    public Query open(String token, String userId, ReadState readState, int limit) {
        if (token == null) {
            Instant snapshot = clock.instant().truncatedTo(ChronoUnit.MICROS);
            return new Query(userId, readState, limit, snapshot, snapshot.plusSeconds(properties.ttlSeconds()), null, null);
        }
        Payload payload = codec.decode(token, Payload.class);
        Query query;
        try {
            if (payload == null || !userId.equals(payload.userId()) || payload.readState() != readState
                    || payload.limit() != limit || payload.lastId() == null || !payload.lastId().matches("[A-Za-z0-9_-]{1,64}")) {
                throw new IllegalArgumentException();
            }
            Instant snapshot = Instant.parse(payload.snapshotAt());
            Instant expires = Instant.parse(payload.expiresAt());
            Instant last = Instant.parse(payload.lastAt());
            if (!expires.isAfter(snapshot) || last.isAfter(snapshot)) throw new IllegalArgumentException();
            query = new Query(userId, readState, limit, snapshot, expires, last, payload.lastId());
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.CURSOR_INVALID);
        }
        if (!query.expiresAt().isAfter(clock.instant())) throw new BusinessException(ErrorCode.CURSOR_EXPIRED);
        return query;
    }

    public String next(Query query, Instant lastAt, String lastId) {
        return codec.encode(new Payload(query.userId(), query.readState(), query.limit(), query.snapshotAt().toString(),
                query.expiresAt().toString(), lastAt.toString(), lastId));
    }
}
