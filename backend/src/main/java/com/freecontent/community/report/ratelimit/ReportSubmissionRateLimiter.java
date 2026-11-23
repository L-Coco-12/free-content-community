package com.freecontent.community.report.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.report.config.ReportSubmissionProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReportSubmissionRateLimiter {
    private final RedisRateLimitExecutor executor;
    private final ReportSubmissionProperties properties;

    public ReportSubmissionRateLimiter(StringRedisTemplate redis, ReportSubmissionProperties properties) {
        this.executor = new RedisRateLimitExecutor(redis);
        this.properties = properties;
    }

    public void acquire(String actor, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{report-submission}:";
        executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,
                List.of(prefix + "user:" + TokenDigest.sha256(actor), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                "举报提交过于频繁", "举报限流服务暂不可用", properties.userPerMinuteLimit(), properties.ipPerMinuteLimit());
    }
}
