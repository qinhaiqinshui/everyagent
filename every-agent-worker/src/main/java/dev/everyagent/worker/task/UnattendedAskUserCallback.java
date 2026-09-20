package dev.everyagent.worker.task;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 无人值守 ask_user 拦截装饰器(任务级开关,实时生效)。
 *
 * <p>本装饰器包装 {@code AskUserTool} 的 {@link ToolCallback},在工具执行瞬间拦截
 * {@code ask_user} 调用:任务级 {@code TaskEntry.unattended}(volatile)为 true 时,
 * 直接返回合成文本「当前无人值守,请按你推荐的实现。」——不创建 ask、不挂起虚拟线程、
 * 不发 ask.create/ask.state/ask.resolved;为 false 时原样委托给真实
 * {@code AskUserTool}(正常挂起等待用户作答)。
 *
 * <p>设计要点:
 * <ul>
 *   <li><b>拦截点在工具执行瞬间(call)</b>,而非模型请求前的工具定义过滤:模型仍看得到
 *       ask_user 工具、仍会发起调用,只是调用被短路。与「运行时中途开关即时生效」语义一致——
 *       无需等下一轮模型请求重新组装工具列表。</li>
 *   <li><b>实时性</b>:装饰器持有 {@link TaskEntry} 引用,每次 call 读 volatile 字段;
 *       用户在运行中点胶囊开/关无人值守,下一次 AI 调用 ask_user 时即生效,不重启任务。</li>
 *   <li><b>事件流一致</b>:合成结果作为正常 tool result 经
 *       {@code WorkerToolEventAdvisor#doGetNextInstructionsForToolCallStream} 按 callId
 *       配对发射 tool.result,与真实 ask 被回答后的事件形态完全一致,前端无需特例。</li>
 *   <li><b>子 agent 无注册</b>:子 agent 本就不注册 ask_user(其工具集不含 AskUserTool),
 *       本装饰器对子 agent 无触发点,天然零副作用。</li>
 * </ul>
 *
 * <p>与 {@code SchemaStrippedToolCallback} 同为 ToolCallback 装饰器,但本类按任务状态
 * 条件拦截,非纯透传包装。实例随每次 {@code buildMainAgent} 新建,状态随实例物化隔离。
 */
public class UnattendedAskUserCallback implements ToolCallback {

    /** 无人值守时回传给 AI 的合成工具结果文本。 */
    private static final String UNATTENDED_RESULT = "当前无人值守,请按你推荐的实现。";

    private final ToolCallback delegate;
    private final TaskEntry task;

    public UnattendedAskUserCallback(ToolCallback delegate, TaskEntry task) {
        this.delegate = delegate;
        this.task = task;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        if (task.unattended) {
            return UNATTENDED_RESULT;
        }
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        if (task.unattended) {
            return UNATTENDED_RESULT;
        }
        return delegate.call(toolInput, toolContext);
    }
}
