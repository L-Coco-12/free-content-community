package com.freecontent.community.auth.session;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OwnedSessionRevocationTest {
    private static final String DIGEST=TokenDigest.sha256("token");
    private final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    private final RedisSessionStore store=new RedisSessionStore(redis,LoginTestSupport.properties("test:revoke-owned:"),Clock.systemUTC());
    @ParameterizedTest @ValueSource(longs={0,1})
    void absentOrDeletedResultsBothConfirmRevocation(long result){
        doReturn(result).when(redis).execute(any(RedisScript.class),anyList(),any(Object[].class));
        assertDoesNotThrow(()->store.revokeOwned("user1",1,"current",DIGEST,"target"));
    }
    @Test void invalidCurrentSessionIs401(){
        doReturn(-1L).when(redis).execute(any(RedisScript.class),anyList(),any(Object[].class));
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED,assertThrows(BusinessException.class,()->store.revokeOwned("user1",1,"current",DIGEST,"target")).errorCode());
    }
    @ParameterizedTest @ValueSource(longs={-2,2,100})
    void unexpectedResultCannotConfirmDeletion(long result){
        doReturn(result).when(redis).execute(any(RedisScript.class),anyList(),any(Object[].class));unavailable();
    }
    @Test void unknownResultAndRedisFailureAre503(){
        unavailable();
        doThrow(new RedisConnectionFailureException("private connection detail")).when(redis).execute(any(RedisScript.class),anyList(),any(Object[].class));
        var error=assertThrows(BusinessException.class,()->store.revokeOwned("user1",1,"current",DIGEST,"target"));assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,error.errorCode());assertFalse(error.getMessage().contains("private"));
    }
    @Test void invalidIdentityCannotReachRedis(){
        for(String[] input:new String[][]{{"bad:user","current",DIGEST,"target"},{"user1","bad:current",DIGEST,"target"},
                {"user1","current","raw-token","target"},{"user1","current",DIGEST,"bad:target"},{null,"current",DIGEST,"target"}})
            assertEquals(ErrorCode.AUTHENTICATION_REQUIRED,assertThrows(BusinessException.class,()->store.revokeOwned(input[0],1,input[1],input[2],input[3])).errorCode());
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED,assertThrows(BusinessException.class,()->store.revokeOwned("user1",0,"current",DIGEST,"target")).errorCode());verifyNoInteractions(redis);
    }
    private void unavailable(){assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,assertThrows(BusinessException.class,()->store.revokeOwned("user1",1,"current",DIGEST,"target")).errorCode());}
}
