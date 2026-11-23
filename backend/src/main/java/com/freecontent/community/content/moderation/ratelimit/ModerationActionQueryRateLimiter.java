package com.freecontent.community.content.moderation.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.content.moderation.config.ModerationActionQueryProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ModerationActionQueryRateLimiter {
    private final ModerationActionQueryProperties properties;
    private final RedisRateLimitExecutor executor;

    public ModerationActionQueryRateLimiter(StringRedisTemplate redis, ModerationActionQueryProperties properties) {
        this.properties = properties;
        this.executor = new RedisRateLimitExecutor(redis);
    }

    public void acquire(String userId, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{content-moderation-query}:";
        executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,
                List.of(prefix + "user:" + TokenDigest.sha256(userId), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                "运营处置查询过于频繁", "运营处置查询限流服务暂不可用",
                properties.userPerMinuteLimit(), properties.ipPerMinuteLimit());
    }
}
