package dev.everyagent.plugin.imagevision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * 图片压缩器 —— 把超限图片逐步缩放编码到「base64 后 ≤ 上限」。
 *
 * <p>只用 {@link ImageIO} / {@link BufferedImage}（headless 安全），不碰 Swing/AWT 组件。
 * 缩放策略：每次缩 10%，最多 10 次，最小 100×100；编码格式：源图含 alpha → PNG
 * （保留透明通道），否则 JPEG（quality=0.85）。压到最小尺寸仍超限 → {@link Status#TOO_LARGE}；
 * 图片无法解码（如 JDK 无 webp 解码器）→ {@link Status#DECODE_FAILED}。
 */
public final class ImageCompressor {

    /** 压缩结果状态。 */
    public enum Status { OK, TOO_LARGE, DECODE_FAILED }

    /** 压缩结果。 */
    public record Compression(Status status, byte[] data, String mimeType) {

        static Compression ok(byte[] data, String mimeType) {
            return new Compression(Status.OK, data, mimeType);
        }

        static Compression of(Status status) {
            return new Compression(status, null, null);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ImageCompressor.class);

    /** 每次缩放比例（缩 10%）。 */
    private static final double SCALE_STEP = 0.9;

    /** 最多缩放次数。 */
    private static final int MAX_STEPS = 10;

    /** 最小尺寸（宽/高各自下限）。 */
    private static final int MIN_DIMENSION = 100;

    /** JPEG 编码质量。 */
    private static final float JPEG_QUALITY = 0.85f;

    private ImageCompressor() {
    }

    /**
     * 压缩图片到 base64 后 ≤ {@code maxBase64Bytes}。
     *
     * @param file           图片文件（已存在、已授权）
     * @param maxBase64Bytes base64 后长度上限（字节）
     * @return 压缩结果（OK 携带编码后字节与 mimeType；TOO_LARGE / DECODE_FAILED 无载荷）
     */
    public static Compression compress(Path file, long maxBase64Bytes) {
        BufferedImage src;
        try {
            src = ImageIO.read(file.toFile());
        } catch (IOException | RuntimeException e) {
            log.warn("[image-vision] 图片解码失败: {} - {}", file, e.toString());
            return Compression.of(Status.DECODE_FAILED);
        }
        if (src == null) {
            log.warn("[image-vision] 图片无可用解码器: {}", file);
            return Compression.of(Status.DECODE_FAILED);
        }
        boolean alpha = src.getColorModel().hasAlpha();
        BufferedImage current = src;
        for (int step = 0; step < MAX_STEPS; step++) {
            int w = Math.max(MIN_DIMENSION, (int) Math.round(current.getWidth() * SCALE_STEP));
            int h = Math.max(MIN_DIMENSION, (int) Math.round(current.getHeight() * SCALE_STEP));
            if (w == current.getWidth() && h == current.getHeight()) {
                break; // 已触最小尺寸，无法再缩
            }
            current = scale(current, w, h, alpha);
            byte[] encoded;
            try {
                encoded = encode(current, alpha);
            } catch (IOException | RuntimeException e) {
                log.warn("[image-vision] 图片编码失败: {} - {}", file, e.toString());
                return Compression.of(Status.DECODE_FAILED);
            }
            if (base64Length(encoded.length) <= maxBase64Bytes) {
                log.info("[image-vision] 图片压缩完成: {} 缩放 {} 次 → {}x{}, {} bytes({})",
                        file.getFileName(), step + 1, w, h, encoded.length,
                        alpha ? "image/png" : "image/jpeg");
                return Compression.ok(encoded, alpha ? "image/png" : "image/jpeg");
            }
        }
        log.warn("[image-vision] 图片压到最小尺寸仍超限: {}", file);
        return Compression.of(Status.TOO_LARGE);
    }

    /** base64 编码后的字符串长度（4 * ceil(n/3)）。 */
    public static long base64Length(long rawBytes) {
        return 4L * ((rawBytes + 2) / 3);
    }

    /** 双线性插值缩放（headless 安全的 BufferedImage 绘制）。 */
    private static BufferedImage scale(BufferedImage src, int w, int h, boolean alpha) {
        BufferedImage dst = new BufferedImage(w, h,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /** 编码：含 alpha → PNG；否则 JPEG（TYPE_INT_RGB 归一 + quality=0.85）。 */
    private static byte[] encode(BufferedImage img, boolean alpha) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (alpha) {
            ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        }
        BufferedImage rgb = img.getType() == BufferedImage.TYPE_INT_RGB ? img
                : toRgb(img);
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("JDK 无 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bos)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(out);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    /** 归一为 TYPE_INT_RGB（JPEG 编码器不支持部分自定义/索引色彩模型）。 */
    private static BufferedImage toRgb(BufferedImage img) {
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.drawImage(img, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }
}
