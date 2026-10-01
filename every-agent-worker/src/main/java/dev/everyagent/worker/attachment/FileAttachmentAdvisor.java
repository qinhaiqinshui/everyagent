package dev.everyagent.worker.attachment;

import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.lifecycle.FileReferenceProcessNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.Ordered;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文件附件注入 Advisor（核心统一 Advisor，与 {@link FileReferenceProcessNode} 配对）。
 *
 * <p>职责单一（一个 Advisor 只做一个功能）：读 {@code TaskEntry.metadata.attachments}，
 * 把 {@code type=image} 项转为 Spring AI {@link Media}（data URL base64），重建末位
 * {@link UserMessage} 为带媒体的多模态消息并 {@code mutate()}。无附件 / 无 user 消息 /
 * 末位 user 消息已带媒体 → 原样放行。
 *
 * <p>本 Advisor 不感知「图片」业务（不读文件、不压缩、不解析 token）——处理逻辑在各
 * 插件注册的 {@code FileReferenceHandler}；未来 PDF/Word 插件复用同一 Advisor，
 * 按需扩展 type 支持即可。
 *
 * <p>attachments 随 meta.json 持久化，多轮持续注入（便于追问）；token 消耗随轮次累积，
 * 「仅当轮」语义可后续细化。
 */
public class FileAttachmentAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(FileAttachmentAdvisor.class);

    /** 任务上下文（只读引用）；为 null（如子 agent 无 taskEntry 属性）时恒放行。 */
    private final TaskEntry task;

    public FileAttachmentAdvisor(TaskEntry task) {
        this.task = task;
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
        if (task == null) {
            return List.of();
        }
        Object attachments = task.metadata.get(FileReferenceProcessNode.METADATA_ATTACHMENTS_KEY);
        if (!(attachments instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Media> media = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            if (!"image".equals(map.get("type"))) {
                continue;
            }
            Object dataUrl = map.get("dataUrl");
            if (!(dataUrl instanceof String data) || data.isEmpty()) {
                continue;
            }
            try {
                var builder = Media.builder()
                        .mimeType(parseMimeType(map.get("mimeType")))
                        .data(data);
                Object fileName = map.get("fileName");
                if (fileName instanceof String name && !name.isEmpty()) {
                    builder.name(name);
                }
                media.add(builder.build());
            } catch (RuntimeException e) {
                log.warn("[attachment] 附件转 Media 失败，已跳过: {}", e.toString());
            }
        }
        return media;
    }

    /** 解析附件 mimeType；缺失/非法时兜底 image/png。 */
    private static MimeType parseMimeType(Object mimeType) {
        if (mimeType instanceof String value && !value.isEmpty()) {
            try {
                return MimeTypeUtils.parseMimeType(value);
            } catch (RuntimeException ignored) {
                // 落兜底
            }
        }
        return MimeTypeUtils.IMAGE_PNG;
    }
}
