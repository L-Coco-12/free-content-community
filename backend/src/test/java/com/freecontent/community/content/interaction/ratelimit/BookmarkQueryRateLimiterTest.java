package com.freecontent.community.content.interaction.ratelimit;

import com.freecontent.community.common.error.*;
import com.freecontent.community.content.interaction.config.BookmarkQueryProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookmarkQueryRateLimiterTest {
    @Test void redisFailuresAre503(){
        var redis=mock(StringRedisTemplate.class);doThrow(new DataAccessResourceFailureException("private connection detail")).when(redis).execute(any(RedisScript.class),anyList(),any(),any());
        var limiter=new BookmarkQueryRateLimiter(redis,new BookmarkQueryProperties(120,60,"test:"));
        var error=assertThrows(BusinessException.class,()->limiter.acquire("user","source"));assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,error.errorCode());assertFalse(error.getMessage().contains("private"));
    }
    @Test void uncertainQuotaDecisionsAre503(){
        var redis=mock(StringRedisTemplate.class);doReturn(List.of(2L,0L)).when(redis).execute(any(RedisScript.class),anyList(),any(),any());
        var limiter=new BookmarkQueryRateLimiter(redis,new BookmarkQueryProperties(120,60,"test:"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,assertThrows(BusinessException.class,()->limiter.acquire("user","source")).errorCode());
    }
}
