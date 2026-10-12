package dev.everyagent.plugin.api.attachment;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.spi.FileReferenceHandler;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 用户输入物化服务（核心实现，插件经 {@code WorkerServices.userInputMaterializer()} 取用）。
 *
 * <p>把一轮「人类可读正文 + rawContent（可含文件引用 opaque token）」输入物化为可投喂模型的
 * {@link UserMessage}，处理链与任务轮次的输入预处理（核心 {@code file.reference.process} 节点）
 * <b>同一条实现</b>：
 * <ol>
 *   <li>解析 rawContent 中的 {@code system.workspace_file} / {@code system.external_file} token
 *       （{@code SlashTokenEncoder}，4 连符号定界 + base64url payload，与前端 composer 同格式）；</li>
 *   <li>按扩展名分发到插件注册的 {@link FileReferenceHandler}；</li>
 *   <li>用 {@code replacementText}（如「（附图：xxx）」）改写正文中对应的路径文本；</li>
 *   <li>附件合并进任务 {@code metadata.attachments}（随 meta.json 持久化，跨轮可读，由核心
 *       {@code FileAttachmentAdvisor} 持续注入模型）；</li>
 *   <li>附件转 Spring AI {@code Media} 装配多模态消息。</li>
 * </ol>
 *
 * <p><b>为什么需要本服务</b>：轮次路径的引用处理挂在生命周期节点上，但「运行中把输入直接投给
 * 正在跑的工具循环」的路径（任务队列「插入到当前对话」）不过节点——若各自实现一份，@ 文件引用
 * 会退化成裸路径文本（图片不进模型、AI 只看到路径，转而去 read_file 图片失败）。
 * 任何「把一条用户输入投给模型」的入口都应经本服务物化。
 *
 * <p>核心对具体文件类型零感知：不认识扩展名（无处理器）时正文保持原样、不产生附件，绝不阻断。
 */
public interface UserInputMaterializer {

    /**
     * 物化一条用户输入。
     *
     * @param text       人类可读正文（前端提交的 input；null 视为空串）
     * @param rawContent 原始 opaque token 串（可为 null/空 —— 无 token 时直接返回纯文本消息）
     * @param exec       执行上下文（工作区根、任务 metadata、交互口等；null 时跳过引用处理）
     * @return 可投喂模型的消息：正文已按处理器结果改写、附件已转 media；无引用/未命中处理器/
     *         处理失败一律降级为正文原样的纯文本消息
     */
    UserMessage materialize(String text, String rawContent, ExecContext exec);
}