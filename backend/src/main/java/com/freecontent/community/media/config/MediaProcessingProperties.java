package com.freecontent.community.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.media.processing")
public record MediaProcessingProperties(boolean enabled, int pollDelayMillis, int leaseSeconds, int maxAttempts,
        int retryBaseSeconds, int orphanGraceSeconds, int maxWidth, int maxHeight, long maxFramePixels,
        int maxFrames, long maxTotalPixels, int decodeTimeoutSeconds) {
    public MediaProcessingProperties {
        if (pollDelayMillis<1 || leaseSeconds<60 || maxAttempts<1 || maxAttempts>20 || retryBaseSeconds<1
                || orphanGraceSeconds<60 || maxWidth<1 || maxHeight<1 || maxFramePixels<1 || maxFrames<1
                || maxTotalPixels<1 || decodeTimeoutSeconds<1 || (long)decodeTimeoutSeconds+45>=leaseSeconds)
            throw new IllegalArgumentException("媒体处理配置不合法，租约须覆盖解码与存储超时");
    }
}
