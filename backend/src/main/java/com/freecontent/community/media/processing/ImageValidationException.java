package com.freecontent.community.media.processing;

import java.io.IOException;

/** 只暴露固定业务原因，不将解码器或存储内部错误发送给用户。 */
public class ImageValidationException extends IOException {
    private final String code;
    public ImageValidationException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
