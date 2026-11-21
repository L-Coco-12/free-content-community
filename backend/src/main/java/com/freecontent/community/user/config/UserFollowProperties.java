package com.freecontent.community.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.user.follow")
public record UserFollowProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public UserFollowProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("关注限流配置不合法");
        }
    }
}
