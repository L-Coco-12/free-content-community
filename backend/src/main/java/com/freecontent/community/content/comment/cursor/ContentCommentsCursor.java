package com.freecontent.community.content.comment.cursor;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.pagination.CursorTokenCodec;
import com.freecontent.community.content.browse.config.PublicContentCursorProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ContentCommentsCursor {
    private static final String ROOT_PURPOSE = "v1:GET:/api/v1/contents/{contentId}/comments:createdAt,id:DESC";
    private static final String REPLY_PURPOSE = "v1:GET:/api/v1/comments/{commentId}/replies:createdAt,id:ASC";
    private final CursorTokenCodec roots;
    private final CursorTokenCodec replies;
    private final Clock clock;
    private final int ttlSeconds;

    public ContentCommentsCursor(PublicContentCursorProperties properties, JsonMapper json, Clock clock) {
        this.roots = new CursorTokenCodec(properties.keyBase64(), ROOT_PURPOSE, json);
        this.replies = new CursorTokenCodec(properties.keyBase64(), REPLY_PURPOSE, json);
        this.ttlSeconds = properties.ttlSeconds();
        this.clock = clock;
    }

    public Query open(String token, String contentId, String viewerId, int limit) {
        return open(token, contentId, viewerId, limit, Kind.ROOTS);
    }

    public Query open(String token, String resourceId, String viewerId, int limit, Kind kind) {
        Instant now = clock.instant();
        if (token == null) {
            var snapshot = now.truncatedTo(ChronoUnit.MICROS);
            return new Query(resourceId, viewerId, limit, snapshot, snapshot.plusSeconds(ttlSeconds), null, null, kind);
        }
        var payload = codec(kind).decode(token, Payload.class);
        if (payload == null || !resourceId.equals(payload.contentId()) || !Objects.equals(viewerId, payload.viewerId())
                || limit != payload.limit() || payload.lastId() == null || !payload.lastId().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(ErrorCode.CURSOR_INVALID);
        }
        Query query;
        try {
            var snapshot = Instant.parse(payload.snapshotAt());
            var expires = Instant.parse(payload.expiresAt());
            var last = Instant.parse(payload.lastAt());
            if (!expires.isAfter(snapshot) || last.isAfter(snapshot)) throw new IllegalArgumentException();
            query = new Query(resourceId, viewerId, limit, snapshot, expires, last, payload.lastId(), kind);
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.CURSOR_INVALID);
        }
        if (!query.expiresAt().isAfter(now)) throw new BusinessException(ErrorCode.CURSOR_EXPIRED);
        return query;
    }

    public String next(Query query, Instant lastAt, String lastId) {
        return codec(query.kind()).encode(new Payload(query.resourceId(), query.viewerId(), query.limit(),
                query.snapshotAt().toString(), query.expiresAt().toString(), lastAt.toString(), lastId));
    }

    private CursorTokenCodec codec(Kind kind) {
        return kind == Kind.ROOTS ? roots : replies;
    }

    public enum Kind { ROOTS, REPLIES }

    public record Query(String resourceId, String viewerId, int limit, Instant snapshotAt, Instant expiresAt,
                        Instant lastAt, String lastId, Kind kind) { }

    // contentId 是原顶级评论游标的字段名，保留以兼容已签发的游标。
    private record Payload(String contentId, String viewerId, int limit, String snapshotAt, String expiresAt,
                           String lastAt, String lastId) { }
}
