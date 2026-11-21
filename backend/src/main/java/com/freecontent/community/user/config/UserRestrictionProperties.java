package com.freecontent.community.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.user.restriction")
public record UserRestrictionProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public UserRestrictionProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("账号限制限流配置不合法");
        }
    }
}
