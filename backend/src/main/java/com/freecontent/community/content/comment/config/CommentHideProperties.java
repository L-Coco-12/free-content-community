package com.freecontent.community.content.comment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.comment-hide")
public record CommentHideProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public CommentHideProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("评论隐藏限流配置不合法");
        }
    }
}
