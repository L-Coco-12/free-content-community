package com.freecontent.community.content.comment.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.content.comment.config.PublicCommentQueryProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class PublicCommentRateLimiter {
    private final RedisRateLimitExecutor executor;
    private final PublicCommentQueryProperties properties;

    public PublicCommentRateLimiter(StringRedisTemplate redis, PublicCommentQueryProperties properties) {
        this.executor = new RedisRateLimitExecutor(redis);
        this.properties = properties;
    }

    public void acquire(String viewer, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{content-comment-query}:";
        String source = prefix + "source:" + TokenDigest.sha256(sourceIp);
        String limited = "公开评论查询过于频繁";
        String unavailable = "公开评论限流服务暂不可用";
        if (viewer == null) {
            executor.acquire(Policy.SOURCE_MINUTE, List.of(source), limited, unavailable, properties.ipPerMinuteLimit());
        } else {
            executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,
                    List.of(prefix + "user:" + TokenDigest.sha256(viewer), source), limited, unavailable,
                    properties.userPerMinuteLimit(), properties.ipPerMinuteLimit());
        }
    }
}
