package com.freecontent.community.content.interaction.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.content.interaction.config.ContentBookmarkProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ContentBookmarkRateLimiter {
    private final RedisRateLimitExecutor executor;
    private final ContentBookmarkProperties properties;
    public ContentBookmarkRateLimiter(StringRedisTemplate redis,ContentBookmarkProperties properties){this.executor=new RedisRateLimitExecutor(redis);this.properties=properties;}
    public void acquire(String user,String sourceIp){
        String prefix=properties.redisKeyPrefix()+"{content-bookmark}:";
        executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,List.of(prefix+"user:"+TokenDigest.sha256(user),prefix+"source:"+TokenDigest.sha256(sourceIp)),
                "收藏请求过于频繁","收藏限流服务暂不可用",properties.userPerMinuteLimit(),properties.ipPerMinuteLimit());
    }
}
