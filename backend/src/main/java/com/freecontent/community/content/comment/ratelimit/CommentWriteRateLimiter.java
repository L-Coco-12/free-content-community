package com.freecontent.community.content.comment.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.content.comment.config.CommentWriteProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class CommentWriteRateLimiter {
    private final RedisRateLimitExecutor executor;
    private final CommentWriteProperties properties;

    public CommentWriteRateLimiter(StringRedisTemplate redis, CommentWriteProperties properties) {
        this.executor = new RedisRateLimitExecutor(redis);
        this.properties = properties;
    }

    public void acquire(String actor, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{content-comment}:";
        executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,
                List.of(prefix + "user:" + TokenDigest.sha256(actor), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                "评论操作过于频繁", "评论限流服务暂不可用", properties.userPerMinuteLimit(), properties.ipPerMinuteLimit());
    }
}
