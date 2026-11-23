package com.freecontent.community.content.comment.controller;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.comment.dto.CreateCommentRequest;
import com.freecontent.community.content.comment.dto.HideCommentRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

@ControllerAdvice(assignableTypes = {CommentCreationController.class, CommentHideController.class})
public class CommentRequestBodyAdvice extends RequestBodyAdviceAdapter {
    // 包含 JSON 转义时，一个合规的 2,000 码点正文最多约 24 KB。
    private static final int MAX_BYTES = 32 * 1024;

    @Override
    public boolean supports(MethodParameter parameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType == CreateCommentRequest.class || targetType == HideCommentRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type targetType,
                                            Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        if (input.getHeaders().getContentLength() > MAX_BYTES) throw tooLarge();
        // 分块请求也按实际读取字节限制，由默认 JSON 转换器继续负责语法和字段类型。
        byte[] bytes = input.getBody().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw tooLarge();
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
            @Override public HttpHeaders getHeaders() { return input.getHeaders(); }
        };
    }

    private static BusinessException tooLarge() {
        return new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "评论请求体不能超过32 KiB");
    }
}
