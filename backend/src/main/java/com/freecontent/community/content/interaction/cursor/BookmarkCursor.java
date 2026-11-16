package com.freecontent.community.content.interaction.cursor;

import com.freecontent.community.common.error.*;
import com.freecontent.community.content.browse.config.PublicContentCursorProperties;
import com.freecontent.community.content.interaction.model.BookmarkListQuery;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 与公开列表共用稳定密钥与期限，独立AAD绑定本人收藏接口及关系排序。 */
@Component
public class BookmarkCursor {
    private static final byte[] PURPOSE="v1:GET:/api/v1/me/bookmarks:bookmark.createdAt,id:DESC".getBytes(StandardCharsets.UTF_8);
    private final PublicContentCursorProperties properties;
    private final JsonMapper json;
    private final Clock clock;
    private final SecretKeySpec key;
    private final SecureRandom random=new SecureRandom();
    public BookmarkCursor(PublicContentCursorProperties properties,JsonMapper json,Clock clock){
        this.properties=properties;this.json=json;this.clock=clock;
        this.key=new SecretKeySpec(Base64.getDecoder().decode(properties.keyBase64()),"AES");
    }
    public record Payload(int limit,String userId,String snapshotAt,String expiresAt,String lastAt,String lastId) { }
    public BookmarkListQuery open(String token,int limit,String userId){
        if(token==null){
            Instant snapshot=clock.instant().truncatedTo(ChronoUnit.MICROS);
            return new BookmarkListQuery(limit,userId,snapshot,snapshot.plusSeconds(properties.ttlSeconds()),null,null);
        }
        BookmarkListQuery query;
        try{
            if(!token.matches("v1\\.[A-Za-z0-9_-]+"))throw invalid();
            byte[] packed=Base64.getUrlDecoder().decode(token.substring(3));
            if(packed.length<29 || !Base64.getUrlEncoder().withoutPadding().encodeToString(packed).equals(token.substring(3)))throw invalid();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(packed,0,12)));
            cipher.updateAAD(PURPOSE);
            Payload payload=json.readValue(cipher.doFinal(Arrays.copyOfRange(packed,12,packed.length)),Payload.class);
            if(payload==null || payload.limit()!=limit || limit<1 || limit>50 || !Objects.equals(userId,payload.userId())
                    || payload.lastId()==null || !payload.lastId().matches("[A-Za-z0-9_-]{1,64}"))throw invalid();
            Instant snapshot=Instant.parse(payload.snapshotAt()),expires=Instant.parse(payload.expiresAt()),last=Instant.parse(payload.lastAt());
            if(!expires.isAfter(snapshot) || last.isAfter(snapshot))throw invalid();
            query=new BookmarkListQuery(limit,userId,snapshot,expires,last,payload.lastId());
        }catch(GeneralSecurityException | RuntimeException ex){throw invalid();}
        if(!query.expiresAt().isAfter(clock.instant()))throw new BusinessException(ErrorCode.CURSOR_EXPIRED);
        return query;
    }
    public String next(BookmarkListQuery query,Instant lastAt,String lastId){
        var payload=new Payload(query.limit(),query.userId(),query.snapshotAt().toString(),query.expiresAt().toString(),lastAt.toString(),lastId);
        try{
            byte[] iv=new byte[12];random.nextBytes(iv);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));cipher.updateAAD(PURPOSE);
            byte[] encrypted=cipher.doFinal(json.writeValueAsBytes(payload));
            return "v1."+Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(iv.length+encrypted.length).put(iv).put(encrypted).array());
        }catch(GeneralSecurityException ex){throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"收藏列表游标服务暂不可用");}
    }
    private BusinessException invalid(){return new BusinessException(ErrorCode.CURSOR_INVALID);}
}
