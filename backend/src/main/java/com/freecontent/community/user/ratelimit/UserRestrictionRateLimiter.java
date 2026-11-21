package com.freecontent.community.user.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.user.config.UserRestrictionProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class UserRestrictionRateLimiter {
    private final RedisRateLimitExecutor executor;
    private final UserRestrictionProperties properties;

    public UserRestrictionRateLimiter(StringRedisTemplate redis, UserRestrictionProperties properties) {
        this.executor = new RedisRateLimitExecutor(redis);
        this.properties = properties;
    }

    public void acquire(String userId, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{user-restriction}:";
        executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,
                List.of(prefix + "user:" + TokenDigest.sha256(userId), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                "账号限制操作过于频繁", "账号限制限流服务暂不可用",
                properties.userPerMinuteLimit(), properties.ipPerMinuteLimit());
    }
}
