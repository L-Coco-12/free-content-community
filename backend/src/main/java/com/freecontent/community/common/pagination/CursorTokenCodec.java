package com.freecontent.community.common.pagination;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.json.JsonMapper;

/** 加密并认证游标，将令牌限制在指定接口及排序规则内。 */
public final class CursorTokenCodec {
    private static final String PREFIX = "v1.";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private final SecretKeySpec key;
    private final byte[] purpose;
    private final JsonMapper json;
    private final SecureRandom random = new SecureRandom();

    public CursorTokenCodec(String keyBase64, String purpose, JsonMapper json) {
        this.key = new SecretKeySpec(Base64.getDecoder().decode(keyBase64), "AES");
        this.purpose = purpose.getBytes(StandardCharsets.UTF_8);
        this.json = json;
    }

    public String encode(Object payload) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, nonce).doFinal(json.writeValueAsBytes(payload));
            return PREFIX + ENCODER.encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "游标服务暂不可用");
        }
    }

    public <T> T decode(String token, Class<T> type) {
        try {
            if (!token.matches("v1\\.[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            String encoded = token.substring(PREFIX.length());
            byte[] packed = Base64.getUrlDecoder().decode(encoded);
            if (packed.length <= NONCE_BYTES + TAG_BITS / Byte.SIZE || !ENCODER.encodeToString(packed).equals(encoded)) {
                throw new IllegalArgumentException();
            }
            byte[] nonce = Arrays.copyOf(packed, NONCE_BYTES);
            byte[] payload = cipher(Cipher.DECRYPT_MODE, nonce).doFinal(packed, NONCE_BYTES, packed.length - NONCE_BYTES);
            return json.readValue(payload, type);
        } catch (GeneralSecurityException | RuntimeException ex) {
            throw new BusinessException(ErrorCode.CURSOR_INVALID);
        }
    }

    private Cipher cipher(int mode, byte[] nonce) throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(purpose);
        return cipher;
    }
}
