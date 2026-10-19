package dev.everyagent.plugin.api.spi;

import dev.everyagent.plugin.api.execution.ExecContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 工具执行拦截上下文(<b>per-call 粒度</b>:每次工具调用由
 * {@code PerCallToolCallingManager} 的切口构造一个新实例并执行一遍拦截链,见架构 §7.14.3)。
 *
 * <p>本身即 {@link ExecContext}(extends),拦截器直读类型化槽位
 * (subjectId / workspaceRoot / metadata 等),不再经 {@code execution()} 中转;
 * {@code emitter()} 覆写为 <b>agent 级</b> agentId 包装口(链上发射的事件自动携带
 * 归属 agentId,主/子一致)。
 *
 * <p><b>批级成员</b>(整轮不变):{@link #prompt()} / {@link #chatResponse()} /
 * {@link #toolCalls()}(本轮下发的全部调用,顺序与模型一致)。
 *
 * <p><b>per-call 成员</b>(链节点可读写,方案 B 切口语义):
 * <ul>
 *   <li>{@link #toolCall()} / {@link #toolCallback()}——当前调用全字段与解析出的回调
 *       (下行切口位于框架回调解析之后、observation 之前,二者必有值);</li>
 *   <li>{@link #arguments()} / {@link #arguments(String)}——当前入参串(空参已归一为
 *       {@code "{}"});<b>下行段可改写</b>,改写对真实执行与 observation 生效;</li>
 *   <li>{@link #synthesize(String)}——<b>下行合成哨兵</b>:设置后链尾跳过 observation +
 *       真实调用,合成串<b>沿框架 ToolResponse 构造路径</b>流经全部上行改写节点
 *       (secret-redaction 等对合成答案照常生效)后作为该调用结果;调用循环继续推进
 *       下一个调用(<b>不可终止批</b>——框架循环无 mid-batch break,自造「N 调用
 *       M 结果」的模型配对义务不被采纳);</li>
 *   <li>{@link #startedAt()}——本调用开始时刻(毫秒,切口记录;上行段据此算
 *       durationMs);</li>
 *   <li>{@link #toolOutcomes()}——本 run 的工具结果账本(可空:单测/裸用场景);
 *       链上行按 callId 写账。</li>
 * </ul>
 *
 * <p><b>上行段</b>({@code next.proceed(ctx)} 返回后)可改写结果串、可发事件,<b>不可撤销</b>
 * 已执行调用。拦截器也可直接 {@code return} 串不调 {@code next}(经典硬短路,绕过内层
 * 节点——仅限极端场景;合成一律走 {@link #synthesize})。
 */
public interface ToolExecutionContext extends ExecContext {
    /** 本轮(批)的 prompt。 */
    Prompt prompt();

    /** 本轮(批)的模型响应(含下发工具调用的 assistant 消息)。 */
    ChatResponse chatResponse();

    /** 本轮(批)下发的全部工具调用(顺序与模型下发一致;批级只读信息)。 */
    List<AssistantMessage.ToolCall> toolCalls();

    /** 当前正在执行的工具调用(per-call 粒度)。 */
    AssistantMessage.ToolCall toolCall();

    /** 当前调用解析出的工具回调(下行切口在框架回调解析之后,必有值)。 */
    ToolCallback toolCallback();

    /** 当前调用的入参串(空参已归一为 "{}");下行段可改写,改写对真实执行生效。 */
    String arguments();

    /** 改写当前入参串(下行段)。 */
    void arguments(String arguments);

    /**
     * 下行合成哨兵:设置后链尾跳过 observation + 真实调用,合成串沿框架 ToolResponse
     * 构造路径流经全部上行改写节点后作为该调用结果;调用循环继续推进(不可终止批)。
     */
    void synthesize(String result);

    /** 哨兵读取(null = 未合成)。 */
    String synthesizedResult();

    /** 本调用开始时刻(毫秒;切口记录,上行段据此算 durationMs)。 */
    long startedAt();

    /** 记录本调用开始时刻(毫秒)。 */
    void startedAt(long epochMs);

    /** 本 run 的工具结果账本(可空);链上行按 callId 写账(见 ToolOutcomeLog)。 */
    ToolOutcomeLog toolOutcomes();
}

