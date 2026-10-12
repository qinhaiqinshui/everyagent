package dev.everyagent.worker.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 附件 map → Spring AI {@link Media} 的唯一转换点（附件 map 规范见
 * {@code FileReferenceResult}：{@code {type, dataUrl, fileName, mimeType}}）。
 *
 * <p>两个消费方共用本类，避免「图片 → Media」知识出现两份：
 * <ul>
 *   <li>{@code FileAttachmentAdvisor}：读任务 metadata.attachments（跨轮持续注入）→ 重建末位
 *       UserMessage 为多模态消息；</li>
 *   <li>{@code UserInputMaterializerImpl}：把本轮新收集的附件装配进物化消息（「插入到当前对话」
 *       等不经轮次节点的投递路径）。</li>
 * </ul>
 *
 * <p>只认 {@code type=image}（dataUrl 形态）；其它类型 / 缺 dataUrl / 转换失败一律跳过，
 * 绝不阻断（附件是增强，不是必需）。未来 type=text 等新类型在此按需扩展。
 */
final class AttachmentMedia {

    private static final Logger log = LoggerFactory.getLogger(AttachmentMedia.class);

    /** 附件类型：图片（dataUrl 为 base64 data URL）。 */
    static final String TYPE_IMAGE = "image";

    private AttachmentMedia() {
    }

    /** 从附件列表中收集 {@code type=image} 项并转 Media；无附件/无图片返回空表。 */
    static List<Media> toMedia(List<Map<String, Object>> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<Media> media = new ArrayList<>();
        for (Map<String, Object> item : attachments) {
            if (item == null || !TYPE_IMAGE.equals(item.get("type"))) {
                continue;
            }
            Object dataUrl = item.get("dataUrl");
            if (!(dataUrl instanceof String data) || data.isEmpty()) {
                continue;
            }
            try {
                var builder = Media.builder()
                        .mimeType(parseMimeType(item.get("mimeType")))
                        .data(data);
                Object fileName = item.get("fileName");
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