package com.freecontent.community.content.workspace.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.content.workspace.config.ContentDeletionProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ContentDeletionRateLimiter {
    private final ContentDeletionProperties properties;
    private final RedisRateLimitExecutor executor;
    public ContentDeletionRateLimiter(StringRedisTemplate redis, ContentDeletionProperties properties) {
        this.properties = properties; this.executor = new RedisRateLimitExecutor(redis);
    }
    public void acquire(String actor, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{content-deletion}:";
        executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,
                List.of(prefix + "user:" + TokenDigest.sha256(actor), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                "内容删除过于频繁", "内容删除限流服务暂不可用", properties.userPerMinuteLimit(), properties.ipPerMinuteLimit());
    }
}
