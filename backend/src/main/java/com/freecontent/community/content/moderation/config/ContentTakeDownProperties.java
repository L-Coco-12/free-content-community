package com.freecontent.community.content.moderation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.take-down")
public record ContentTakeDownProperties(int ipPerMinuteLimit, int userHourlyLimit, String redisKeyPrefix) {
    public ContentTakeDownProperties {
        if (ipPerMinuteLimit < 1 || userHourlyLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("强制下架限流配置不合法");
        }
    }
}
