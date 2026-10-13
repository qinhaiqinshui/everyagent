package dev.everyagent.worker.attachment;

import dev.everyagent.plugin.api.attachment.UserInputMaterializer;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import dev.everyagent.plugin.api.spi.FileReference;
import dev.everyagent.plugin.api.spi.FileReferenceContext;
import dev.everyagent.plugin.api.spi.FileReferenceHandler;
import dev.everyagent.plugin.api.spi.FileReferenceResult;
import dev.everyagent.worker.plugin.registry.FileReferenceHandlerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link UserInputMaterializer} 默认实现（核心单一实现；轮次节点与「插入到当前对话」共用）。
 *
 * <p>解析 rawContent 中的文件引用 token → 按扩展名分发 {@link FileReferenceHandler} →
 * 改写正文（{@code replacementText}）+ 附件合并进 {@code ExecContext.metadata().attachments}
 * （随 meta.json 持久化，跨轮可读）→ 附件转 {@link org.springframework.ai.content.Media} 装配消息。
 *
 * <p>rawContent 本身不改写（前端回放仍用原 token 还原胶囊）。
 * 处理器返回 null（关闭/放弃处理）则跳过该引用；处理器抛异常则<b>终止任务</b>
 * （降级为纯路径文本没有意义：AI 无法处理裸路径，任务照样出错）。
 */
@Component
public class UserInputMaterializerImpl implements UserInputMaterializer {

    private static final Logger log = LoggerFactory.getLogger(UserInputMaterializerImpl.class);

    /** opaque token 边界（4 连符号定界，与 SlashTokenResolveAdvisor 一致）。 */
    private static final Pattern TOKEN_RE = Pattern.compile("\\[\\[\\[\\[[\\s\\S]*?\\]\\]\\]\\]");

    private static final String KIND_WORKSPACE_FILE = "system.workspace_file";
    private static final String KIND_EXTERNAL_FILE = "system.external_file";

    private final FileReferenceHandlerRegistry handlers;

    public UserInputMaterializerImpl(FileReferenceHandlerRegistry handlers) {
        this.handlers = handlers;
    }

    @Override
    public UserMessage materialize(String text, String rawContent, ExecContext exec) {
        String input = text == null ? "" : text;
        if (exec == null || handlers.isEmpty() || rawContent == null || rawContent.isEmpty()) {
            return plain(input);
        }
        List<Map<String, Object>> collected = null;
        // 处理器 SPI 形参是 FileReferenceContext（与 ExecContext 成员集合相同的标记子类型，
        // 详见其类注释）——插件侧只面对 ExecContext，转换在核心这一处完成。
        FileReferenceContext refCtx = new FileReferenceView(exec);
        Matcher matcher = TOKEN_RE.matcher(rawContent);
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
            FileReferenceResult result = handler.process(refCtx, ref);
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
        if (collected == null) {
            return plain(input);
        }
        mergeAttachments(exec, collected);
        return UserMessage.builder().text(input).media(AttachmentMedia.toMedia(collected)).build();
    }

    /** 无附件消息（无引用 / 未命中处理器 / 处理失败时的降级形态）。 */
    private static UserMessage plain(String text) {
        return UserMessage.builder().text(text).build();
    }

    /**
     * 改写正文中该引用对应的文本：
     * external_file 在正文中仍是 opaque 原串（前端不做提交解析）；
     * workspace_file 已被前端替换为「 相对路径 」（前后各一个空格）。
     * 找不到锚点时保持正文原样（附件仍合并，记 debug 日志）。
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
        log.debug("[fileref] 正文中未找到引用锚点，跳过文本改写: {}", ref.fullPath());
        return input;
    }

    /** 把本轮收集的附件追加进任务 metadata.attachments（整体替换为新 List，避免并发写共享引用）。 */
    @SuppressWarnings("unchecked")
    private void mergeAttachments(ExecContext exec, List<Map<String, Object>> collected) {
        Map<String, Object> metadata = exec.metadata();
        if (metadata == null) {
            log.warn("[fileref] 任务 metadata 不可用，{} 个附件未落盘", collected.size());
            return;
        }
        List<Map<String, Object>> merged = new ArrayList<>();
        Object existing = metadata.get(ExecContext.METADATA_ATTACHMENTS_KEY);
        if (existing instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    merged.add((Map<String, Object>) map);
                }
            }
        }
        merged.addAll(collected);
        metadata.put(ExecContext.METADATA_ATTACHMENTS_KEY, merged);
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

    /**
     * {@link FileReferenceContext} 只读视图（纯委托，机械转发 11 个槽位）。
     *
     * <p>{@code FileReferenceContext} 不声明任何超出 {@link ExecContext} 的成员（见其类注释），
     * 保留该类型只为兼容文件引用处理器 SPI 的形参签名；本视图是两者之间唯一的转换点，
     * 插件（如「插入到当前对话」）因此可以只面对统一的 {@link ExecContext}。
     */
    private record FileReferenceView(ExecContext exec) implements FileReferenceContext {

        @Override
        public String subjectId() {
            return exec.subjectId();
        }

        @Override
        public String workspaceRoot() {
            return exec.workspaceRoot();
        }

        @Override
        public String workspaceId() {
            return exec.workspaceId();
        }

        @Override
        public dev.everyagent.plugin.api.model.ModelConfig snapshot() {
            return exec.snapshot();
        }

        @Override
        public dev.everyagent.plugin.api.model.EventEmitter emitter() {
            return exec.emitter();
        }

        @Override
        public dev.everyagent.plugin.api.agent.AgentFactory agentFactory() {
            return exec.agentFactory();
        }

        @Override
        public Map<String, Object> metadata() {
            return exec.metadata();
        }

        @Override
        public java.nio.file.Path dataDir() {
            return exec.dataDir();
        }

        @Override
        public boolean terminal() {
            return exec.terminal();
        }

        @Override
        public dev.everyagent.plugin.api.interaction.InteractionService interaction() {
            return exec.interaction();
        }

        @Override
        public Map<String, dev.everyagent.plugin.api.agent.AgentContext> agents() {
            return exec.agents();
        }
    }
}