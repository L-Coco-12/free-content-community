package com.freecontent.community.user.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.user.config.AvatarAccessProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class AvatarAccessRateLimiter {
    private final RedisRateLimitExecutor executor;
    private final AvatarAccessProperties properties;
    public AvatarAccessRateLimiter(StringRedisTemplate redis,AvatarAccessProperties properties) {
        this.executor=new RedisRateLimitExecutor(redis);this.properties=properties;
    }
    public void acquire(String viewer,String sourceIp) {
        String prefix=properties.redisKeyPrefix()+"{user-avatar-access}:";
        String source=prefix+"source:"+TokenDigest.sha256(sourceIp);
        if(viewer==null)executor.acquire(Policy.SOURCE_MINUTE,List.of(source),"头像读取过于频繁","头像限流服务暂不可用",properties.ipPerMinuteLimit());
        else executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,List.of(prefix+"user:"+TokenDigest.sha256(viewer),source),
                "头像读取过于频繁","头像限流服务暂不可用",properties.userPerMinuteLimit(),properties.ipPerMinuteLimit());
    }
}
