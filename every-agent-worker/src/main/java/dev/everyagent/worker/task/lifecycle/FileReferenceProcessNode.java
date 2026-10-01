package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import dev.everyagent.plugin.api.spi.FileReference;
import dev.everyagent.plugin.api.spi.FileReferenceContext;
import dev.everyagent.plugin.api.spi.FileReferenceHandler;
import dev.everyagent.plugin.api.spi.FileReferenceResult;
import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.worker.plugin.registry.FileReferenceHandlerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件引用处理节点（order=395.4，consume.input=396 之前，与 EditResendNode=395.5 错开）。
 *
 * <p>职责（通用机制，核心对具体文件类型零感知）：
 * <ol>
 *   <li>解析 {@code ctx.rawContent()} 中的 {@code system.workspace_file} /
 *       {@code system.external_file} opaque token（4 连符号定界 + base64url payload，
 *       复用 {@link SlashTokenEncoder} 解析，与前端 composerOpaqueToken 同格式）；</li>
 *   <li>按扩展名查 {@link FileReferenceHandlerRegistry} 分发到插件注册的
 *       {@link FileReferenceHandler}；</li>
 *   <li>命中则用 {@code replacementText} 改写 {@code ctx.input()} 中对应的路径文本，
 *       并把 {@code attachments} 追加进 {@code TaskEntry.metadata.attachments}
 *       （随 meta.json 持久化，跨轮可读，由 {@code FileAttachmentAdvisor} 统一注入模型）。</li>
 * </ol>
 *
 * <p>未命中 handler / handler 返回 null / 处理抛异常 → 不改 input（路径文本保持现有行为），
 * 绝不阻断任务。rawContent 不改写（前端回放仍用原 token 还原胶囊）。
 */
