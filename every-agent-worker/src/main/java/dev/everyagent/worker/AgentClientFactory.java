package dev.everyagent.worker;

import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.plugin.AdvisorContextImpl;
import dev.everyagent.worker.plugin.registry.AdvisorProviderRegistry;
import dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry;
import dev.everyagent.worker.skill.BuiltInSkills;
import dev.everyagent.worker.skill.SkillAdvisor;
import dev.everyagent.worker.task.AgentCancelledException;
import dev.everyagent.worker.task.AgentEntity;
import dev.everyagent.worker.task.ChatModelFactory;
import dev.everyagent.worker.task.InterceptingToolCallingManager;
import dev.everyagent.worker.task.ModelPoolChatModel;
import dev.everyagent.worker.task.WorkerToolEventAdvisor;
import dev.everyagent.worker.tools.MissingToolCallbackResolver;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * worker 的 {@link ChatClient} 装配入口(架构 §5.2 + 红线:主/子 Agent 共用同一运行入口,
 * 按 {@link AgentEntity.Kind} 分档挂 advisor,仅配置不同)。
 *
 * <p>纪律(AGENTS.md §13):agent 执行必须走 ChatClient + Advisor 生态,禁止手搓工具循环。
 * 本工厂复用 Spring AI 原生 {@code ToolCallingAdvisor}(经 {@link WorkerToolEventAdvisor} 继承扩展,
 * 仅追加 worker 事件发射,不改动循环逻辑),底层 {@link ToolCallingManager} 共享单例。
 *
 * <p>角色分档(对应 nagent:子 agent 不挂派发工具、不挂 skill):
 * <ul>
 *   <li>主 agent:挂 {@link SystemInfoAdvisor}(工作区/OS 环境信息) + {@link AgentsMdAdvisor}
 *       (工作区 agents.md 约束) + {@link SkillAdvisor}(注入内置 skill 渐进式披露索引)
 *       + {@link WorkerToolEventAdvisor};
 *       工具集含 {@code SubAgentTools}(可派生子 agent),由 {@link AgentEntity#tools} 携带。</li>
 *   <li>子 agent:挂 {@link SystemInfoAdvisor}(同样需要知道工作区与系统) + {@link AgentsMdAdvisor}
 *       (工作区 agents.md 约束) + {@link WorkerToolEventAdvisor}
 *       (不挂 skill、不挂派发工具、不注册 ask_user——其 {@code tools} 本就不含 {@code SubAgentTools} 与
 *       {@code AskUserTool},结构上禁递归且不可向用户提问)。</li>
 * </ul>
 *
 * <p>重试双 advisor(n 侧空响应重试 + 瞬时错误退避重试的 advisor 化,角色无关主/子同挂):
 * {@link EmptyResponseRetryAdvisor}(order = 工具循环 +100,每轮包住单次模型调用,空响应重调)
 * 与 {@link TransientErrorRetryAdvisor}(order = 工具循环 +200,最内层包住单次 HTTP 调用,
 * 429/5xx/网络退避重调),参数取 {@code worker.retry}(默认同 n 护栏全局默认);
 * 无人值守为任务级开关,由 {@code ToolExecutionInterceptor} 责任链在 ask_user 工具执行
 * 入口拦截(实时读 {@code TaskEntry.taskFlags}),不经 advisor;
 * 最内层链尾 {@link ContextCompressionAdvisor}(order = 工具循环 +400,主/子 agent 同挂):每轮
 * 模型请求前按窗口阈值压缩 instructions(§5.8),不等待 400 报错,压缩只改发送视图不动事实源。
 *
 * <p>容灾在<b>模型层</b>而非 advisor:任务 configId 指向 {@code provider: model-pool} 池配置时,
 * {@link ChatModelFactory#buildAgentModel} 产出的 chatModel 是 {@link ModelPoolChatModel}(组合
 * 各成员模型、按序容灾切换),主/子 agent 自动具备容灾;无需 slash、无需任务级开关、无需额外 advisor。
 *
 * <p>底层 {@link ChatModel} 仍 per-agent 独立(在 {@code TaskManager}/{@code SubAgentManager}
 * 构造),本工厂只负责"按角色编排 advisor 外壳",不持有 per-run 状态,多任务并发安全。
 */
@Configuration
public class AgentClientFactory {

    private final OsSandbox osSandbox;
    private final AdvisorProviderRegistry advisorRegistry;

    public AgentClientFactory(OsSandbox osSandbox, AdvisorProviderRegistry advisorRegistry) {
        this.osSandbox = osSandbox;
        this.advisorRegistry = advisorRegistry;
    }


    /**
     * 工具执行异常处理器:取消类异常穿透框架向上抛(中断 agent 线程),其余异常转模型可读文本。
     * 与 AgentRunner 原 CANCELLING_PROCESSOR 语义一致;AgentRunner 薄化后,此处为单一事实源。
     */
    private static final ToolExecutionExceptionProcessor CANCELLING_PROCESSOR = ex -> {
        Throwable cause = ex.getCause();
        if (cause instanceof InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AgentCancelledException("interrupted");
        }
        if (cause instanceof AgentCancelledException ace) {
            throw ace;
        }
        String msg = cause == null ? ex.getMessage() : cause.getMessage();
        return "[工具执行失败] " + (msg == null ? cause.getClass().getSimpleName() : msg);
    };

    /**
     * 共享无状态工具调用管理器(取消穿透处理器为单一事实源)，
     * 经 {@link InterceptingToolCallingManager} 包装以支持 {@link ToolExecutionInterceptor} 责任链。
     */
    @Bean
    public ToolCallingManager toolCallingManager(ToolExecutionInterceptorRegistry interceptorRegistry) {
        ToolCallingManager base = ToolCallingManager.builder()
                .toolExecutionExceptionProcessor(CANCELLING_PROCESSOR)
                // AI 可能下发本 agent 未注册的工具名(平台/模型幻觉等):框架默认抛
                // IllegalStateException("No ToolCallback found for tool name: X") 中断任务,
                // 属「AI 下发工具异常」不应致命。开 resolutionFallback + 兜底解析器,把缺失工具
                // 降级为一次错误文本工具结果回传模型,AI 自纠下一轮(复用框架原生扩展点,不改写循环)。
                .toolCallbackResolver(new MissingToolCallbackResolver())
                .resolutionFallbackEnabled(true)
                // 工具循环配额(每 turn 内,每工具/总量,见 ToolCallLimits):框架默认
                // 40/工具、总量 150,对多文件逐项处理等合法长任务过紧;提为 200/工具、总量 500,
                // 仍保留防失控护栏(触顶即抛 ToolCallLimitExceededException 中断工具循环)。
                .maxCallsPerTool(200)
                .maxTotalToolCalls(500)
                .build();
        return new InterceptingToolCallingManager(base, interceptorRegistry);
    }

    /** skill 渐进式披露索引注入 advisor(主 agent 专属,无状态可共享单例)。 */
    @Bean
    public SkillAdvisor skillAdvisor(BuiltInSkills builtInSkills) {
        return new SkillAdvisor(builtInSkills, osSandbox);
    }

    /**
     * 主 agent 的 ChatClient:从 {@link AdvisorProviderRegistry#getForMain()} 聚合 Advisor
     * (按 {@link dev.everyagent.worker.plugin.spi.AdvisorProvider#order()} 排序),
     * 替代原硬编码的 13 个 Advisor 创建与顺序。Advisor 的创建逻辑、顺序、参数由各
     * {@link dev.everyagent.worker.plugin.spi.AdvisorProvider} 适配器封装,行为零变化。
     *
     * <p>容灾在模型层:任务 configId 为池配置时 a.chatModel 即 ModelPoolChatModel。
     * 工具集走 {@code a.tools}(prompt options.toolCallbacks),不在此 defaultTools 重复注册。
     */
    public ChatClient forMain(AgentEntity a, ToolCallingManager tcm) {
        AdvisorContextImpl ctx = new AdvisorContextImpl(a, tcm);
        List<Advisor> advisors = advisorRegistry.getForMain().stream()
                .filter(p -> p.appliesTo(ctx))
                .map(p -> p.create(ctx))
                .toList();
        return ChatClient.builder(a.chatModel)
                .defaultAdvisors(advisors)
                .build();
    }

    /**
     * 子 agent 的 ChatClient:从 {@link AdvisorProviderRegistry#getForSub()} 聚合 Advisor
     * (按 {@link dev.everyagent.worker.plugin.spi.AdvisorProvider#order()} 排序)。
     *
     * <p>子 agent 不挂 skill、不挂派发工具、不注册 ask_user;容灾在模型层——任务 configId
     * 为池配置时 a.chatModel 即 ModelPoolChatModel,子 agent 同样自动换池容灾。子 agent 的
     * FileChangeAdvisor 只记录文件改动到共享回合槽,DialogInsertAdvisor 按 kind 旁路。
     */
    public ChatClient forSub(AgentEntity a, ToolCallingManager tcm) {
        AdvisorContextImpl ctx = new AdvisorContextImpl(a, tcm);
        List<Advisor> advisors = advisorRegistry.getForSub().stream()
                .filter(p -> p.appliesTo(ctx))
                .map(p -> p.create(ctx))
                .toList();
        return ChatClient.builder(a.chatModel)
                .defaultAdvisors(advisors)
                .build();
    }

    /** 按 Kind 分派:主挂 skill+事件,子仅事件(与 nagent 一致)。 */
    public ChatClient forAgent(AgentEntity a, ToolCallingManager tcm) {
        return a.kind == AgentEntity.Kind.SUB ? forSub(a, tcm) : forMain(a, tcm);
    }
}
