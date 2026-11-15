package com.freecontent.community.content.workspace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.publication-withdrawal")
public record PublicationWithdrawalProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public PublicationWithdrawalProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) throw new IllegalArgumentException("公开撤回限流配置不合法");
    }
}
