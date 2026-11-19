package dev.everyagent.plugin.api.spi;

import java.util.List;
import java.util.Map;

/**
 * read_file 接管处理器的返回值 —— {@link FileReadHandler#read} 的返回值。
 *
 * <p>核心 {@code FileTools} 据此做两件事：
 * <ol>
 *   <li>把 {@link #text()} 作为 read_file 的工具结果串直接回灌 AI；</li>
 *   <li>把 {@link #attachments()} 合并进 {@code TaskEntry.metadata.attachments}
 *       （随 meta.json 持久化、跨轮可读），由核心 {@code FileAttachmentAdvisor}
 *       注入模型——AI 在工具结果后的下一次模型调用即可看到图片。</li>
 * </ol>
 *
 * <p>attachments 项约定结构：{@code {type: "image", dataUrl, fileName, mimeType}}
 * （dataUrl 为 base64 data URL，如 {@code data:image/png;base64,...}）；
 * 未来可扩展 {@code type: "text"} 等新类型。
 */
public record FileReadResult(
        /** read_file 的工具结果串（直接回灌 AI）。 */
        String text,
        /** 附件列表（空列表表示无附件）。 */
        List<Map<String, Object>> attachments) {

    public FileReadResult {
        if (attachments == null) {
            attachments = List.of();
        }
    }
}
