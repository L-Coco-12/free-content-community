package com.freecontent.community.notification.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.notification.config.NotificationQueryProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class NotificationQueryRateLimiter {
    private final RedisRateLimitExecutor executor;
    private final NotificationQueryProperties properties;

    public NotificationQueryRateLimiter(StringRedisTemplate redis, NotificationQueryProperties properties) {
        this.executor = new RedisRateLimitExecutor(redis);
        this.properties = properties;
    }

    public void acquire(String userId, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{notification-query}:";
        executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,
                List.of(prefix + "user:" + TokenDigest.sha256(userId), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                "通知查询过于频繁", "通知查询限流服务暂不可用",
                properties.userPerMinuteLimit(), properties.ipPerMinuteLimit());
    }
}
