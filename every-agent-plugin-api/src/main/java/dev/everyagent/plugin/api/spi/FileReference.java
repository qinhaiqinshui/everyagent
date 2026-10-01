package dev.everyagent.plugin.api.spi;

/**
 * 文件引用 —— 从用户输入 rawContent 中的 {@code system.workspace_file} /
 * {@code system.external_file} opaque token 解析出的结构化视图。
 *
 * <p>由 worker 核心的 {@code FileReferenceProcessNode} 解析并交给
 * {@link FileReferenceHandler} 处理。
 */
public record FileReference(
        /**
         * 交给 AI 的引用路径（workspace_file = 工作区相对路径，无前导 {@code /}；
         * external_file = 工作区外绝对路径）。
         */
        String path,
        /** 文件名（路径最后一段，即输入框胶囊 label）。 */
        String fileName,
        /**
         * 完整路径（workspace_file = 带前导 {@code /} 的业务绝对路径；
         * external_file = 工作区外绝对路径）。
         */
        String fullPath,
        /** 是否工作区外引用（{@code system.external_file} 为 true）。 */
        boolean external) {
}
