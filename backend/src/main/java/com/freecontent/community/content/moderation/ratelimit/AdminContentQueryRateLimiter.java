package com.freecontent.community.content.moderation.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.content.moderation.config.AdminContentQueryProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class AdminContentQueryRateLimiter {
    private final AdminContentQueryProperties properties;
    private final RedisRateLimitExecutor executor;

    public AdminContentQueryRateLimiter(StringRedisTemplate redis, AdminContentQueryProperties properties) {
        this.properties = properties;
        this.executor = new RedisRateLimitExecutor(redis);
    }

    public void acquire(String actor, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{content-admin-query}:";
        executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,
                List.of(prefix + "user:" + TokenDigest.sha256(actor), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                "后台内容查询过于频繁", "后台内容查询限流服务暂不可用",
                properties.userPerMinuteLimit(), properties.ipPerMinuteLimit());
    }
}
