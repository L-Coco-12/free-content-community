package com.freecontent.community.user.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.user.config.UserRoleUpdateProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class UserRoleUpdateRateLimiter {
    private final RedisRateLimitExecutor executor;
    private final UserRoleUpdateProperties properties;

    public UserRoleUpdateRateLimiter(StringRedisTemplate redis, UserRoleUpdateProperties properties) {
        this.executor = new RedisRateLimitExecutor(redis);
        this.properties = properties;
    }

    public void acquire(String userId, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{user-role-update}:";
        executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,
                List.of(prefix + "user:" + TokenDigest.sha256(userId), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                "角色变更操作过于频繁", "角色变更限流服务暂不可用",
                properties.userPerMinuteLimit(), properties.ipPerMinuteLimit());
    }
}
