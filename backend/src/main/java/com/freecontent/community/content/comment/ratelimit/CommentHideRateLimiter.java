package com.freecontent.community.content.comment.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.content.comment.config.CommentHideProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class CommentHideRateLimiter {
    private final RedisRateLimitExecutor executor;
    private final CommentHideProperties properties;

    public CommentHideRateLimiter(StringRedisTemplate redis, CommentHideProperties properties) {
        this.executor = new RedisRateLimitExecutor(redis);
        this.properties = properties;
    }

    public void acquire(String operatorId, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{content-comment-hide}:";
        executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,
                List.of(prefix + "user:" + TokenDigest.sha256(operatorId), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                "评论隐藏过于频繁", "评论隐藏限流服务暂不可用", properties.userPerMinuteLimit(), properties.ipPerMinuteLimit());
    }
}
