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

/**
 * 文件附件注入 Advisor（核心统一 Advisor，与 {@link FileReferenceProcessNode} 配对）。
 *
 * <p>职责单一（一个 Advisor 只做一个功能）：读 {@code ExecContext.metadata().attachments}，
 * 把 {@code type=image} 项转为 Spring AI {@link Media}（data URL base64），重建末位
 * {@link UserMessage} 为带媒体的多模态消息并 {@code mutate()}。无附件 / 无 user 消息 /
 * 末位 user 消息已带媒体 → 原样放行。
 *
 * <p>本 Advisor 不感知「图片」业务（不读文件、不压缩、不解析 token）——处理逻辑在各
 * 插件注册的 {@code FileReferenceHandler}；未来 PDF/Word 插件复用同一 Advisor，
 * 按需扩展 type 支持即可（附件 map → Media 的转换在 {@link AttachmentMedia}，
 * 与 {@code UserInputMaterializerImpl} 共用同一份实现）。
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
        if (!lastUser.getMedia().isEmpty()) {
            // 已带媒体（防御重复注入），原样放行。
            return chatClientRequest;
        }
        List<Message> out = new ArrayList<>(instructions);
        out.set(lastUserIndex, UserMessage.builder()
                .text(lastUser.getText())
                .media(media)
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
}
