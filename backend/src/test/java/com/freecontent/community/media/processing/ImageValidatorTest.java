package com.freecontent.community.media.processing;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ImageValidatorTest {
    static byte[] image(String format, int width, int height) throws Exception {
        var out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out));
        return out.toByteArray();
    }

    @Test
    void validPngReturnsDecodedDimensionsAndMime() throws Exception {
        var result = new ImageValidator().validate(image("png", 3, 2), "image/png", null);
        assertEquals("image/png", result.contentType());
        assertEquals(3, result.width());
        assertEquals(2, result.height());
        assertEquals(1, result.frames());
    }

    @Test
    void jpegDisguisedAsPngIsRejected() throws Exception {
        assertThrows(Exception.class, () -> new ImageValidator().validate(image("jpeg", 2, 2), "image/png", null));
    }

    @Test
    void pixelBudgetIsCheckedBeforeDecode() throws Exception {
        var validator = new ImageValidator(1, 8192, 25000000, 100, 100000000);
        assertThrows(Exception.class, () -> validator.validate(image("png", 2, 1), "image/png", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpeg", "gif"})
    void truncatedImageIsRejected(String format) throws Exception {
        byte[] bytes = image(format, 3, 2);
        assertThrows(Exception.class, () -> new ImageValidator().validate(Arrays.copyOf(bytes, bytes.length - 3),
                "image/" + format, null));
    }

    @Test
    void checksumMismatchIsRejected() throws Exception {
        assertThrows(Exception.class, () -> new ImageValidator().validate(image("png", 2, 1), "image/png", "0".repeat(64)));
    }

    static byte[] animation(int frames) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = ImageIO.createImageOutputStream(bytes)) {
            var writer = ImageIO.getImageWritersByFormatName("gif").next();
            try {
                writer.setOutput(out); writer.prepareWriteSequence(null);
                for (int i = 0; i < frames; i++) writer.writeToSequence(new javax.imageio.IIOImage(
                        new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), null, null), null);
                writer.endWriteSequence();
            } finally { writer.dispose(); }
        }
        return bytes.toByteArray();
    }

    @Test
    void gifDecodesEveryFrameAndEnforcesBothBudgets() throws Exception {
        byte[] gif = animation(3);
        assertEquals(3, new ImageValidator().validate(gif, "image/gif", null).frames());
        assertThrows(Exception.class, () -> new ImageValidator(8192,8192,25000000,2,100000000).validate(gif,"image/gif",null));
        assertThrows(Exception.class, () -> new ImageValidator(8192,8192,25000000,100,12).validate(gif,"image/gif",null));
    }

    @ParameterizedTest
    @ValueSource(strings={"lossy.webp","lossless-alpha.webp","animated.webp"})
    void webpFormatsAreActuallyDecoded(String name) throws Exception {
        byte[] bytes;
        try (var in=getClass().getResourceAsStream("/media/"+name)) { bytes=in.readAllBytes(); }
        var result=new ImageValidator().validate(bytes,"image/webp",null);
        assertEquals("image/webp",result.contentType());
        assertEquals(4,result.width()); assertEquals(4,result.height());
        assertEquals(name.equals("animated.webp")?2:1,result.frames());
        if (name.equals("animated.webp")) {
            assertThrows(ImageValidationException.class,()->new ImageValidator(8192,8192,25000000,1,100000000).validate(bytes,"image/webp",null));
        }
    }
}
