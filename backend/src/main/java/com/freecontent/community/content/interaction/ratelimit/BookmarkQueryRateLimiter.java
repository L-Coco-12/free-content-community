package com.freecontent.community.content.interaction.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.content.interaction.config.BookmarkQueryProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class BookmarkQueryRateLimiter {
    private final RedisRateLimitExecutor executor;
    private final BookmarkQueryProperties properties;
    public BookmarkQueryRateLimiter(StringRedisTemplate redis,BookmarkQueryProperties properties){this.executor=new RedisRateLimitExecutor(redis);this.properties=properties;}
    public void acquire(String user,String sourceIp){
        String prefix=properties.redisKeyPrefix()+"{content-bookmark-query}:";
        executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,List.of(prefix+"user:"+TokenDigest.sha256(user),prefix+"source:"+TokenDigest.sha256(sourceIp)),
                "收藏列表查询过于频繁","收藏列表限流服务暂不可用",properties.userPerMinuteLimit(),properties.ipPerMinuteLimit());
    }
}
