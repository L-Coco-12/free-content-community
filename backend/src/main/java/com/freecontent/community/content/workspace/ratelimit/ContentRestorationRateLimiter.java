package com.freecontent.community.content.workspace.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.content.workspace.config.ContentRestorationProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ContentRestorationRateLimiter {
    private final ContentRestorationProperties properties;
    private final RedisRateLimitExecutor executor;
    public ContentRestorationRateLimiter(StringRedisTemplate redis, ContentRestorationProperties properties) {
        this.properties = properties; this.executor = new RedisRateLimitExecutor(redis);
    }
    public void acquire(String actor, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{content-restoration}:";
        executor.acquire(Policy.USER_HOUR_SOURCE_MINUTE, List.of(prefix + "user:" + TokenDigest.sha256(actor), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                "恢复内容过于频繁", "恢复内容限流服务暂不可用", properties.userHourlyLimit(), properties.ipPerMinuteLimit());
    }
}
