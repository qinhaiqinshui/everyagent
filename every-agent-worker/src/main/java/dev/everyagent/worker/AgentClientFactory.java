package dev.everyagent.worker;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.GitService;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.skill.BuiltInSkills;
import dev.everyagent.worker.skill.SkillAdvisor;
import dev.everyagent.worker.skill.SlashTokenResolveAdvisor;
import dev.everyagent.worker.slash.SlashTokenHandler;
import dev.everyagent.worker.task.AgentCancelledException;
import dev.everyagent.worker.task.AgentEntity;
import dev.everyagent.worker.task.AgentsMdAdvisor;
import dev.everyagent.worker.task.ChatModelFactory;
import dev.everyagent.worker.task.ContextCompressionAdvisor;
import dev.everyagent.worker.task.ContextSummarizer;
import dev.everyagent.worker.task.DialogInsertAdvisor;
import dev.everyagent.worker.task.EmptyResponseRetryAdvisor;
import dev.everyagent.worker.task.FileChangeAdvisor;
import dev.everyagent.worker.task.GitAutoSyncAdvisor;
import dev.everyagent.worker.task.LlmContextSummarizer;
import dev.everyagent.worker.task.LoopRepeatGuardAdvisor;
import dev.everyagent.worker.task.MeasureDurationAdvisor;
import dev.everyagent.worker.task.ModelPoolChatModel;
import dev.everyagent.worker.task.RoundIndexAdvisor;
import dev.everyagent.worker.task.RoundIndexStore;
import dev.everyagent.worker.task.SystemInfoAdvisor;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.task.TransientErrorRetryAdvisor;
import dev.everyagent.worker.task.UnattendedModeAdvisor;
import dev.everyagent.worker.task.WorkerToolEventAdvisor;
import dev.everyagent.worker.tools.MissingToolCallbackResolver;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 *       (不挂 skill、不挂派发工具——其 {@code tools} 本就不含 {@code SubAgentTools},结构上禁递归)。</li>
 * </ul>
 *
 * <p>重试双 advisor(n 侧空响应重试 + 瞬时错误退避重试的 advisor 化,角色无关主/子同挂):
 * {@link EmptyResponseRetryAdvisor}(order = 工具循环 +100,每轮包住单次模型调用,空响应重调)
 * 与 {@link TransientErrorRetryAdvisor}(order = 工具循环 +200,最内层包住单次 HTTP 调用,
 * 429/5xx/网络退避重调),参数取 {@code worker.retry}(默认同 n 护栏全局默认);
 * 无人值守 {@link UnattendedModeAdvisor}(order = 工具循环 +200,主/子 agent 同挂,且为
 * 任务级开关:实时读 {@code TaskEntry.unattended},开启时过滤 ask_user + 注入无人值守提示词);
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

    private final WorkerProperties props;
    private final GitService gitService;
    private final WorkspaceManager workspaces;
    private final SlashTokenHandler slashTokenHandler;
    private final TaskStore taskStore;
    private final RoundIndexStore roundIndexStore;
    private final OsSandbox osSandbox;

    public AgentClientFactory(WorkerProperties props, GitService gitService, WorkspaceManager workspaces,
            SlashTokenHandler slashTokenHandler, TaskStore taskStore,
            RoundIndexStore roundIndexStore, OsSandbox osSandbox) {
        this.props = props;
        this.gitService = gitService;
        this.workspaces = workspaces;
        this.slashTokenHandler = slashTokenHandler;
        this.taskStore = taskStore;
        this.roundIndexStore = roundIndexStore;
        this.osSandbox = osSandbox;
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

    /** 共享无状态工具调用管理器(取消穿透处理器为单一事实源)。 */
    @Bean
    public ToolCallingManager toolCallingManager() {
        return ToolCallingManager.builder()
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
    }

    /** skill 渐进式披露索引注入 advisor(主 agent 专属,无状态可共享单例)。 */
    @Bean
    public SkillAdvisor skillAdvisor(BuiltInSkills builtInSkills) {
        return new SkillAdvisor(builtInSkills);
    }

    /**
     * 主 agent 的 ChatClient:挂 任务耗时 + 文件改动收集 + 环境信息 + skill + 事件(含死循环检测)
     * + 重试双 advisor。容灾在模型层:任务 configId 为池配置时 a.chatModel 即 ModelPoolChatModel。
     * 工具集走 {@code a.tools}(prompt options.toolCallbacks),不在此 defaultTools 重复注册。
     * <p>顺序(由 getOrder 决定,非列表序):MeasureDurationAdvisor(HIGHEST_PRECEDENCE,最外层,
     * 包裹整条链含工具循环,流完成帧把耗时回填进 rounds.jsonl,不再发 task_duration trace)→
     * RoundIndexAdvisor(+10,每轮用户任务流 doOnComplete 后增量补写 rounds.jsonl 轮次索引,
     * 仅主 agent)→
     * SystemInfoAdvisor(+50,注入工作区/OS 环境信息)→ AgentsMdAdvisor(+60,读取工作区
     * agents.md 注入约束)→ SkillAdvisor(+100,注入 skill 渐进式披露索引)→ GitAutoSyncAdvisor(+140,读取本轮 /自动同步 标记,任务收口后触发
     * git 同步)→ SlashTokenResolveAdvisor(+150,统一按 kind 解析/剥离 input 里的 opaque
     * token,透传 a.task 供任务感知 kind(如 system.external_file 注册外部授权根)使用)→
     * UnattendedModeAdvisor(+200,任务级无人值守开关:开启时过滤 ask_user + 注入
     * 无人值守提示词)→ LoopRepeatGuardAdvisor(+300,事件发射 + 工具循环 + 死循环检测)→
     * DialogInsertAdvisor(+330,普通 StreamAdvisor,工具循环内侧下行阶段:把任务队列「插入
     * 到当前对话」的用户消息 drain 并追加到 instructions,随工具结果一起提交给 AI)→
     * FileChangeAdvisor(+301,内层普通 advisor:doOnNext 直接看模型流——工具轮为模型层合并后的
     * 完整消息,检查 update_file/create_file 记录文件改动,「本轮无工具调用」的最终回答轮收口把
     * 轻量摘要/全文填充到 TaskEntry 槽,由 RoundIndexAdvisor 随轮落盘)→ EmptyResponseRetryAdvisor(循环内侧,空响应重调)→
     * TransientErrorRetryAdvisor(瞬时错误退避)→ ContextCompressionAdvisor(最内层,每轮模型
     * 请求前按窗口阈值压缩上下文;请求/响应日志均由 HTTP 层 {@link HttpRequestLoggingInterceptor}
     * 打印,子 agent 不挂 skill 与耗时(见 {@link #forSub})。
     */
    public ChatClient forMain(AgentEntity a, SkillAdvisor skillAdvisor, ToolCallingManager tcm) {
        return ChatClient.builder(a.chatModel)
                .defaultAdvisors(
                        new MeasureDurationAdvisor(a, taskStore, roundIndexStore),
                        new RoundIndexAdvisor(a, taskStore, roundIndexStore),
                        new SystemInfoAdvisor(a.task.workspaceRoot, osSandbox.isWslBackend(), osSandbox.isWslDirect()),
                        new AgentsMdAdvisor(a.task.workspaceRoot),
                        skillAdvisor,
                        new GitAutoSyncAdvisor(a, gitService),
                        new SlashTokenResolveAdvisor(slashTokenHandler, a.task),
                        new UnattendedModeAdvisor(a),
                        newLoopGuardedAdvisor(a, tcm),
                        new DialogInsertAdvisor(a),
                        new FileChangeAdvisor(a),
                        new EmptyResponseRetryAdvisor(a, props.getRetry()),
                        new TransientErrorRetryAdvisor(a, props.getRetry()),
                        newContextCompressionAdvisor(a))
                .build();
    }

    /**
     * 子 agent 的 ChatClient:事件(含死循环检测 + 文件改动记录)+ 无人值守 + 重试双 advisor
     * + 上下文压缩(不挂 skill、不挂派发工具;容灾在模型层——任务 configId 为池配置时
     * a.chatModel 即 ModelPoolChatModel,子 agent 同样自动换池容灾;无人值守为任务级开关,
     * 子 agent 同样注册 ask_user,无人值守时同样剥离 ask_user 并注入提示词;上下文压缩同样
     * 最内层每轮生效。子 agent 的 FileChangeAdvisor 只记录文件改动到共享回合槽,不写
     * trace——任务级文件变更由主 agent 统一收口填充槽并随轮落盘。DialogInsertAdvisor
     * 子 agent 同挂但按 kind 旁路(不接收任务队列用户输入))。
     */
    public ChatClient forSub(AgentEntity a, ToolCallingManager tcm) {
        return ChatClient.builder(a.chatModel)
                .defaultAdvisors(
                        new SystemInfoAdvisor(a.task.workspaceRoot, osSandbox.isWslBackend(), osSandbox.isWslDirect()),
                        new AgentsMdAdvisor(a.task.workspaceRoot),
                        new UnattendedModeAdvisor(a),
                        newLoopGuardedAdvisor(a, tcm),
                        new DialogInsertAdvisor(a),
                        new FileChangeAdvisor(a),
                        new EmptyResponseRetryAdvisor(a, props.getRetry()),
                        new TransientErrorRetryAdvisor(a, props.getRetry()),
                        newContextCompressionAdvisor(a))
                .build();
    }

    /** 工具循环 + 事件发射 + 死循环检测 advisor(每 run 新建,状态随实例物化隔离)。 */
    private LoopRepeatGuardAdvisor newLoopGuardedAdvisor(AgentEntity a, ToolCallingManager tcm) {
        return new LoopRepeatGuardAdvisor(tcm, a, props.getLimits().getMaxRepeatedToolRounds());
    }

    /** 上下文压缩 advisor(主/子 agent 同挂):开启摘要时注入 LLM 摘要器,否则传 null 走确定性降级。 */
    private ContextCompressionAdvisor newContextCompressionAdvisor(AgentEntity a) {
        WorkerProperties.Limits limits = props.getLimits();
        ContextSummarizer summarizer = limits.isContextSummaryEnabled()
                ? new LlmContextSummarizer(a.chatModel, limits.getContextSummaryMaxTokens())
                : null;
        return new ContextCompressionAdvisor(a, limits, summarizer);
    }

    /** 按 Kind 分派:主挂 skill+事件,子仅事件(与 nagent 一致)。 */
    public ChatClient forAgent(AgentEntity a, SkillAdvisor skillAdvisor, ToolCallingManager tcm) {
        return a.kind == AgentEntity.Kind.SUB ? forSub(a, tcm) : forMain(a, skillAdvisor, tcm);
    }
}
