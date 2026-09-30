package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.interaction.InteractionServiceImpl;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.plugin.AdvisorContextImpl;
import dev.everyagent.worker.plugin.ToolContextImpl;
import dev.everyagent.worker.os.SandboxPathRegistry;
import dev.everyagent.worker.plugin.registry.AdvisorProviderRegistry;
import dev.everyagent.worker.plugin.registry.ToolProviderRegistry;
import dev.everyagent.worker.task.InterceptingToolCallingManager;
import dev.everyagent.worker.task.LoopRepeatGuardToolManager;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.tools.PermissionGate;
import dev.everyagent.worker.tools.RipgrepBinary;
import dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Agent 层 Builder 模式装配入口。
 *
 * <p>从上层接收 {@link EventEmitter}（逐层传播）+ {@code Map<String, Object> properties}（黑盒数据），
 * 不接收 TaskEntry。{@code create()} 时从注册表聚合全部工具和 advisor 到内部列表，
 * 之后可用 {@code .tools(list, mode)} / {@code .advisors(list, mode)} 按模式增删改。
 *
 * <p>核心设计：{@code create()} 聚合全部工具（工具只需 ToolContext，不需 entity）；
 * {@code build()} 创建 entity 后聚合 advisor（advisor 需要 entity 引用），再装配 ChatClient。
 *
 * <p>{@code properties} 是黑盒：agent 层核心（{@link AgentRunner}）完全不读；
 * task 层 advisor 从中取出 TaskEntry 等。builder 内部从 properties 取必要信息构造 ToolContext。
 */
@Component
public class AgentBuilder {

    /** 修改模式：REPLACE=替换全部，ADD=追加，REMOVE=移除匹配项。 */
    public enum ModifyMode { REPLACE, ADD, REMOVE }

    private final ToolProviderRegistry toolRegistry;
    private final AdvisorProviderRegistry advisorRegistry;
    private final ToolCallingManager defaultTcm;
    private final ToolExecutionInterceptorRegistry interceptorRegistry;
    private final WorkerProperties props;
    private final OsSandbox sandbox;
    private final PermissionGate gate;
    private final WorkspaceManager workspaces;
    private final RipgrepBinary rgBinary;
    private final InteractionServiceImpl interaction;
    private final SandboxPathRegistry pathRegistry;
    private final AgentRunner runner;

    public AgentBuilder(ToolProviderRegistry toolRegistry,
            AdvisorProviderRegistry advisorRegistry,
            ToolCallingManager defaultTcm,
            ToolExecutionInterceptorRegistry interceptorRegistry,
            WorkerProperties props,
            OsSandbox sandbox,
            PermissionGate gate,
            WorkspaceManager workspaces,
            RipgrepBinary rgBinary,
            InteractionServiceImpl interaction,
            SandboxPathRegistry pathRegistry,
            AgentRunner runner) {
        this.toolRegistry = toolRegistry;
        this.advisorRegistry = advisorRegistry;
        this.defaultTcm = defaultTcm;
        this.interceptorRegistry = interceptorRegistry;
        this.props = props;
        this.sandbox = sandbox;
        this.gate = gate;
        this.workspaces = workspaces;
        this.rgBinary = rgBinary;
        this.interaction = interaction;
        this.pathRegistry = pathRegistry;
        this.runner = runner;
    }

    /**
     * 创建装配会话：聚合全部工具 + 全部 advisor 到内部列表，返回 fluent Build 对象。
     *
     * @param agentId    agent 标识
     * @param chatModel  模型层 ChatModel（由上层解析后传入）
     * @param options    请求参数快照
     * @param emitter    从上层传入的事件发射器（逐层传播）
     * @param properties 上层黑盒数据（taskEntry / taskId / workspaceRoot 等，agent 层核心不读）
     */
    public Build create(String agentId, ChatModel chatModel, OpenAiChatOptions options,
            EventEmitter emitter, Map<String, Object> properties) {
        // 聚合全部工具: 遍历 toolRegistry.getProviders() → appliesTo → createTools
        ToolContextImpl toolCtx = createToolContext(agentId, properties);
        List<ToolCallback> tools = new ArrayList<>();
        for (ToolProvider p : toolRegistry.getProviders()) {
            if (p.appliesTo(toolCtx)) {
                tools.addAll(p.createTools(toolCtx));
            }
        }
        return new Build(agentId, chatModel, options, emitter, properties, tools);
    }

    /** 从 properties 提取必要信息构造 ToolContext。 */
    @SuppressWarnings("unchecked")
    private ToolContextImpl createToolContext(String agentId, Map<String, Object> properties) {
        String taskId = (String) properties.get("taskId");
        Object wsRoot = properties.get("workspaceRoot");
        Path workspaceRoot = wsRoot == null ? null : Paths.get(wsRoot.toString());
        TaskEntry taskEntry = (TaskEntry) properties.get("taskEntry");
        return new ToolContextImpl(taskId, agentId, workspaceRoot,
                sandbox, gate, workspaces, rgBinary != null ? rgBinary.path() : null, interaction, taskEntry,
                pathRegistry);
    }

    /**
     * 死循环守卫：{@code maxRepeated > 0} 时用 {@link LoopRepeatGuardToolManager} 装饰 TCM，
     * 否则原样返回。守卫状态随装饰器实例 per-run 隔离。
     */
    ToolCallingManager wrapWithGuardIfNeeded(ToolCallingManager tcm) {
        int maxRepeated = props.getLimits().getMaxRepeatedToolRounds();
        if (maxRepeated <= 0) {
            return tcm;
        }
        return new LoopRepeatGuardToolManager(tcm, maxRepeated);
    }

    /** 装配会话（fluent builder）。 */
    public class Build {

