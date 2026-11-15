package com.freecontent.community.content.workspace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.restoration")
public record ContentRestorationProperties(int ipPerMinuteLimit, int userHourlyLimit, String redisKeyPrefix) {
    public ContentRestorationProperties {
        if (ipPerMinuteLimit < 1 || userHourlyLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("恢复内容限流配置不合法");
        }
    }
}
