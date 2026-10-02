package dev.everyagent.worker.agent;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.proto.SnowflakeId;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.node.ObjectNode;

/**
 * Agent 层 Builder 模式装配入口。
 *
 * <p>S2 起从上层接收 {@link ExecContext}（执行上下文：emitter 取
 * {@code exec.emitter()}，工具上下文直接用 exec 槽位构造）。
 * {@code create()} 时从注册表聚合全部工具和 advisor 到内部列表，
 * 之后可用 {@code .tools(list, mode)} / {@code .advisors(list, mode)} 按模式增删改。
 *
 * <p>核心设计：{@code create()} 聚合全部工具（工具只需 ToolContext，不需 entity）；
 * {@code build()} 创建 entity 后聚合 advisor（advisor 需要 entity 引用），再装配 ChatClient。
 *
 * <p>S4 起 advisor 链全面经 {@code AgentContext.execution()} 类型化槽位取数
 * （{@code entity.execution().subjectId()/workspaceRoot()/snapshot()}），原过渡黑盒
 * map（taskEntry/taskId/workspaceRoot/configId 四件套键）与 AgentEntity.properties
 * 字段已删除；agent 层核心（{@link AgentRunner}）零读上下文数据。
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
     * @param agentId   agent 标识
     * @param chatModel 模型层 ChatModel（由上层解析后传入）
     * @param options   请求参数快照
     * @param exec      本 agent 所属执行上下文（task 或未来 workflow）；emitter 取
     *                  {@code exec.emitter()}，configId 取 {@code exec.snapshot().configId()}
     */
    public Build create(String agentId, ChatModel chatModel, OpenAiChatOptions options,
            ExecContext exec) {
        return create(agentId, chatModel, options, exec, exec.snapshot().configId());
    }

    /**
     * 包内全参变体：{@code configId} 为本 agent 实际解析所用的配置 ID
     * （AgentFactoryImpl 主干传入——审议 agent 覆盖模型时与
     * {@code exec.snapshot().configId()} 不同），Build 持有供
     * {@code createAdvisorContext} 填充 {@code AdvisorContext.configId()}。
     */
    Build create(String agentId, ChatModel chatModel, OpenAiChatOptions options,
            ExecContext exec, String configId) {
        // 注册路径到 SandboxPathRegistry：工作区根 + 系统技能目录。
        // 必须在 advisor 链装配(build())之前完成,使 SystemInfoAdvisor / SkillAdvisor
        // 注入 system prompt 的路径经 pathRegistry.toSandboxPath() 翻译为沙箱内路径
        // (如 WSL 后端 C:\... → /c/...)。幂等:重复注册同一路径只首次实际挂载。
        registerSandboxPaths(exec);

        // 聚合全部工具: 遍历 toolRegistry.getProviders() → appliesTo → createTools
        ToolContextImpl toolCtx = createToolContext(agentId, exec);
        List<ToolCallback> tools = new ArrayList<>();
        for (ToolProvider p : toolRegistry.getProviders()) {
            if (p.appliesTo(toolCtx)) {
                tools.addAll(p.createTools(toolCtx));
            }
        }
        return new Build(agentId, chatModel, options, exec, configId, tools);
    }

    /** 从 exec 槽位构造 ToolContext（S2 起不再从 map 逐个 get）。 */
    private ToolContextImpl createToolContext(String agentId, ExecContext exec) {
        return new ToolContextImpl(exec, agentId, sandbox, gate, workspaces,
                rgBinary != null ? rgBinary.path() : null, pathRegistry);
    }

    /**
     * 注册工作区根 + 系统技能目录到 {@link SandboxPathRegistry}。
     *
     * <p>架构 §7.17：核心调 {@code sandbox.mount()} 拿到 {宿主路径 → 沙箱内路径} 映射,
     * 之后 advisor / 工具链经 {@code pathRegistry.toSandboxPath()} 查表翻译。
     * 本方法在 agent 装配(advisor 链创建)之前完成注册,使 {@code SystemInfoAdvisor}
     * (工作区路径)和 {@code SkillAdvisor}(技能知识包路径)注入 system prompt 时
     * 得到正确的沙箱内路径。幂等:重复注册同一路径只首次实际挂载。
     */
    private void registerSandboxPaths(ExecContext exec) {
        if (exec.workspaceRoot() == null || exec.workspaceRoot().isBlank()) {
            return;
        }
        try {
            java.util.List<dev.everyagent.plugin.api.spi.SandboxBackend.MountRequest> requests =
                    new java.util.ArrayList<>();
            // 工作区根(读写)
            requests.add(new dev.everyagent.plugin.api.spi.SandboxBackend.MountRequest(
                    java.nio.file.Path.of(exec.workspaceRoot()),
                    dev.everyagent.plugin.api.spi.SandboxBackend.Access.READ_WRITE));
            // 系统技能目录(只读)——SkillAdvisor 注入知识包路径时需翻译
            java.nio.file.Path skillsDir = props.resolveSkillsDir();
            if (java.nio.file.Files.isDirectory(skillsDir)) {
                requests.add(new dev.everyagent.plugin.api.spi.SandboxBackend.MountRequest(
                        skillsDir, dev.everyagent.plugin.api.spi.SandboxBackend.Access.READ_ONLY));
            }
            pathRegistry.register(requests);
        } catch (RuntimeException e) {
            // 路径注册失败不阻断任务执行:降级为原路径(DIRECT 场景或挂载失败)
            // AI 看到的路径为宿主路径,与 WSL 后端命令执行侧的独立路径翻译不一致,
            // 但 read_file 经 FsToolSupport 反向翻译兜底(无映射则原样,Java NIO 报错)。
        }
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
        /** 本 agent 所属执行上下文（S2 起为装配主干；agent 级 emitter 包它的 emitter()）。 */
        private final ExecContext execution;
        /** 本 agent 实际解析所用的配置 ID（AdvisorContext.configId() 的供体）。 */
        private final String configId;

        // create() 时已聚合填充，后续可按模式增删改
        private List<ToolCallback> tools;
        // build() 时从注册表聚合（需 entity），存储修改请求
        private List<Advisor> advisorMods;
        private ModifyMode advisorMode;

        // 可选 — 会话
        private final List<Message> conversation = new ArrayList<>();
        private String title = "";

        private Map<String, Object> agentMetadata = Map.of();

        Build(String agentId, ChatModel chatModel, OpenAiChatOptions options,
                ExecContext execution, String configId,
                List<ToolCallback> tools) {
            this.agentId = agentId;
            this.chatModel = chatModel;
            this.options = options;
            this.execution = execution;
            this.configId = configId;
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

        public Build agentMetadata(Map<String, Object> m) {
            this.agentMetadata = m != null ? m : Map.of();
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
            // 1. 创建 AgentEntity（chatClient 暂为 null，build 后设置）；
            //    上游 emitter 取 execution.emitter()（任务级事件口）
            AgentEntity entity = new AgentEntity(agentId, title, chatModel, options,
                    List.copyOf(tools), execution.emitter(), execution, agentMetadata);
            entity.conversation.addAll(conversation);

            // 2. 装配 TCM：per-run InterceptingToolCallingManager（持 exec，替代 ThreadLocal）
            //    → 可选 LoopRepeatGuardToolManager 装饰
            ToolCallingManager interceptingTcm = new InterceptingToolCallingManager(
                    defaultTcm, interceptorRegistry, execution);
            ToolCallingManager tcm = wrapWithGuardIfNeeded(interceptingTcm);

            // 3. 聚合 advisor：创建 AdvisorContext → 遍历 registry → appliesTo → create → 排序
            AdvisorContextImpl advCtx = createAdvisorContext(entity, tcm, configId);
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

            // 8. 自动注册 agent 到执行主体的 agents 注册表 + 发射 agent.started 事件
            execution.agents().put(agentId, entity);
            ObjectNode startedData = Json.obj();
            startedData.put("agentId", agentId);
            if (title != null && !title.isEmpty()) {
                startedData.put("title", title);
            }
            if (!agentMetadata.isEmpty()) {
                startedData.set("metadata", Json.toJson(agentMetadata));
            }
            execution.emitter().emit(EmitEvent.of(
                    SnowflakeId.next(),
                    "agent.started", agentId,
                    null, null, null, null, startedData,
                    EmitEvent.Mode.REPLACE));

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

    /**
     * 构造 AdvisorContext：ExecContext 槽位由 Impl 委托 entity.execution();
     * {@code configId} 为本 agent 实际解析所用配置 ID(Build 持有传入,per-agent 语义)。
     */
    private AdvisorContextImpl createAdvisorContext(AgentEntity entity, ToolCallingManager tcm,
            String configId) {
        return new AdvisorContextImpl(entity, tcm, configId);
    }
}
