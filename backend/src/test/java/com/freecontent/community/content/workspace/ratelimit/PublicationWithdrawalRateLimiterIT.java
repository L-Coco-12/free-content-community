package com.freecontent.community.content.workspace.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.workspace.config.PublicationWithdrawalProperties;
import com.freecontent.community.content.review.config.ReviewCancellationProperties;
import com.freecontent.community.content.review.ratelimit.ReviewCancellationRateLimiter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class PublicationWithdrawalRateLimiterIT {
    @Autowired private StringRedisTemplate redis;

    @Test
    void minuteWindowsAreSharedAcrossWithdrawalsAndSeparateFromReviewCancellation() {
        String prefix = "test:withdraw-budget:" + UUID.randomUUID() + ":";
        try {
            var limiter = new PublicationWithdrawalRateLimiter(redis, new PublicationWithdrawalProperties(3, 2, prefix));
            limiter.acquire("one", "source"); limiter.acquire("one", "source");
            assertEquals(ErrorCode.RATE_LIMITED, assertThrows(BusinessException.class, () -> limiter.acquire("one", "other-source")).errorCode());
            assertNull(redis.opsForValue().get(prefix + "{content-publication-withdrawal}:source:" + TokenDigest.sha256("other-source")));
            limiter.acquire("two", "source");
            var error = assertThrows(BusinessException.class, () -> limiter.acquire("three", "source"));
            assertEquals(ErrorCode.RATE_LIMITED, error.errorCode()); assertTrue(error.retryAfterSeconds() > 0);
            long ttl = redis.getExpire(prefix + "{content-publication-withdrawal}:user:" + TokenDigest.sha256("one"));
            assertTrue(ttl > 0 && ttl <= 60);
            new ReviewCancellationRateLimiter(redis, new ReviewCancellationProperties(3, 2, prefix)).acquire("one", "source");
        } finally { clear(prefix); }
    }

    @Test
    void concurrentCallersCannotOverspendTheSourceWindow() throws Exception {
        String prefix = "test:withdraw-concurrent:" + UUID.randomUUID() + ":";
        var pool = Executors.newFixedThreadPool(8);
        try {
            var limiter = new PublicationWithdrawalRateLimiter(redis, new PublicationWithdrawalProperties(5, 100, prefix));
            var start = new CountDownLatch(1); List<Future<Boolean>> attempts = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                String actor = "actor-" + index;
                attempts.add(pool.submit(() -> {
                    start.await();
                    try { limiter.acquire(actor, "source"); return true; }
                    catch (BusinessException error) { assertEquals(ErrorCode.RATE_LIMITED, error.errorCode()); return false; }
                }));
            }
            start.countDown(); int accepted = 0;
            for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals(5, accepted);
        } finally { pool.shutdownNow(); clear(prefix); }
    }

    private void clear(String prefix) {
        var keys = redis.keys(prefix + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
}
