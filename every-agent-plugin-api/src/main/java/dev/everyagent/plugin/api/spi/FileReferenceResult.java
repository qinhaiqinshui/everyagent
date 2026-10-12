package dev.everyagent.plugin.api.spi;

import java.util.List;
import java.util.Map;

/**
 * 文件引用处理结果 —— {@link FileReferenceHandler#process} 的返回值。
 *
 * <p>核心节点（{@code FileReferenceProcessNode}）与「插入到当前对话」路径（{@code DialogInsertAdvisor}）
 * 都经核心用户输入物化服务（{@code UserInputMaterializer}）消费本结果，据此做两件事：
 * <ol>
 *   <li>用 {@link #replacementText()} 改写输入中对应的路径文本
 *       （为 null 时保持 input 原样）；</li>
 *   <li>把 {@link #attachments()} 追加进 {@code TaskEntry.metadata.attachments}
 *       （持久化，跨轮可读），由核心 {@code FileAttachmentAdvisor} 注入模型。</li>
 * </ol>
 *
 * <p>attachments 项约定结构：{@code {type: "image", dataUrl, fileName, mimeType}}
 * （dataUrl 为 base64 data URL，如 {@code data:image/png;base64,...}）；
 * 未来可扩展 {@code type: "text"} 等新类型。
 */
public record FileReferenceResult(
        /** 替换输入中文件引用路径文本的内容；为 null 表示不改写 input。 */
        String replacementText,
        /** 附件列表（空列表表示无附件）。 */
        List<Map<String, Object>> attachments) {

    public FileReferenceResult {
        if (attachments == null) {
            attachments = List.of();
        }
    }
}
