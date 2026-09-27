package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.worker.modules.ConfigStore;
import dev.everyagent.worker.modules.ConfigStore.ResolvedConfig;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.plugin.ToolContextImpl;
import dev.everyagent.worker.plugin.registry.ToolProviderRegistry;
import dev.everyagent.worker.task.AgentEntity;
import dev.everyagent.worker.task.ChatModelFactory;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.tools.PermissionGate;
import dev.everyagent.worker.tools.RipgrepBinary;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 装配工厂（agent 层）：从基础设施层取模型/沙箱/工具/权限，
 * 组装出 AgentEntity 交给 AgentService 执行。
 * 主 agent（buildMainAgent）与子 agent（buildAgent）共用。
 */
@Component
public class AgentFactory {

    /** 子 agent 系统提示词（从 SubAgentManager 迁入）。 */
    public static final String SUB_SYSTEM_PROMPT =
            "你是任务中派生的子 agent。专注完成交给你的单一目标,善用工具,给出简明的最终结论。";

    private final ChatModelFactory modelFactory;
    private final ConfigStore configs;
    private final WorkspaceManager workspaces;
    private final OsSandbox sandbox;
    private final PermissionGate gate;
    private final RipgrepBinary rgbin;
    private final ToolProviderRegistry toolProviderRegistry;

    public AgentFactory(ChatModelFactory modelFactory, ConfigStore configs, WorkspaceManager workspaces,
            OsSandbox sandbox, PermissionGate gate, RipgrepBinary rgbin,
            ToolProviderRegistry toolProviderRegistry) {
        this.modelFactory = modelFactory;
        this.configs = configs;
        this.workspaces = workspaces;
        this.sandbox = sandbox;
        this.gate = gate;
        this.rgbin = rgbin;
        this.toolProviderRegistry = toolProviderRegistry;
    }

    /**
     * 主 agent 装配（方法体从 TaskManager.buildMainAgent 原样搬移）。
     * 工具集从 ToolProviderRegistry.getForMain() 聚合；模型经 resolveAgentConfig。
     * 主 agent:agentId = 任务 mainAgentId(再运行沿用);priorConversation 为冷启动载入的历史。
     */
    public AgentEntity buildMainAgent(TaskEntry t, List<Message> priorConversation) {
        // 渐进式披露:内置 skill 知识包由 BuiltInSkills 启动时物化到系统技能目录(§5.10),
        // 不在任务侧重复物化;AI 按需按绝对路径 read_file 读取(系统技能目录只读放行)。
        ResolvedConfig cfg = resolveAgentConfig(t);
        // 工具装配改为从 ToolProviderRegistry 聚合(替代硬编码 new AskUserTool / new BashTool / ...)
        // per-task 上下文封装:taskId、agentId、workspaceRoot、sandbox、gate、workspaces、rgBinary、TaskEntry
        ToolContextImpl ctx = new ToolContextImpl(t.taskId, t.mainAgentId,
                java.nio.file.Path.of(t.workspaceRoot), sandbox, gate, workspaces,
                rgbin.path(), t);
        List<ToolCallback> tools = new ArrayList<>();
        for (ToolProvider p : toolProviderRegistry.getForMain()) {
            if (p.appliesTo(ctx)) {
                tools.addAll(p.createTools(ctx));
            }
        }
        // 模型装配:普通模型 → OpenAiChatModel;provider=model-pool → model-pool 插件(自动容灾)。
        // Agent 请求 options 基底:普通 = 自身快照,池 = 首成员(主模型)快照(上下文压缩等 advisor 据此读参数)。
        ChatModelFactory.AgentModel am = modelFactory.buildAgentModel(cfg, t.mainAgentId, t.events, null);
        AgentEntity main = new AgentEntity(t, t.mainAgentId, AgentEntity.Kind.MAIN, "主 agent",
                am.chatModel(), am.options(), tools);
        main.conversation.addAll(priorConversation);
        return main;
    }

    /**
     * 子 agent 装配（方法体从 SubAgentManager.buildAgent 原样搬移）。
     * 工具集从 ToolProviderRegistry.getForSub() 聚合（结构上禁止递归）。
     * 会话头：SUB_SYSTEM_PROMPT + 首条 user 输入。
     */
    public AgentEntity buildAgent(TaskEntry task, String agentId, String title, String input) {
        // 普通模型用冻结快照;池配置(configId 指向 provider=model-pool)回查 ConfigStore 以取成员列表。
        ResolvedConfig cfg = resolveAgentConfig(task);
        // 子 agent 工具集不含 run_agent 等(结构上禁止递归);tool.result 事件由 AgentRunner 统一发射
        // 子 agent 不注册 ask_user:提问只能由主 agent 发起,子 agent 通过返回结果向上传递信息
        // 工具装配改为从 ToolProviderRegistry 聚合(替代硬编码 new FileTools / new BashTool / ...)
        // per-task 上下文封装:taskId、agentId、workspaceRoot、sandbox、gate、workspaces、rgBinary、TaskEntry
        ToolContextImpl ctx = new ToolContextImpl(task.taskId, agentId,
                java.nio.file.Path.of(task.workspaceRoot), sandbox, gate, workspaces,
                rgbin.path(), task);
        List<ToolCallback> tools = new ArrayList<>();
        for (ToolProvider p : toolProviderRegistry.getForSub()) {
            if (p.appliesTo(ctx)) {
                tools.addAll(p.createTools(ctx));
            }
        }
        // 模型装配:普通模型 → OpenAiChatModel;provider=model-pool → model-pool 插件(自动容灾)。
        ChatModelFactory.AgentModel am = modelFactory.buildAgentModel(cfg, agentId, task.events, null);
        AgentEntity agent = new AgentEntity(task, agentId, AgentEntity.Kind.SUB, title,
                am.chatModel(), am.options(), tools);
        agent.conversation.add(new SystemMessage(SUB_SYSTEM_PROMPT));
        agent.conversation.add(new UserMessage(input));
        return agent;
    }

    /**
     * 任务运行配置解析（方法体从 TaskManager.resolveAgentConfig 原样搬移）。
     * 任务运行配置:apiKey 取自 ConfigStore 解析结果(ResolvedConfig),不再从 TaskEntry 持有。
     * 池配置(configId 指向 provider=model-pool)经 ConfigStore 解析获得池成员列表。
     * (运行期 worker.models 启动即物化、无热更新,与创建时解析一致)。
     */
    public ResolvedConfig resolveAgentConfig(TaskEntry t) {
        return configs.resolve(t.snapshot.configId());
    }
}
