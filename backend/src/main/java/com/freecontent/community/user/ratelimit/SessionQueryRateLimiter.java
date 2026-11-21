package com.freecontent.community.user.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.user.config.SessionQueryProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SessionQueryRateLimiter {
    private final RedisRateLimitExecutor executor;
    private final SessionQueryProperties properties;
    public SessionQueryRateLimiter(StringRedisTemplate redis,SessionQueryProperties properties){this.executor=new RedisRateLimitExecutor(redis);this.properties=properties;}
    public void acquire(String user,String sourceIp){
        String prefix=properties.redisKeyPrefix()+"{user-session-query}:";
        executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,List.of(prefix+"user:"+TokenDigest.sha256(user),prefix+"source:"+TokenDigest.sha256(sourceIp)),
                "本人会话查询过于频繁","本人会话查询限流服务暂不可用",properties.userPerMinuteLimit(),properties.ipPerMinuteLimit());
    }
}
