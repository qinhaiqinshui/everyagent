package dev.everyagent.plugin.imagevision;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.spi.FileReference;
import dev.everyagent.plugin.api.spi.FileReferenceContext;
import dev.everyagent.plugin.api.spi.FileReferenceHandler;
import dev.everyagent.plugin.api.spi.FileReferenceResult;
import dev.everyagent.plugin.api.spi.WorkspaceSandbox;

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
 * 图片引用处理器 —— image-vision 插件对 {@link FileReferenceHandler} 的唯一实现。
 *
 * <p>处理流程（{@code process()}）：
 * <ol>
 *   <li>解析文件路径：workspace_file 走工作区根路径沙箱
 *       （{@code workspaces().sandboxFor(...).resolveExisting(...)}，词法 + realpath 双校验）；
 *       external_file 直接 realpath 解析（用户 {@code @} 引用行为本身即视为已授权，
 *       无需额外授权链）；异常则<b>抛异常终止任务</b>；</li>
 *   <li>压缩：原图 base64 后 ≤ 上限直接用；超限经 {@link ImageCompressor} 逐步缩放；</li>
 *   <li>转 base64 dataURL → 返回 {@code replacementText="（附图：{displayPath}）"} +
 *       {@code attachments=[{type:"image", dataUrl, fileName, mimeType}]}。
 *       displayPath 为工作区相对路径（工作区内）或完整绝对路径（工作区外）。</li>
 * </ol>
 *
 * <p>压到最小仍超限 / 无法解码 / 读取失败 → <b>抛异常终止任务</b>。
 * 降级为纯路径文本没有意义：AI 无法处理裸路径，任务照样出错；
 * 不如 fail-fast，让用户直接看到明确的失败原因。
 */
public class ImageReferenceHandler implements FileReferenceHandler {

    /** 读入内存的硬上限（256MB）：超出直接按「过大」处理，防超大文件撑爆内存。 */
    private static final long HARD_READ_CAP_BYTES = 256L * 1024 * 1024;

    private final WorkerServices services;
    private final ImageVisionSettings settings;

    public ImageReferenceHandler(WorkerServices services, ImageVisionSettings settings) {
        this.services = services;
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
    public FileReferenceResult process(FileReferenceContext ctx, FileReference ref) {
        if (!settings.enabled()) {
            return null; // 注入开关关闭：放弃处理，保持纯路径文本
        }
        Path file = resolveFile(ctx, ref);
        return buildResult(file, ref);
    }

    // ---- 路径解析 ----

    /**
     * 解析目标文件路径。用户 {@code @} 引用行为本身即视为已授权，无需额外授权链。
     *
     * @return 可读取的真实路径
     * @throws RuntimeException 越界/不存在/非普通文件
     */
    private Path resolveFile(FileReferenceContext ctx, FileReference ref) {
        if (!ref.external()) {
            // workspace_file：jailed 到工作区根（词法 + realpath 双校验，防符号链接逃逸）。
            if (ctx.workspaceRoot() == null) {
                throw new RuntimeException("工作区根不可用，无法解析图片引用: " + ref.path());
            }
            try {
                WorkspaceSandbox sandbox = services.workspaces().sandboxFor(ctx.workspaceRoot());
                Path real = sandbox.resolveExisting(ref.path());
                if (!Files.isRegularFile(real)) {
                    throw new RuntimeException("图片引用不是普通文件: " + ref.path());
                }
                return real;
            } catch (IOException | RuntimeException e) {
                throw new RuntimeException("工作区图片解析失败: " + ref.path() + " - " + e.getMessage(), e);
            }
        }
        // external_file：用户 @ 引用即授权，直接 realpath 解析。
        Path real;
        try {
            real = Path.of(ref.fullPath().trim()).toRealPath();
        } catch (IOException | RuntimeException e) {
            throw new RuntimeException("外部图片已失效: " + ref.fullPath(), e);
        }
        if (!Files.isRegularFile(real)) {
            throw new RuntimeException("外部引用不是普通文件: " + ref.fullPath());
        }
        return real;
    }

    // ---- 读取 / 压缩 / 组装结果 ----

    /**
     * 用于 replacementText 的展示路径：工作区内用相对路径，工作区外用完整绝对路径。
     */
    private static String displayPath(FileReference ref) {
        return ref.external() ? ref.fullPath() : ref.path();
    }

    private FileReferenceResult buildResult(Path file, FileReference ref) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new RuntimeException("无法读取图片大小: " + displayPath(ref), e);
        }
        if (size > HARD_READ_CAP_BYTES) {
            throw new RuntimeException(
                    "图片超过读入硬上限(256MB): " + displayPath(ref) + " (" + size + " bytes)");
        }
        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new RuntimeException("读取图片失败: " + displayPath(ref), e);
        }
        byte[] payload;
        String mimeType;
        if (ImageCompressor.base64Length(raw.length) <= settings.maxBase64Bytes()) {
            payload = raw; // 原图未超限：直接用，保留原始格式
            mimeType = mimeTypeOf(ref.fileName());
        } else {
            ImageCompressor.Compression compressed = ImageCompressor.compress(file, settings.maxBase64Bytes());
            switch (compressed.status()) {
                case OK -> {
                    payload = compressed.data();
                    mimeType = compressed.mimeType();
                }
                case TOO_LARGE ->
                        throw new RuntimeException(
                                "图片过大，压缩到最小尺寸仍超限: " + displayPath(ref) + "，请缩小后重试");
                default ->
                        throw new RuntimeException("图片无法解析: " + displayPath(ref));
            }
        }
        String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(payload);
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("type", "image");
        attachment.put("dataUrl", dataUrl);
        attachment.put("fileName", ref.fileName());
        attachment.put("mimeType", mimeType);
        return new FileReferenceResult("（附图：" + displayPath(ref) + "）", List.of(attachment));
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
