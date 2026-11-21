package com.freecontent.community.auth.session;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.*;
import java.time.Clock;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionMetadataReadTest {
    private final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    private final RedisSessionStore store=new RedisSessionStore(redis,LoginTestSupport.properties("test:metadata:"),Clock.systemUTC());
    private static final String DIGEST=TokenDigest.sha256("token");
    @Test void missingCurrentSessionIs401WhileRedisFailureIs503(){
        doReturn(List.of(0L)).when(redis).execute(any(RedisScript.class),anyList(),any(Object[].class));
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED,assertThrows(BusinessException.class,()->store.listActive("user1",1,"session1",DIGEST)).errorCode());
        doThrow(new RedisConnectionFailureException("private connection detail")).when(redis).execute(any(RedisScript.class),anyList(),any(Object[].class));
        var error=assertThrows(BusinessException.class,()->store.listActive("user1",1,"session1",DIGEST));assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,error.errorCode());assertFalse(error.getMessage().contains("private"));
    }
    @ParameterizedTest @MethodSource("invalidReplies")
    void uncertainOrDamagedResultsCannotBecomeAnEmptySuccessfulList(List<?> reply){
        doReturn(reply).when(redis).execute(any(RedisScript.class),anyList(),any(Object[].class));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,assertThrows(BusinessException.class,()->store.listActive("user1",1,"session1",DIGEST)).errorCode());
    }
    static Stream<Arguments> invalidReplies(){return Stream.of(
            Arguments.of((Object)null),Arguments.of(List.of()),Arguments.of(List.of(1L)),Arguments.of(List.of(2L)),Arguments.of(List.of(0.5)),Arguments.of(List.of("1")),
            Arguments.of(List.of(0L,"extra")),Arguments.of(List.of(1L,"session1")),
            Arguments.of(List.of(1L,"session1",0L,"100","0","")),
            Arguments.of(List.of(1L,"session1","9223372036854775808","100","0","")),
            Arguments.of(List.of(1L,"session1","bad","100","0","")),
            Arguments.of(List.of(1L,"session1","0","100","-1","")),
            Arguments.of(List.of(1L,"session1","0","100","100","")),
            Arguments.of(List.of(1L,"session1","100","0","0","")),
            Arguments.of(List.of(1L,"session1","0","100","0","a".repeat(121))),
            Arguments.of(List.of(1L,"session1","0","100","0","","session1","0","100","0","")),
            Arguments.of(List.of(1L,"other","0","100","0","")));
    }
    @Test void invalidIdentityNeverReachesRedis(){
        for(String[] identity:List.of(new String[]{"bad:user","session1",DIGEST},new String[]{"user1","bad:id",DIGEST},new String[]{"user1","session1","raw-token"}))
            assertEquals(ErrorCode.AUTHENTICATION_REQUIRED,assertThrows(BusinessException.class,()->store.listActive(identity[0],1,identity[1],identity[2])).errorCode());
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED,assertThrows(BusinessException.class,()->store.listActive("user1",0,"session1",DIGEST)).errorCode());verifyNoInteractions(redis);
    }
}
