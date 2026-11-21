package com.freecontent.community.notification.cursor;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.browse.config.PublicContentCursorProperties;
import com.freecontent.community.content.interaction.cursor.BookmarkCursor;
import com.freecontent.community.notification.config.NotificationCursorProperties;
import com.freecontent.community.notification.cursor.NotificationCursor.ReadState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class NotificationCursorTest {
    private static final Instant NOW = Instant.parse("2026-11-18T03:04:05.123456Z");
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void cursorExpiryStaysFixedAcrossPages() {
        var first = cursor(NOW).open(null, "user_1", ReadState.READ, 20);
        String next = cursor(NOW).next(first, NOW.minusSeconds(1), "notice_1");
        var resumed = cursor(NOW.plusSeconds(899)).open(next, "user_1", ReadState.READ, 20);
        assertEquals(NOW, resumed.snapshotAt());
        assertEquals(NOW.minusSeconds(1), resumed.lastAt());
        assertEquals("notice_1", resumed.lastId());
        String later = cursor(NOW.plusSeconds(899)).next(resumed, NOW.minusSeconds(2), "notice_2");

        assertEquals(ErrorCode.CURSOR_EXPIRED, assertThrows(BusinessException.class,
                () -> cursor(NOW.plusSeconds(900)).open(later, "user_1", ReadState.READ, 20)).errorCode());
    }

    @Test
    void notificationCursorCannotBeTamperedOrUsedAsBookmarkCursor() {
        var notifications = cursor(NOW);
        var first = notifications.open(null, "user_1", ReadState.ALL, 20);
        String next = notifications.next(first, NOW.minusSeconds(1), "notice_1");
        byte[] bytes = Base64.getUrlDecoder().decode(next.substring(3));
        bytes[bytes.length - 1] ^= 1;
        String tampered = "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        assertEquals(ErrorCode.CURSOR_INVALID, assertThrows(BusinessException.class,
                () -> notifications.open(tampered, "user_1", ReadState.ALL, 20)).errorCode());

        var bookmarks = new BookmarkCursor(new PublicContentCursorProperties(900, KEY), json, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(ErrorCode.CURSOR_INVALID, assertThrows(BusinessException.class,
                () -> bookmarks.open(next, 20, "user_1")).errorCode());
    }

    private NotificationCursor cursor(Instant now) {
        return new NotificationCursor(new NotificationCursorProperties(900, KEY), json, Clock.fixed(now, ZoneOffset.UTC));
    }
}
