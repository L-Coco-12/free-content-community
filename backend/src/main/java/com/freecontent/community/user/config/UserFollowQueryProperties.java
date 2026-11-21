package com.freecontent.community.user.config;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.user.follow-query")
public record UserFollowQueryProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix,
                                        int cursorTtlSeconds, String cursorKeyBase64) {
    public UserFollowQueryProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("关注列表限流配置不合法");
        }
        if (cursorTtlSeconds < 1 || cursorTtlSeconds > 86400) {
            throw new IllegalArgumentException("关注列表游标有效期必须在1到86400秒之间");
        }
        try {
            if (cursorKeyBase64 == null || Base64.getDecoder().decode(cursorKeyBase64).length != 32) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("关注列表游标必须配置32字节Base64密钥");
        }
    }

    @Override
    public String toString() {
        return "UserFollowQueryProperties[cursorTtlSeconds=" + cursorTtlSeconds + ", key=REDACTED]";
    }
}
