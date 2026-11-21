package com.freecontent.community.content.interaction.cursor;

import com.freecontent.community.common.error.*;
import com.freecontent.community.common.pagination.CursorTokenCodec;
import com.freecontent.community.content.browse.config.PublicContentCursorProperties;
import com.freecontent.community.content.interaction.model.BookmarkListQuery;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 与公开列表共用稳定密钥与期限，独立AAD绑定本人收藏接口及关系排序。 */
@Component
public class BookmarkCursor {
    private static final String PURPOSE="v1:GET:/api/v1/me/bookmarks:bookmark.createdAt,id:DESC";
    private final PublicContentCursorProperties properties;
    private final Clock clock;
    private final CursorTokenCodec codec;
    public BookmarkCursor(PublicContentCursorProperties properties,JsonMapper json,Clock clock){
        this.properties=properties;this.clock=clock;
        this.codec=new CursorTokenCodec(properties.keyBase64(),PURPOSE,json);
    }
    public record Payload(int limit,String userId,String snapshotAt,String expiresAt,String lastAt,String lastId) { }
    public BookmarkListQuery open(String token,int limit,String userId){
        if(token==null){
            Instant snapshot=clock.instant().truncatedTo(ChronoUnit.MICROS);
            return new BookmarkListQuery(limit,userId,snapshot,snapshot.plusSeconds(properties.ttlSeconds()),null,null);
        }
        BookmarkListQuery query;
        try{
            Payload payload=codec.decode(token,Payload.class);
            if(payload==null || payload.limit()!=limit || limit<1 || limit>50 || !Objects.equals(userId,payload.userId())
                    || payload.lastId()==null || !payload.lastId().matches("[A-Za-z0-9_-]{1,64}"))throw invalid();
            Instant snapshot=Instant.parse(payload.snapshotAt()),expires=Instant.parse(payload.expiresAt()),last=Instant.parse(payload.lastAt());
            if(!expires.isAfter(snapshot) || last.isAfter(snapshot))throw invalid();
            query=new BookmarkListQuery(limit,userId,snapshot,expires,last,payload.lastId());
        }catch(RuntimeException ex){throw invalid();}
        if(!query.expiresAt().isAfter(clock.instant()))throw new BusinessException(ErrorCode.CURSOR_EXPIRED);
        return query;
    }
    public String next(BookmarkListQuery query,Instant lastAt,String lastId){
        var payload=new Payload(query.limit(),query.userId(),query.snapshotAt().toString(),query.expiresAt().toString(),lastAt.toString(),lastId);
        return codec.encode(payload);
    }
    private BusinessException invalid(){return new BusinessException(ErrorCode.CURSOR_INVALID);}
}
