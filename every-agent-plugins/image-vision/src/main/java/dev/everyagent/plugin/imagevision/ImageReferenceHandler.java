package dev.everyagent.plugin.imagevision;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.interaction.AskOption;
import dev.everyagent.plugin.api.interaction.AskQuestion;
import dev.everyagent.plugin.api.interaction.AskResult;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.spi.FileReference;
import dev.everyagent.plugin.api.spi.FileReferenceContext;
import dev.everyagent.plugin.api.spi.FileReferenceHandler;
import dev.everyagent.plugin.api.spi.FileReferenceResult;
import dev.everyagent.plugin.api.spi.WorkspaceSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *   <li>经 jailed 路径读取文件：workspace_file 走工作区根路径沙箱
 *       （{@code workspaces().sandboxFor(...).resolveExisting(...)}，词法 + realpath 双校验）；
 *       external_file 先 realpath，再走授权链（与 PermissionGate 人工节点同款的
 *       authorization ask 三档选项），未授权/异常则<b>降级为纯路径文本</b>（返回 null）并记日志；</li>
 *   <li>压缩：原图 base64 后 ≤ 上限直接用；超限经 {@link ImageCompressor} 逐步缩放；</li>
 *   <li>转 base64 dataURL → 返回 {@code replacementText="（附图：{fileName}）"} +
 *       {@code attachments=[{type:"image", dataUrl, fileName, mimeType}]}。</li>
 * </ol>
 *
 * <p>压到最小仍超限 / 无法解码 → 返回提示文本 + 空 attachments（错误文本随 input 进会话，
 * 由 AI 转告用户），记 warn 日志，不阻断任务。任何读取失败均降级不阻断。
 */
public class ImageReferenceHandler implements FileReferenceHandler {

    private static final Logger log = LoggerFactory.getLogger(ImageReferenceHandler.class);

    /** 授权 ask 超时（ms）。 */
    private static final long AUTH_ASK_TIMEOUT_MS = 120_000;

    /** 读入内存的硬上限（256MB）：超出直接按「过大」处理，防超大文件撑爆内存。 */
    private static final long HARD_READ_CAP_BYTES = 256L * 1024 * 1024;

