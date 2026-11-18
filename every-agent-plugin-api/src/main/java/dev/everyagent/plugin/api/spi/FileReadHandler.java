package dev.everyagent.plugin.api.spi;

import dev.everyagent.plugin.api.execution.ExecContext;

import java.nio.file.Path;
import java.util.Set;

/**
 * read_file 文件读取处理器 SPI —— 插件按扩展名注册处理器，接管 {@code read_file}
 * 工具对文件的读取。核心对文本文件提供默认支持；扩展名未命中任何 handler 时
 * 走核心默认文本读取，行为不变。
 *
 * <p>路径授权解析在核心完成：调用 {@link #read} 之前，核心已按 read_file 既有
 * 安全模型（{@code PermissionGate} READ 档授权 + 工作区沙箱 jail + realpath 校验）
 * 把 AI 传入路径解析为 {@code file}（宿主真实绝对路径）；实现方直接读 {@code file}
 * 即可，<b>不得另行自行解析路径</b>（防绕过授权）。
 *
 * <p>典型实现：image-vision 插件注册图片扩展名处理器（读取 → 压缩 → base64
 * data URL → 返回 {@code text="已读取图片：…"} +
 * {@code attachments=[{type:"image",...}]}）；未来 PDF 插件可返回提取后的
 * 文本内容（attachments 为空列表）。
 */
public interface FileReadHandler {

    /** 插件 id。 */
    String pluginId();

    /** 支持的扩展名集合（小写含点，如 {@code {".png", ".jpg"}}）。 */
    Set<String> extensions();

    /**
     * 读取一个文件。
     *
     * <p>read_file 的 {@code line_start} / {@code line_end} 等行区间参数对接管
     * 处理器无意义，核心不会传递。
     *
     * @param ctx  任务上下文
     * @param path AI 调 read_file 时传入的原始路径参数
     * @param file 核心授权解析后的宿主绝对路径（已确认存在且为普通文件的校验按需自做）
     * @return 读取结果；返回 null 等价于「放弃接管」（回落核心默认文本读取）
     * @throws RuntimeException 处理失败时抛出，异常信息按工具执行失败文本回灌 AI
     *         （fail-fast，不降级为乱码文本）
     */
    FileReadResult read(ExecContext ctx, String path, Path file);
}
