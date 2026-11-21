package com.freecontent.community.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.user.role-query")
public record RoleQueryProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public RoleQueryProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("角色查询限流配置不合法");
        }
    }
}
