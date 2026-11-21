package com.freecontent.community.user.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.user.config.RoleQueryProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class AdminRoleQueryRateLimiter {
    private final RedisRateLimitExecutor executor;
    private final RoleQueryProperties properties;

    public AdminRoleQueryRateLimiter(StringRedisTemplate redis, RoleQueryProperties properties) {
        this.executor = new RedisRateLimitExecutor(redis);
        this.properties = properties;
    }

    public void acquire(String userId, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{user-role-query}:";
        executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,
                List.of(prefix + "user:" + TokenDigest.sha256(userId), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                "角色查询过于频繁", "角色查询限流服务暂不可用",
                properties.userPerMinuteLimit(), properties.ipPerMinuteLimit());
    }
}
