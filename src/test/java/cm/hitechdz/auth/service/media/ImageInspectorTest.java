package cm.hitechdz.auth.service.media;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ImageInspectorTest {

    static byte[] image(String format, int w, int h) throws Exception {
        int type = format.equals("gif") || format.equals("bmp") ? BufferedImage.TYPE_BYTE_INDEXED : BufferedImage.TYPE_INT_RGB;
        BufferedImage img = new BufferedImage(w, h, type);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, format, out)).as("writer " + format).isTrue();
        return out.toByteArray();
    }

    /** En-tête WebP VP8X minimal (canevas w×h). */
    static byte[] webpVp8x(int w, int h) {
        byte[] b = new byte[40];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        System.arraycopy("WEBPVP8X".getBytes(StandardCharsets.US_ASCII), 0, b, 8, 8);
        b[16] = 10;
        int wm = w - 1, hm = h - 1;
        b[24] = (byte) wm; b[25] = (byte) (wm >> 8); b[26] = (byte) (wm >> 16);
        b[27] = (byte) hm; b[28] = (byte) (hm >> 8); b[29] = (byte) (hm >> 16);
        return b;
    }

    static byte[] webpVp8l(int w, int h) {
        byte[] b = new byte[40];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        System.arraycopy("WEBPVP8L".getBytes(StandardCharsets.US_ASCII), 0, b, 8, 8);
        b[20] = 0x2F;
        long bits = (w - 1) | ((long) (h - 1) << 14);
        b[21] = (byte) bits; b[22] = (byte) (bits >> 8); b[23] = (byte) (bits >> 16); b[24] = (byte) (bits >> 24);
        return b;
    }

    static byte[] webpVp8(int w, int h) {
        byte[] b = new byte[40];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        System.arraycopy("WEBPVP8 ".getBytes(StandardCharsets.US_ASCII), 0, b, 8, 8);
        b[23] = (byte) 0x9D; b[24] = 0x01; b[25] = 0x2A;
        b[26] = (byte) w; b[27] = (byte) (w >> 8); b[28] = (byte) h; b[29] = (byte) (h >> 8);
        return b;
    }

    @Test
    void reads_real_format_and_dimensions() throws Exception {
        assertThat(ImageInspector.inspect(image("png", 1600, 900)).info())
                .isEqualTo(new ImageInspector.Info(ImageInspector.Format.PNG, 1600, 900));
        assertThat(ImageInspector.inspect(image("jpg", 800, 600)).info())
                .isEqualTo(new ImageInspector.Info(ImageInspector.Format.JPEG, 800, 600));
        assertThat(ImageInspector.inspect(image("gif", 640, 480)).info())
                .isEqualTo(new ImageInspector.Info(ImageInspector.Format.GIF, 640, 480));
        assertThat(ImageInspector.inspect(webpVp8x(1600, 900)).info())
                .isEqualTo(new ImageInspector.Info(ImageInspector.Format.WEBP, 1600, 900));
        assertThat(ImageInspector.inspect(webpVp8l(800, 600)).info())
                .isEqualTo(new ImageInspector.Info(ImageInspector.Format.WEBP, 800, 600));
        assertThat(ImageInspector.inspect(webpVp8(1200, 675)).info())
                .isEqualTo(new ImageInspector.Info(ImageInspector.Format.WEBP, 1200, 675));
    }

    @Test
    void names_refused_formats() throws Exception {
        assertThat(ImageInspector.inspect(image("bmp", 800, 600)).rejectedFormat()).isEqualTo("BMP");
        assertThat(ImageInspector.inspect(image("tiff", 800, 600)).rejectedFormat()).isEqualTo("TIFF");
        assertThat(ImageInspector.inspect("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes()).rejectedFormat())
                .isEqualTo("SVG");
        assertThat(ImageInspector.inspect("<?xml version=\"1.0\"?><svg></svg>".getBytes()).rejectedFormat()).isEqualTo("SVG");
        byte[] heic = new byte[32];
        System.arraycopy("\0\0\0\u0018ftypheic".getBytes(StandardCharsets.ISO_8859_1), 0, heic, 0, 12);
        assertThat(ImageInspector.inspect(heic).rejectedFormat()).isEqualTo("HEIC");
        ImageInspector.Result text = ImageInspector.inspect("ceci n'est pas une image".getBytes());
        assertThat(text.accepted()).isFalse();
        assertThat(text.rejectedFormat()).isNull();
        // Extension trompeuse : seul le contenu compte
        assertThat(ImageInspector.inspect(new byte[4]).accepted()).isFalse();
    }
}
