package com.freecontent.community.notification.config;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.notification.cursor")
public record NotificationCursorProperties(int ttlSeconds, String keyBase64) {
    public NotificationCursorProperties {
        if (ttlSeconds < 1 || ttlSeconds > 86400) {
            throw new IllegalArgumentException("通知游标有效期必须在1到86400秒之间");
        }
        try {
            if (keyBase64 == null || Base64.getDecoder().decode(keyBase64).length != 32) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("通知游标必须配置32字节Base64密钥");
        }
    }

    @Override
    public String toString() {
        return "NotificationCursorProperties[ttlSeconds=" + ttlSeconds + ", key=REDACTED]";
    }
}