public final class FileReferenceProcessNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(FileReferenceProcessNode.class);

    /** opaque token 边界（4 连符号定界，与 SlashTokenResolveAdvisor 一致）。 */
    private static final Pattern TOKEN_RE = Pattern.compile("\\[\\[\\[\\[[\\s\\S]*?\\]\\]\\]\\]");

    private static final String KIND_WORKSPACE_FILE = "system.workspace_file";
    private static final String KIND_EXTERNAL_FILE = "system.external_file";

    /** TaskEntry.metadata 键：附件列表（项约定 {type, dataUrl, fileName, mimeType}）。 */
    public static final String METADATA_ATTACHMENTS_KEY = "attachments";

    private final FileReferenceHandlerRegistry handlers;

    public FileReferenceProcessNode(FileReferenceHandlerRegistry handlers) {
        this.handlers = handlers;
    }

    @Override
    public String id() {
        return "file.reference.process";
    }

    @Override
    public float order() {
        return 395.4f;
    }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        processReferences(ctx);
        return next.proceed(ctx);
    }

    /** 下行段：解析 rawContent 文件引用 → 分发 handler → 改写 input + 合并 attachments。 */
    private void processReferences(TaskLifecycleContext ctx) {
        if (handlers.isEmpty()) {
            return;
        }
        String raw = ctx.rawContent();
        String input = ctx.input();
        if (raw == null || raw.isEmpty() || input == null) {
            return;
        }
        Matcher matcher = TOKEN_RE.matcher(raw);
        List<Map<String, Object>> collected = null;
        while (matcher.find()) {
            String opaque = matcher.group();
            SlashTokenEncoder.ParsedToken token = SlashTokenEncoder.parseToken(opaque);
            if (token == null) {
                continue;
            }
            FileReference ref = toReference(token);
            if (ref == null) {
                continue;
            }
            FileReferenceHandler handler = handlers.find(extensionOf(ref.fileName()));
            if (handler == null) {
                continue;
            }
            FileReferenceResult result;
            try {
                result = handler.process(new ContextView(ctx), ref);
            } catch (Exception e) {
                log.warn("[fileref] 处理器 {} 处理 {} 失败，保持路径文本: {}",
                        handler.pluginId(), ref.fullPath(), e.toString());
                continue;
            }
            if (result == null) {
                continue;
            }
            if (result.replacementText() != null) {
                input = replaceReferenceText(input, opaque, ref, result.replacementText());
            }
            if (!result.attachments().isEmpty()) {
                if (collected == null) {
                    collected = new ArrayList<>();
                }
                collected.addAll(result.attachments());
            }
        }
        if (collected != null) {
            mergeAttachments(ctx, collected);
        }
        if (!input.equals(ctx.input())) {
            ctx.input(input);
        }
    }

    /**
     * 改写 input 中该引用对应的文本：
     * external_file 在 input 中仍是 opaque 原串（前端不做提交解析）；
     * workspace_file 已被前端替换为「 相对路径 」（前后各一个空格）。
     * 找不到锚点时保持 input 原样（attachments 仍合并，记 debug 日志）。
     */
    private String replaceReferenceText(String input, String opaque, FileReference ref, String replacement) {
        if (input.contains(opaque)) {
            return input.replaceFirst(Pattern.quote(opaque), Matcher.quoteReplacement(replacement));
        }
        String spaced = " " + ref.path() + " ";
        if (input.contains(spaced)) {
            return input.replaceFirst(Pattern.quote(spaced), Matcher.quoteReplacement(replacement));
        }
        if (ref.path() != null && !ref.path().isEmpty() && input.contains(ref.path())) {
            return input.replaceFirst(Pattern.quote(ref.path()), Matcher.quoteReplacement(replacement));
        }
        log.debug("[fileref] input 中未找到引用锚点，跳过文本改写: {}", ref.fullPath());
        return input;
    }

    /** 把本轮收集的附件追加进 TaskEntry.metadata.attachments（整体替换为新 List，避免并发写共享引用）。 */
    @SuppressWarnings("unchecked")
    private void mergeAttachments(TaskLifecycleContext ctx, List<Map<String, Object>> collected) {
        Map<String, Object> metadata = ctx.metadata();
        if (metadata == null) {
            log.warn("[fileref] 任务 metadata 不可用，{} 个附件未落盘", collected.size());
            return;
        }
        List<Map<String, Object>> merged = new ArrayList<>();
        Object existing = metadata.get(METADATA_ATTACHMENTS_KEY);
        if (existing instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    merged.add((Map<String, Object>) map);
                }
            }
        }
        merged.addAll(collected);
        metadata.put(METADATA_ATTACHMENTS_KEY, merged);
    }

    /** 把解析出的 token 转为 FileReference；非文件引用 kind / payload 缺路径返回 null。 */
    private FileReference toReference(SlashTokenEncoder.ParsedToken token) {
        JsonNode payload = token.payload();
        if (payload == null) {
            return null;
        }
        if (KIND_WORKSPACE_FILE.equals(token.kind())) {
            String path = textOrNull(payload, "path");
            String fullPath = textOrNull(payload, "fullPath");
            String fileName = textOrNull(payload, "fileName");
            if (path == null || path.isEmpty()) {
                return null;
            }
            if (fileName == null || fileName.isEmpty()) {
                fileName = lastSegment(path);
            }
            return new FileReference(path, fileName,
                    fullPath != null && !fullPath.isEmpty() ? fullPath : path, false);
        }
        if (KIND_EXTERNAL_FILE.equals(token.kind())) {
            String absolutePath = textOrNull(payload, "absolutePath");
            String fileName = textOrNull(payload, "fileName");
            if (absolutePath == null || absolutePath.isEmpty()) {
                return null;
            }
            if (fileName == null || fileName.isEmpty()) {
                fileName = lastSegment(absolutePath);
            }
            return new FileReference(absolutePath, fileName, absolutePath, true);
        }
        return null;
    }

    private static String textOrNull(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return node != null && node.isString() ? node.asString() : null;
    }

    private static String lastSegment(String path) {
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /** 文件名扩展名（小写含点）；无扩展名返回空串（查不到 handler）。 */
    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx <= 0 || idx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx).toLowerCase(Locale.ROOT);
    }

    /** per-任务的 FileReferenceContext 只读视图。 */
    private record ContextView(String taskId, String workspaceId, Path workspaceRoot,
            dev.everyagent.plugin.api.execution.ExecContext execution)
            implements FileReferenceContext {

        ContextView(TaskLifecycleContext ctx) {
            this(ctx.taskId(), ctx.workspaceId(), toPath(ctx.workspaceRoot()), ctx.taskRuntime());
        }

        private static Path toPath(String workspaceRoot) {
            return workspaceRoot != null && !workspaceRoot.isEmpty() ? Path.of(workspaceRoot) : null;
        }
    }
}
