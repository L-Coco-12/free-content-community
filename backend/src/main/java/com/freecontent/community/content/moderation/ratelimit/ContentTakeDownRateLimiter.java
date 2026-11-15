package com.freecontent.community.content.moderation.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.content.moderation.config.ContentTakeDownProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ContentTakeDownRateLimiter {
    private final ContentTakeDownProperties properties;
    private final RedisRateLimitExecutor executor;
    public ContentTakeDownRateLimiter(StringRedisTemplate redis, ContentTakeDownProperties properties) {
        this.properties = properties; this.executor = new RedisRateLimitExecutor(redis);
    }
    public void acquire(String actor, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{content-take-down}:";
        executor.acquire(Policy.USER_HOUR_SOURCE_MINUTE, List.of(prefix + "user:" + TokenDigest.sha256(actor), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                "强制下架过于频繁", "强制下架限流服务暂不可用", properties.userHourlyLimit(), properties.ipPerMinuteLimit());
    }
}
