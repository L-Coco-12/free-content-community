package com.freecontent.community.report.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.report.config.ReportDecisionProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReportDecisionRateLimiter {
    private final RedisRateLimitExecutor executor;
    private final ReportDecisionProperties properties;

    public ReportDecisionRateLimiter(StringRedisTemplate redis, ReportDecisionProperties properties) {
        this.executor = new RedisRateLimitExecutor(redis);
        this.properties = properties;
    }

    public void acquire(String operator, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{report-decision}:";
        executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,
                List.of(prefix + "user:" + TokenDigest.sha256(operator), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                "举报处理过于频繁", "举报处理限流服务暂不可用", properties.userPerMinuteLimit(), properties.ipPerMinuteLimit());
    }
}
