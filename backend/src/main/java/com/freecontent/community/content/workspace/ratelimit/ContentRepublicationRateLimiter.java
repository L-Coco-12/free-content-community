package com.freecontent.community.content.workspace.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.content.workspace.config.ContentRepublicationProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ContentRepublicationRateLimiter {
    private final ContentRepublicationProperties properties;
    private final RedisRateLimitExecutor executor;
    public ContentRepublicationRateLimiter(StringRedisTemplate redis, ContentRepublicationProperties properties) {
        this.properties = properties; this.executor = new RedisRateLimitExecutor(redis);
    }
    public void acquire(String actor, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{content-publication-republish}:";
        executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,
                List.of(prefix + "user:" + TokenDigest.sha256(actor), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                "重新公开过于频繁", "重新公开限流服务暂不可用", properties.userPerMinuteLimit(), properties.ipPerMinuteLimit());
    }
}
