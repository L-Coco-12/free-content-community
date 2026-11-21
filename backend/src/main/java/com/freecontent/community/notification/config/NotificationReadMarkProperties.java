package com.freecontent.community.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.notification.read")
public record NotificationReadMarkProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public NotificationReadMarkProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("通知标记已读限流配置不合法");
        }
    }
}
