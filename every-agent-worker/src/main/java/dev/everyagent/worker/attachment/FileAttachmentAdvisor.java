package dev.everyagent.worker.attachment;

import dev.everyagent.plugin.api.execution.ExecContext;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文件附件注入 Advisor（核心统一 Advisor）。
 *
 * <p>职责单一（一个 Advisor 只做一个功能）：读 {@code ExecContext.metadata().attachments}，
 * 把 {@code type=image} 项转为 Spring AI {@link Media}（data URL base64），重建末位
 * {@link UserMessage} 为带媒体的多模态消息并 {@code mutate()}。无附件 / 无 user 消息 →
 * 原样放行；末位 user 消息已带媒体时做<b>合并去重</b>而非整条跳过——只追加尚不存在的
 * 候选项（判重口径：data + name + mimeType 组合比较），全部已存在则原样放行（不重建消息）。
 *
 * <p>合并而非跳过的原因：read_file 按扩展名接管（FileReadHandler）可在<b>同一 run 中途</b>
 * 把新附件并入 metadata.attachments（工具循环跨多次模型调用、instructions 复用同一消息
 * 实例），先注入图片 A 后又读入图片 B 时，「已带媒体即跳过」会让 B 本轮不可见；故防重复
 * 注入守卫从「跳过」改为「合并去重」——既有 media 保留在前，新增项按候选序追加。
 *
 * <p>本 Advisor 不感知「图片」业务（不读文件、不压缩、不解析 token）——附件由 read_file
 * 按扩展名接管处理器（{@code FileReadHandler}）产出、核心合并进 metadata.attachments；
 * 未来 PDF/Word 插件复用同一 Advisor，按需扩展 type 支持即可（附件 map → Media 的转换在
 * {@link AttachmentMedia}）。
 *
 * <p>attachments 随 meta.json 持久化，多轮持续注入（便于追问）；token 消耗随轮次累积，
 * 「仅当轮」语义可后续细化。
 */
public class FileAttachmentAdvisor implements BaseAdvisor {

    /** 执行上下文（只读引用）；为 null（如子 agent 无 taskEntry 属性）时恒放行。 */
    private final ExecContext exec;

    public FileAttachmentAdvisor(ExecContext exec) {
        this.exec = exec;
    }

    @Override
    public String getName() {
        return "File Attachment Advisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 160;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        List<Media> media = collectMedia();
        if (media.isEmpty()) {
            return chatClientRequest;
        }
        List<Message> instructions = chatClientRequest.prompt().getInstructions();
        int lastUserIndex = -1;
        for (int i = instructions.size() - 1; i >= 0; i--) {
            if (instructions.get(i) instanceof UserMessage) {
                lastUserIndex = i;
                break;
            }
        }
        if (lastUserIndex < 0) {
            return chatClientRequest;
        }
        UserMessage lastUser = (UserMessage) instructions.get(lastUserIndex);
        List<Media> existing = lastUser.getMedia();
        List<Media> additions = notPresentIn(media, existing);
        if (additions.isEmpty()) {
            // 末位已带媒体且候选全部已存在（或候选为空，前文已短路）→ 原样放行，不重建消息。
            return chatClientRequest;
        }
        List<Media> merged = new ArrayList<>(existing);
        merged.addAll(additions); // 既有在前，新增按候选序
        List<Message> out = new ArrayList<>(instructions);
        out.set(lastUserIndex, UserMessage.builder()
                .text(lastUser.getText())
                .media(merged)
                .metadata(lastUser.getMetadata())
                .build());
        Prompt newPrompt = new Prompt(out, chatClientRequest.prompt().getOptions());
        return chatClientRequest.mutate().prompt(newPrompt).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    /** 从任务 metadata.attachments 收集 type=image 的附件并转为 Media；无则返回空表。 */
    private List<Media> collectMedia() {
        if (exec == null) {
            return List.of();
        }
        Object attachments = exec.metadata().get(ExecContext.METADATA_ATTACHMENTS_KEY);
        if (!(attachments instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> maps = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                maps.add(typed);
            }
        }
        return AttachmentMedia.toMedia(maps);
    }

    /**
     * 候选中筛出「尚未存在」的 Media，供合并去重。判重口径：data（dataUrl 字符串）+
     * name + mimeType 组合比较——{@link Media} 未覆写 equals（身份等价），不能依赖
     * 集合 contains/record equals，须逐项显式比较。
     */
    private static List<Media> notPresentIn(List<Media> candidates, List<Media> existing) {
        if (existing.isEmpty()) {
            return candidates;
        }
        List<Media> additions = new ArrayList<>();
        for (Media candidate : candidates) {
            if (!containsMedia(existing, candidate)) {
                additions.add(candidate);
            }
        }
        return additions;
    }

    private static boolean containsMedia(List<Media> existing, Media candidate) {
        for (Media has : existing) {
            if (Objects.equals(candidate.getData(), has.getData())
                    && Objects.equals(candidate.getName(), has.getName())
                    && Objects.equals(candidate.getMimeType(), has.getMimeType())) {
                return true;
            }
        }
        return false;
    }
}
