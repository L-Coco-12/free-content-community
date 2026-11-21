package com.freecontent.community.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.notification.query")
public record NotificationQueryProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public NotificationQueryProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("通知查询限流配置不合法");
        }
    }
}
