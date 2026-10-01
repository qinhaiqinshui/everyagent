package dev.everyagent.plugin.api.spi;

import java.util.Set;

/**
 * 文件引用处理器 SPI —— 插件按扩展名注册处理器，处理用户输入中的文件引用
 * （{@code system.workspace_file} / {@code system.external_file} opaque token）。
 *
 * <p>核心 {@code FileReferenceProcessNode}（任务生命周期节点，consume.input 之前）
 * 解析 rawContent 中的文件引用 token，按扩展名分发到本 SPI 的实现；
 * 未命中 handler 的引用保持现有行为（路径文本进 AI），核心对具体文件类型零感知。
 *
 * <p>典型实现：image-vision 插件注册图片扩展名处理器（读取 → 压缩 → 转 base64
 * data URL → 返回 {@code {replacementText, attachments:[{type:"image",...}]}}）。
 * 未来 PDF/Word 等插件复用同一机制，无需改核心。
 */
public interface FileReferenceHandler {

    /** 插件 id。 */
    String pluginId();

    /** 支持的扩展名集合（小写含点，如 {@code {".png", ".jpg"}}）。 */
    Set<String> extensions();

    /**
     * 处理一个文件引用。
     *
     * @param ctx 任务上下文
     * @param ref 文件引用
     * @return 处理结果；返回 null 等价于「放弃处理」（保持现有路径文本行为）
     */
    FileReferenceResult process(FileReferenceContext ctx, FileReference ref);
}
