package dev.everyagent.plugin.imagevision;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.spi.FileReadHandler;
import dev.everyagent.plugin.api.spi.FileReadResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 图片 read_file 接管处理器 —— image-vision 插件对 {@link FileReadHandler} 的唯一实现。
 * 图片处理逻辑自原 ImageReferenceHandler 迁移而来（@ 引用不再自动注入附件：与文本文件
 * 一致只留路径文本，AI 需要看图时自己调 {@code read_file}，由本处理器接管注入）。
 *
 * <p>处理流程（{@code read()}）：
 * <ol>
 *   <li>读文件：超过 256MB 硬上限直接抛异常（防超大文件撑爆内存）；</li>
 *   <li>压缩：原图 base64 后 ≤ 上限直接用（保留原始格式，mimeType 按扩展名推断）；
 *       超限经 {@link ImageCompressor} 逐步缩放编码；</li>
 *   <li>转 base64 dataURL → 返回 {@code text="已读取图片：…"} +
 *       {@code attachments=[{type:"image", dataUrl, fileName, mimeType}]}：
 *       {@code text} 由核心 FileTools 回灌 AI，attachments 由核心合并进任务
 *       metadata.attachments 并经 FileAttachmentAdvisor 注入模型。</li>
 * </ol>
 *
 * <p>路径授权解析在核心完成（PermissionGate READ 档 + 工作区沙箱 jail），本处理器
 * 直接读传入的 {@code file}，<b>不自行解析路径</b>（防绕过授权，契约见 FileReadHandler javadoc）。
 *
 * <p>压到最小仍超限 / 无法解码 / 读取失败 → <b>抛异常按工具失败回灌 AI</b>（fail-fast）。
 * 图片无法解码或过大时降级为乱码文本没有意义，不如让 AI 明确看到失败原因。
 */
public class ImageReadHandler implements FileReadHandler {

    /** 读入内存的硬上限（256MB）：超出直接按「过大」处理，防超大文件撑爆内存。 */
    private static final long HARD_READ_CAP_BYTES = 256L * 1024 * 1024;

    private final ImageVisionSettings settings;

    public ImageReadHandler(ImageVisionSettings settings) {
        this.settings = settings;
    }

    @Override
    public String pluginId() {
        return ImageVisionPlugin.ID;
    }

    @Override
    public Set<String> extensions() {
        return settings.extensions();
    }

    @Override
    public FileReadResult read(ExecContext ctx, String path, Path file) {
        if (!settings.enabled()) {
            return null; // 注入开关关闭：放弃接管，回落核心默认文本读取
        }
        return buildResult(path, file);
    }

    // ---- 读取 / 压缩 / 组装结果 ----

    private FileReadResult buildResult(String path, Path file) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new RuntimeException("无法读取图片大小: " + path, e);
        }
        if (size > HARD_READ_CAP_BYTES) {
            throw new RuntimeException(
                    "图片超过读入硬上限(256MB): " + path + " (" + size + " bytes)");
        }
        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new RuntimeException("读取图片失败: " + path, e);
        }
        String fileName = fileNameOf(path);
        byte[] payload;
        String mimeType;
        if (ImageCompressor.base64Length(raw.length) <= settings.maxBase64Bytes()) {
            payload = raw; // 原图未超限：直接用，保留原始格式
            mimeType = mimeTypeOf(fileName);
        } else {
            ImageCompressor.Compression compressed = ImageCompressor.compress(file, settings.maxBase64Bytes());
            switch (compressed.status()) {
                case OK -> {
                    payload = compressed.data();
                    mimeType = compressed.mimeType();
                }
                case TOO_LARGE ->
                        throw new RuntimeException(
                                "图片过大，压缩到最小尺寸仍超限: " + path + "，请缩小后重试");
                default ->
                        throw new RuntimeException("图片无法解析: " + path);
            }
        }
        String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(payload);
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("type", "image");
        attachment.put("dataUrl", dataUrl);
        attachment.put("fileName", fileName);
        attachment.put("mimeType", mimeType);
        return new FileReadResult("已读取图片：" + path + "（已注入为图像附件，可直接分析其内容）",
                List.of(attachment));
    }

    /** 取 path 尾段作为附件 fileName（纯字符串切分，兼容 / 与 \ 两种分隔符）。 */
    private static String fileNameOf(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        return idx >= 0 ? normalized.substring(idx + 1) : normalized;
    }

    /** 按扩展名推断 mimeType；未知兜底 image/png。 */
    private static String mimeTypeOf(String fileName) {
        String ext = "";
        if (fileName != null) {
            int idx = fileName.lastIndexOf('.');
            if (idx >= 0) {
                ext = fileName.substring(idx).toLowerCase(Locale.ROOT);
            }
        }
        return switch (ext) {
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".bmp" -> "image/bmp";
            default -> "image/png";
        };
    }
}
