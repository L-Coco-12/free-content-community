package com.freecontent.community.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.user.role-update")
public record UserRoleUpdateProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public UserRoleUpdateProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("角色变更限流配置不合法");
        }
    }
}
