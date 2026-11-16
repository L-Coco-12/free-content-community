package com.freecontent.community.content.interaction.cursor;

import com.freecontent.community.common.error.*;
import com.freecontent.community.content.browse.config.PublicContentCursorProperties;
import com.freecontent.community.content.browse.cursor.PublicContentCursor;
import java.time.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class BookmarkCursorTest {
    private static final Instant NOW=Instant.parse("2026-11-16T04:00:00.123456Z");
    private final PublicContentCursorProperties properties=new PublicContentCursorProperties(900,Base64.getEncoder().encodeToString(new byte[32]));
    private final JsonMapper json=JsonMapper.builder().build();
    private BookmarkCursor cursor(Instant now){return new BookmarkCursor(properties,json,Clock.fixed(now,ZoneOffset.UTC));}
    @Test void roundTripPreservesTheUserSnapshotLimitAndBookmarkPosition(){
        var first=cursor(NOW).open(null,2,"user_one");String token=cursor(NOW).next(first,NOW.minusSeconds(1),"bookmark_b");
        var next=cursor(NOW.plusSeconds(800)).open(token,2,"user_one");
        assertEquals(first.snapshotAt(),next.snapshotAt());assertEquals(first.expiresAt(),next.expiresAt());
        assertEquals("user_one",next.userId());assertEquals("bookmark_b",next.lastId());assertEquals(NOW.minusSeconds(1),next.lastAt());
        String later=cursor(NOW.plusSeconds(800)).next(next,NOW.minusSeconds(2),"bookmark_a");
        assertEquals(ErrorCode.CURSOR_EXPIRED,assertThrows(BusinessException.class,()->cursor(NOW.plusSeconds(900)).open(later,2,"user_one")).errorCode());
    }
    @Test void rejectsOtherUsersLimitsRoutesAndTampering(){
        var first=cursor(NOW).open(null,2,"user_one");String token=cursor(NOW).next(first,NOW.minusSeconds(1),"bookmark_b");
        invalid(()->cursor(NOW).open(token,2,"user_two"));invalid(()->cursor(NOW).open(token,3,"user_one"));
        byte[] bytes=Base64.getUrlDecoder().decode(token.substring(3));bytes[bytes.length-1]^=1;
        invalid(()->cursor(NOW).open("v1."+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),2,"user_one"));
        var publicCursor=new PublicContentCursor(properties,json,Clock.fixed(NOW,ZoneOffset.UTC));
        var query=publicCursor.open(null,2,null,null,"user:user_one");
        invalid(()->cursor(NOW).open(publicCursor.next(query,NOW.minusSeconds(1),"bookmark_b"),2,"user_one"));
        invalid(()->publicCursor.open(token,2,null,null,"user:user_one"));
        var author=publicCursor.openForAuthor(null,2,"author", "user:user_one");
        invalid(()->cursor(NOW).open(publicCursor.next(author,NOW.minusSeconds(1),"bookmark_b"),2,"user_one"));
        assertNotEquals(token,cursor(NOW).next(first,NOW.minusSeconds(1),"bookmark_b"));
    }
    @ParameterizedTest @ValueSource(strings={"","changed","v1.AAAA","v1.____=","v2.AAAA"})
    void rejectsMalformedOrNoncanonicalTokens(String value){invalid(()->cursor(NOW).open(value,2,"user_one"));}
    private void invalid(org.junit.jupiter.api.function.Executable action){assertEquals(ErrorCode.CURSOR_INVALID,assertThrows(BusinessException.class,action).errorCode());}
}