    /** 授权三档选项（与 worker HumanAuthorizationHandler.AUTHORIZE_OPTIONS 同款）。 */
    private static final List<AskOption> AUTHORIZE_OPTIONS = List.of(
            new AskOption("本轮运行内允许", "run", AskOption.TYPE_RADIO),
            new AskOption("本任务全程允许", "task", AskOption.TYPE_RADIO),
            new AskOption("拒绝", "deny", AskOption.TYPE_RADIO));

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
        Path file = resolveAuthorized(ctx, ref);
        if (file == null) {
            return null; // 降级为纯路径文本（各分支已记日志）
        }
        try {
            return buildResult(file, ref);
        } catch (IOException | RuntimeException e) {
            log.warn("[image-vision] 读取图片失败，降级为路径文本: {} - {}", ref.fullPath(), e.toString());
            return null;
        }
    }

    // ---- 路径解析与授权 ----

    /**
     * 解析并校验目标文件路径。
     *
     * @return 可读取的真实路径；null = 降级为纯路径文本（越界/不存在/未授权）
     */
    private Path resolveAuthorized(FileReferenceContext ctx, FileReference ref) {
        if (!ref.external()) {
            // workspace_file：jailed 到工作区根（词法 + realpath 双校验，防符号链接逃逸）。
            if (ctx.workspaceRoot() == null) {
                log.warn("[image-vision] 工作区根不可用，降级为路径文本: {}", ref.path());
                return null;
            }
            try {
                WorkspaceSandbox sandbox = services.workspaces().sandboxFor(ctx.workspaceRoot());
                Path real = sandbox.resolveExisting(ref.path());
                return Files.isRegularFile(real) ? real : null;
            } catch (IOException | RuntimeException e) {
                log.warn("[image-vision] 工作区图片解析失败（越界/不存在），降级为路径文本: {} - {}",
                        ref.path(), e.toString());
                return null;
            }
        }
        // external_file：realpath 后走授权链（人工 authorization ask），未授权降级。
        Path real;
        try {
            real = Path.of(ref.fullPath().trim()).toRealPath();
        } catch (IOException | RuntimeException e) {
            log.warn("[image-vision] 外部图片已失效，降级为路径文本: {}", ref.fullPath());
            return null;
        }
        if (!Files.isRegularFile(real)) {
            log.warn("[image-vision] 外部引用不是普通文件，降级为路径文本: {}", ref.fullPath());
            return null;
        }
        if (!authorizeExternal(ctx, real)) {
            log.warn("[image-vision] 外部图片未获授权，降级为路径文本: {}", ref.fullPath());
            return null;
        }
        return real;
    }

    /**
     * 外部文件授权（authorization ask 三档：本轮运行/本任务/拒绝，fail-closed）。
     * 拒绝/超时/取消/交互服务不可用 → false（降级为纯路径文本，不阻断任务）。
     */
    private boolean authorizeExternal(FileReferenceContext ctx, Path real) {
        // §8.2/F:ask 经主体绑定交互口(SubjectBoundInteractionService 自动补 subjectId 键)
        InteractionService interaction = ctx.interaction();
        if (interaction == null) {
            return false;
        }
        // §12.2:prompt 短问句 + fields 结构化信息槽(文件/授权类型;关键信息保留,磁盘回放可读)
        String prompt = "是否授权读取工作区外的图片?";
        Map<String, String> fields = Map.of(
                "文件", real.toString(),
                "授权类型", "读取图片并 base64 注入模型");
        AskResult ans;
        try {
            ans = interaction.ask(List.of(new AskQuestion("", prompt, AUTHORIZE_OPTIONS, fields)),
                    AUTH_ASK_TIMEOUT_MS, Map.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            log.warn("[image-vision] 外部图片授权 ask 失败（按拒绝对待）: {} - {}", real, e.toString());
            return false;
        }
        if (ans == null || !"answered".equals(ans.status())) {
            return false;
        }
        String a = ans.text() == null ? "" : ans.text().trim().toLowerCase(Locale.ROOT);
        return a.equals("run") || a.equals("task") || a.contains("本轮") || a.contains("本任务");
    }

    // ---- 读取 / 压缩 / 组装结果 ----

    private FileReferenceResult buildResult(Path file, FileReference ref) throws IOException {
        long size = Files.size(file);
        if (size > HARD_READ_CAP_BYTES) {
            log.warn("[image-vision] 图片超过读入硬上限(256MB): {} ({} bytes)", ref.fullPath(), size);
            return tooLarge(ref);
        }
        byte[] raw = Files.readAllBytes(file);
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
                case TOO_LARGE -> {
                    log.warn("[image-vision] 图片压到最小尺寸仍超限: {} ({} bytes)", ref.fullPath(), size);
                    return tooLarge(ref);
                }
                default -> {
                    log.warn("[image-vision] 图片无法解析: {}", ref.fullPath());
                    return new FileReferenceResult(
                            "（图片无法解析：" + ref.fileName() + "）", List.of());
                }
            }
        }
        String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(payload);
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("type", "image");
        attachment.put("dataUrl", dataUrl);
        attachment.put("fileName", ref.fileName());
        attachment.put("mimeType", mimeType);
        return new FileReferenceResult("（附图：" + ref.fileName() + "）", List.of(attachment));
    }

    /** 压到最小仍超限：提示文本进会话（由 AI 转告用户），空 attachments，不阻断任务。 */
    private static FileReferenceResult tooLarge(FileReference ref) {
        return new FileReferenceResult(
                "（图片过大，无法处理：" + ref.fileName() + "，请缩小后重试）", List.of());
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
