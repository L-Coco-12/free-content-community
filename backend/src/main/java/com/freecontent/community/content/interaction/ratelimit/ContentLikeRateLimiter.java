package com.freecontent.community.content.interaction.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.content.interaction.config.ContentLikeProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ContentLikeRateLimiter {
    private final RedisRateLimitExecutor executor;
    private final ContentLikeProperties properties;
    public ContentLikeRateLimiter(StringRedisTemplate redis,ContentLikeProperties properties){this.executor=new RedisRateLimitExecutor(redis);this.properties=properties;}
    public void acquire(String userId,String sourceIp) {
        String prefix=properties.redisKeyPrefix()+"{content-like}:";
        executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,List.of(prefix+"user:"+TokenDigest.sha256(userId),prefix+"source:"+TokenDigest.sha256(sourceIp)),
                "点赞请求过于频繁","点赞限流服务暂不可用",properties.userPerMinuteLimit(),properties.ipPerMinuteLimit());
    }
}