        private final String agentId;
        private final ChatModel chatModel;
        private final OpenAiChatOptions options;
        private final EventEmitter emitter;
        private final Map<String, Object> properties;

        // create() 时已聚合填充，后续可按模式增删改
        private List<ToolCallback> tools;
        // build() 时从注册表聚合（需 entity），存储修改请求
        private List<Advisor> advisorMods;
        private ModifyMode advisorMode;

        // 可选 — 会话
        private final List<Message> conversation = new ArrayList<>();
        private String title = "";

        Build(String agentId, ChatModel chatModel, OpenAiChatOptions options,
                EventEmitter emitter, Map<String, Object> properties,
                List<ToolCallback> tools) {
            this.agentId = agentId;
            this.chatModel = chatModel;
            this.options = options;
            this.emitter = emitter;
            this.properties = properties;
            this.tools = new ArrayList<>(tools);
        }

        /** 按模式增删改工具。 */
        public Build tools(List<ToolCallback> t, ModifyMode mode) {
            if (mode == ModifyMode.REPLACE) {
                this.tools = new ArrayList<>(t);
            } else if (mode == ModifyMode.ADD) {
                this.tools.addAll(t);
            } else if (mode == ModifyMode.REMOVE) {
                var namesToRemove = t.stream().map(this::toolName).collect(java.util.stream.Collectors.toSet());
                this.tools = this.tools.stream()
                        .filter(tc -> !namesToRemove.contains(toolName(tc)))
                        .collect(java.util.stream.Collectors.toList());
            }
            return this;
        }

        private String toolName(ToolCallback tc) {
            try {
                return tc.getToolDefinition().name();
            } catch (Exception e) {
                return tc.getClass().getSimpleName();
            }
        }

        /** 按模式增删改 advisor（在 build() 时应用，因为 advisor 聚合需要 entity）。 */
        public Build advisors(List<Advisor> a, ModifyMode mode) {
            this.advisorMods = a;
            this.advisorMode = mode;
            return this;
        }

        public Build title(String t) {
            this.title = t;
            return this;
        }

        public Build conversation(List<Message> c) {
            this.conversation.clear();
            this.conversation.addAll(c);
            return this;
        }

        public Build systemPrompt(String p) {
            this.conversation.add(new SystemMessage(p));
            return this;
        }

        public Build userInput(String i) {
            this.conversation.add(new UserMessage(i));
            return this;
        }

        /**
         * 装配完成：创建 AgentEntity → 聚合 advisor → 装配 ChatClient → 设置到 entity。
         */
        public AgentEntity build() {
            // 1. 创建 AgentEntity（chatClient 暂为 null，build 后设置）
            AgentEntity entity = new AgentEntity(agentId, title, chatModel, options,
                    List.copyOf(tools), emitter, properties);
            entity.conversation.addAll(conversation);

            // 2. 装配 TCM：per-run InterceptingToolCallingManager（持 properties，替代 ThreadLocal）
            //    → 可选 LoopRepeatGuardToolManager 装饰
            ToolCallingManager interceptingTcm = new InterceptingToolCallingManager(
                    defaultTcm, interceptorRegistry, properties);
            ToolCallingManager tcm = wrapWithGuardIfNeeded(interceptingTcm);

            // 3. 聚合 advisor：创建 AdvisorContext → 遍历 registry → appliesTo → create → 排序
            AdvisorContextImpl advCtx = createAdvisorContext(entity, tcm);
            List<Advisor> advisors = new ArrayList<>();
            for (AdvisorProvider p : advisorRegistry.getProviders().stream()
                    .sorted(Comparator.comparingInt(AdvisorProvider::order)).toList()) {
                if (p.appliesTo(advCtx)) {
                    Advisor adv = p.create(advCtx);
                    if (adv != null) {
                        advisors.add(adv);
                    }
                }
            }

            // 4. 应用 advisor 修改请求
            if (advisorMods != null && advisorMode != null) {
                advisors = applyAdvisorMods(advisors, advisorMods, advisorMode);
            }

            // 5. 装配 ChatClient
            ChatClient chatClient = ChatClient.builder(chatModel)
                    .defaultAdvisors(advisors)
                    .build();

            // 6. 设置 chatClient 到 entity
            entity.chatClient = chatClient;

            // 7. 注入 AgentRunner(供 AgentEntity.run() 委托调用)
            entity.runner(AgentBuilder.this.runner);

            return entity;
        }

        private List<Advisor> applyAdvisorMods(List<Advisor> current,
                List<Advisor> mods, ModifyMode mode) {
            if (mode == ModifyMode.REPLACE) {
                return new ArrayList<>(mods);
            } else if (mode == ModifyMode.ADD) {
                List<Advisor> result = new ArrayList<>(current);
                result.addAll(mods);
                return result;
            } else { // REMOVE
                var classesToRemove = mods.stream()
                        .map(a -> a.getClass().getName())
                        .collect(java.util.stream.Collectors.toSet());
                return current.stream()
                        .filter(a -> !classesToRemove.contains(a.getClass().getName()))
                        .collect(java.util.stream.Collectors.toList());
            }
        }
    }

    /** 从 properties 提取必要信息构造 AdvisorContext。 */
    private AdvisorContextImpl createAdvisorContext(AgentEntity entity, ToolCallingManager tcm) {
        String taskId = (String) entity.properties.get("taskId");
        Object wsRoot = entity.properties.get("workspaceRoot");
        Path workspaceRoot = wsRoot == null ? null : Paths.get(wsRoot.toString());
        Object cid = entity.properties.get("configId");
        String configId = cid == null ? null : cid.toString();
        return new AdvisorContextImpl(entity, tcm, taskId, workspaceRoot, configId);
    }
}
