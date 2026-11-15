package com.freecontent.community.content.workspace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.draft-creation")
public record ContentDraftCreationProperties(int ipPerMinuteLimit, int userHourlyLimit, String redisKeyPrefix) {
    public ContentDraftCreationProperties {
        if (ipPerMinuteLimit < 1 || userHourlyLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("新建草稿限流配置不合法");
        }
    }
}
