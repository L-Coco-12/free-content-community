package com.freecontent.community.media.processing;

import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.CRC32;

/** 解码器读取实际文件内容，不信任扩展名或上传请求中的 MIME。 */
@Component
public class ImageValidator {
    private final int maxWidth, maxHeight, maxFrames;
    private final long maxFramePixels, maxTotalPixels;
    private final long decodeNanos;
    @org.springframework.beans.factory.annotation.Autowired
    public ImageValidator(com.freecontent.community.media.config.MediaProcessingProperties properties) {
        this(properties.maxWidth(),properties.maxHeight(),properties.maxFramePixels(),properties.maxFrames(),properties.maxTotalPixels(),properties.decodeTimeoutSeconds());
    }
    public ImageValidator() { this(8192, 8192, 25_000_000, 100, 100_000_000); }
    public ImageValidator(int maxWidth, int maxHeight, long maxFramePixels, int maxFrames, long maxTotalPixels) {
        this(maxWidth,maxHeight,maxFramePixels,maxFrames,maxTotalPixels,30);
    }
    private ImageValidator(int maxWidth, int maxHeight, long maxFramePixels, int maxFrames, long maxTotalPixels,int decodeSeconds) {
        this.maxWidth=maxWidth; this.maxHeight=maxHeight; this.maxFramePixels=maxFramePixels;
        this.maxFrames=maxFrames; this.maxTotalPixels=maxTotalPixels;
        this.decodeNanos=java.util.concurrent.TimeUnit.SECONDS.toNanos(decodeSeconds);
    }
    public record ValidatedImage(String contentType, int width, int height, int frames) { }

    public ValidatedImage validate(byte[] bytes, String declaredMime, String expectedSha256) throws IOException {
        long started=System.nanoTime();
        if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(sha256(bytes)))
            throw invalid("CHECKSUM_MISMATCH", "文件 SHA-256 与声明不符");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("INVALID_IMAGE", "无法识别图片或图片已损坏");
            var reader = readers.next();
            try {
                reader.setInput(input, false, false);
                reader.addIIOReadWarningListener((source, warning) -> { throw new IllegalArgumentException("decode warning"); });
                String mime = "image/" + reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!Set.of("image/png", "image/jpeg", "image/gif", "image/webp").contains(mime))
                    throw invalid("UNSUPPORTED_IMAGE_FORMAT", "暂不支持此图片格式");
                if (!mime.equals(declaredMime)) throw invalid("MEDIA_TYPE_MISMATCH", "图片实际格式与声明不符");
                requireCompleteContainer(bytes, mime);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (mime.equals("image/gif")) { width=u16(bytes,6); height=u16(bytes,8); }
                if (mime.equals("image/webp") && new String(bytes,12,4,java.nio.charset.StandardCharsets.US_ASCII).equals("VP8X")) {
                    width=1+u24(bytes,24); height=1+u24(bytes,27);
                }
                budget(width,height);
                long total=0; int frames=0;
                for (int i=0; ;i++) {
                    if (Thread.currentThread().isInterrupted()) throw invalid("PROCESSING_INTERRUPTED", "图片处理已中断");
                    int fw,fh;
                    try { fw=reader.getWidth(i); fh=reader.getHeight(i); }
                    catch (IndexOutOfBoundsException end) { break; }
                    if (i>=maxFrames) throw invalid("IMAGE_LIMIT_EXCEEDED", "图片帧数超出上限");
                    budget(fw,fh);
                    total += Math.max((long)width*height,(long)fw*fh);
                    if (total>maxTotalPixels) throw invalid("IMAGE_LIMIT_EXCEEDED", "图片累计像素超出上限");
                    var image=reader.read(i);
                    if (image==null) throw invalid("INVALID_IMAGE", "图片帧无法解码");
                    image.flush(); frames++;
                    if (System.nanoTime()-started>decodeNanos) throw invalid("IMAGE_LIMIT_EXCEEDED", "图片解码时间超出上限");
                    if (mime.equals("image/png") || mime.equals("image/jpeg")) break;
                }
                if (frames==0) throw invalid("INVALID_IMAGE", "图片没有可解码帧");
                return new ValidatedImage(mime,width,height,frames);
            } catch (ImageValidationException ex) { throw ex; }
            catch (IOException | RuntimeException ex) { throw invalid("INVALID_IMAGE", "图片损坏、截断或无法完整解码");
            } finally { reader.dispose(); }
        }
    }

    private void budget(int width,int height) throws ImageValidationException {
        if (width<1 || height<1 || width>maxWidth || height>maxHeight || (long)width*height>maxFramePixels)
            throw invalid("IMAGE_LIMIT_EXCEEDED", "图片宽高或单帧像素超出上限");
    }
    private static void requireCompleteContainer(byte[] bytes,String mime) throws ImageValidationException {
        int n=bytes.length;
        if (mime.equals("image/png")) {
            int p=8; boolean ended=false;
            while (p+12<=n) {
                long length=u32be(bytes,p);
                if (length>n-p-12) break;
                int count=(int)length;
                var crc=new CRC32(); crc.update(bytes,p+4,count+4);
                if (crc.getValue()!=u32be(bytes,p+8+count)) throw invalid("INVALID_IMAGE", "PNG 校验和错误");
                boolean last=bytes[p+4]=='I' && bytes[p+5]=='E' && bytes[p+6]=='N' && bytes[p+7]=='D';
                p+=count+12;
                if (last) { ended=count==0 && p==n; break; }
            }
            if (!ended) throw invalid("INVALID_IMAGE", "PNG 文件不完整");
        } else if (mime.equals("image/jpeg") && (n<2 || bytes[n-2]!=(byte)0xff || bytes[n-1]!=(byte)0xd9))
            throw invalid("INVALID_IMAGE", "JPEG 文件不完整");
        else if (mime.equals("image/gif") && (n<14 || bytes[n-1]!=0x3b))
            throw invalid("INVALID_IMAGE", "GIF 文件不完整");
        else if (mime.equals("image/webp") && (n<20 || u32le(bytes,4)+8!=n))
            throw invalid("INVALID_IMAGE", "WebP 文件不完整");
    }
    private static int u16(byte[] b,int p) { return (b[p]&255)|((b[p+1]&255)<<8); }
    private static int u24(byte[] b,int p) { return u16(b,p)|((b[p+2]&255)<<16); }
    private static long u32le(byte[] b,int p) { return Integer.toUnsignedLong(u24(b,p)|((b[p+3]&255)<<24)); }
    private static long u32be(byte[] b,int p) { return ((long)(b[p]&255)<<24)|((long)(b[p+1]&255)<<16)|((b[p+2]&255)<<8)|(b[p+3]&255); }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static ImageValidationException invalid(String code,String message) { return new ImageValidationException(code,message); }
}
