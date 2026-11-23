package com.freecontent.community.content.comment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.comment")
public record CommentWriteProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public CommentWriteProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("评论写入限流配置不合法");
        }
    }
}
