package dev.everyagent.plugin.api.spi;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.execution.ExecContext;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * Advisor 创建上下文 —— {@link AdvisorProvider#create} 的参数。
 *
 * <p>继承 {@link ExecContext}:任务 ID / 工作区 / 模型快照 / emitter 等执行上下文
 * 槽位直接经本接口类型化读取({@code subjectId()}、{@code workspaceRoot()} 等);
 * 工作区根为 String 槽位,需 {@code Path} 的消费侧自行 {@code Path.of(...)} 转换。
 *
 * <p>额外携带 per-agent 信息(agentId、configId)和核心只读服务
 * (ToolCallingManager、当前 agent 上下文),Advisor 提供者据此创建 Advisor 实例。
 */
public interface AdvisorContext extends ExecContext {

    /** Agent ID。 */
    String agentId();

    /**
     * 模型配置 ID(TokenCalibrationAdvisor 等据此校准估算系数)。
     *
     * <p>per-agent 语义:为本 agent 实际解析所用的配置 ID,AgentBuilder 装配时显式传入
     * ——审议 agent 覆盖模型时与 {@link #snapshot()}{@code .configId()} 不同,故不是
     * 与 ExecContext 重复的字段。
     */
    String configId();

    /** 工具调用管理器(共享单例,LoopRepeatGuard 等需要)。 */
    ToolCallingManager toolCallingManager();

    /**
     * 当前 agent 的上下文(plugin-api 契约接口,隐藏 worker 实现细节)。
     *
     * <p>插件经此获取 {@link AgentContext} 来读取会话内存等 agent 域成员,
     * 无需依赖 worker 的 {@code AgentEntity} 具体类。
     *
     * @return 当前 agent 上下文
     */
    AgentContext agentEntity();
}
