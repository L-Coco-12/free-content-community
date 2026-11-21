package com.freecontent.community.content.browse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.following-feed")
public record FollowingFeedProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public FollowingFeedProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("关注流限流配置不合法");
        }
    }
}
