package com.freecontent.community.content.workspace.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.workspace.config.ContentCreationProperties;
import com.freecontent.community.content.workspace.config.ContentDraftCreationProperties;
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
class ContentDraftCreationRateLimiterIT {
    @Autowired private StringRedisTemplate redis;

    @Test
    void sourceMinuteAndUserHourWindowsAreIndependentOfContentCreation() {
        String prefix = "test:draft-budget:" + UUID.randomUUID() + ":";
        try {
            var limiter = new ContentDraftCreationRateLimiter(redis, new ContentDraftCreationProperties(3, 2, prefix));
            limiter.acquire("one", "source");
            limiter.acquire("one", "source");
            var userError = assertThrows(BusinessException.class, () -> limiter.acquire("one", "other-source"));
            assertEquals(ErrorCode.RATE_LIMITED, userError.errorCode());
            assertTrue(userError.retryAfterSeconds() > 0);
            assertNull(redis.opsForValue().get(prefix + "{content-draft-create}:source:" + TokenDigest.sha256("other-source")));
            limiter.acquire("two", "source");
            assertEquals(ErrorCode.RATE_LIMITED, assertThrows(BusinessException.class, () -> limiter.acquire("three", "source")).errorCode());
            long sourceTtl = redis.getExpire(prefix + "{content-draft-create}:source:" + TokenDigest.sha256("source"));
            long userTtl = redis.getExpire(prefix + "{content-draft-create}:user:" + TokenDigest.sha256("one"));
            assertTrue(sourceTtl > 0 && sourceTtl <= 60);
            assertTrue(userTtl > 60 && userTtl <= 3600);
            new ContentCreationRateLimiter(redis, new ContentCreationProperties(3, 2, prefix)).acquire("one", "source");
        } finally { clear(prefix); }
    }

    @Test
    void concurrentRequestsCannotOverspendTheSourceWindow() throws Exception {
        String prefix = "test:draft-budget-concurrent:" + UUID.randomUUID() + ":";
        var pool = Executors.newFixedThreadPool(8);
        try {
            var limiter = new ContentDraftCreationRateLimiter(redis, new ContentDraftCreationProperties(5, 100, prefix));
            var start = new CountDownLatch(1);
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                String actor = "actor-" + index;
                attempts.add(pool.submit(() -> {
                    start.await();
                    try { limiter.acquire(actor, "source"); return true; }
                    catch (BusinessException error) { assertEquals(ErrorCode.RATE_LIMITED, error.errorCode()); return false; }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals(5, accepted);
            assertEquals("5", redis.opsForValue().get(prefix + "{content-draft-create}:source:" + TokenDigest.sha256("source")));
        } finally { pool.shutdownNow(); clear(prefix); }
    }

    private void clear(String prefix) {
        var keys = redis.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
}
