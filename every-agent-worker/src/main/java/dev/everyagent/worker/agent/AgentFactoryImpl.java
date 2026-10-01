package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.agent.Agent;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.worker.modules.ConfigStore;
import dev.everyagent.worker.modules.ConfigStore.ResolvedConfig;
import dev.everyagent.worker.task.ChatModelFactory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Agent 工厂实现（worker 内部；plugin-api 契约见 {@link AgentFactory}）。
 *
 * <p>工厂内部自行解析配置（configId）并构建模型，不暴露 apiKey 等敏感参数给插件。
 * {@code create()} 返回 fluent {@link dev.everyagent.plugin.api.agent.AgentBuilder}，
 * 调用方可链式配置（title / tools / systemPrompt / userInput / options）后
 * {@code build()} 出 {@link Agent} 实例。
 *
 * <p>S2 起（§4.5）：创建主干是包内全参 {@link #create(String, String, ExecContext)}
 * ——接收 {@link ExecContext}，configId 为 null 时取绑定默认
 * {@code exec.snapshot().configId()}，emitter 固定取 {@code exec.emitter()}；
 * {@link #bind(ExecContext)} 据此产出绑定工厂静态代理（TaskBoundAgentFactory），
 * 挂到 {@code ExecContext.agentFactory()} 槽位，作为绑定工厂的唯一获取口。
 * S5 起本类不再 implements plugin-api {@code AgentFactory}（内部化：具体类 +
 * 全参方法仅供 worker 域内与绑定代理使用）。
 *
 * <p>设计要点：模型构建延迟到 {@code build()} 执行，以便 {@code options(Consumer)}
 * 在模型构建前生效——{@code buildAgentModel} 的 optionsCustomizer 参数由
 * {@code options(Consumer<ChatOptions>)} 转换而来。
 */
@Component
public class AgentFactoryImpl {

    private final ConfigStore configStore;
    private final ChatModelFactory chatModelFactory;
    /** worker 的 AgentBuilder（同包，无需 import）。 */
    private final AgentBuilder agentBuilder;

    public AgentFactoryImpl(ConfigStore configStore, ChatModelFactory chatModelFactory,
            AgentBuilder agentBuilder) {
        this.configStore = configStore;
        this.chatModelFactory = chatModelFactory;
        this.agentBuilder = agentBuilder;
    }

    /**
     * 全参创建（S2 内部主干，包内可见）：接收 {@link ExecContext}；
     * {@code configId = null} 时取绑定默认 {@code exec.snapshot().configId()}；
     * emitter 固定取 {@code exec.emitter()}（任务级事件口）。
     */
    dev.everyagent.plugin.api.agent.AgentBuilder create(
            String agentId, String configId, ExecContext exec) {
        String bound = configId != null ? configId : exec.snapshot().configId();
        return new AgentBuilderAdapter(agentId, bound, exec);
    }

    /**
     * 绑定 {@link ExecContext} 产出 {@link AgentFactory} 静态代理
     * （TaskBoundAgentFactory，包内类型）：TaskEntry.agentFactory() 经本方法获取，
     * 调用方只见 plugin-api 契约类型，不见代理实现类。
     */
    public AgentFactory bind(ExecContext exec) {
        return new TaskBoundAgentFactory(this, exec);
    }

    /**
     * 包装 worker 的 {@link AgentBuilder.Build}，实现 plugin-api 的
     * {@link dev.everyagent.plugin.api.agent.AgentBuilder} 接口。
     *
     * <p>延迟策略：所有 fluent 调用先暂存，{@code build()} 时一次性解析配置 →
     * 构建模型（含 options 定制）→ 创建 worker Build → 应用配置 → build。
     */
    private class AgentBuilderAdapter
            implements dev.everyagent.plugin.api.agent.AgentBuilder {

        private final String agentId;
        /** 已解析的配置 ID（null 已在入口回退为绑定默认值）。 */
        private final String configId;
        /** 本 agent 所属执行上下文（S2 起替代 emitter + properties 黑盒入参）。 */
        private final ExecContext exec;

        private String title = "";
        private Consumer<ChatOptions> optionsCustomizer;
        private List<ToolCallback> tools;
        private dev.everyagent.plugin.api.agent.AgentBuilder.ModifyMode toolsMode;
        private String systemPrompt;
        private String userInput;

        AgentBuilderAdapter(String agentId, String configId, ExecContext exec) {
            this.agentId = agentId;
            this.configId = configId;
            this.exec = exec;
        }

        @Override
        public dev.everyagent.plugin.api.agent.AgentBuilder title(String title) {
            this.title = title;
            return this;
        }

        @Override
        public dev.everyagent.plugin.api.agent.AgentBuilder tools(
                List<ToolCallback> tools,
                dev.everyagent.plugin.api.agent.AgentBuilder.ModifyMode mode) {
            this.tools = tools;
            this.toolsMode = mode;
            return this;
        }

        @Override
        public dev.everyagent.plugin.api.agent.AgentBuilder systemPrompt(String prompt) {
            this.systemPrompt = prompt;
            return this;
        }

        @Override
        public dev.everyagent.plugin.api.agent.AgentBuilder userInput(String input) {
            this.userInput = input;
            return this;
        }

        @Override
        public dev.everyagent.plugin.api.agent.AgentBuilder options(
                Consumer<ChatOptions> customizer) {
            this.optionsCustomizer = customizer;
            return this;
        }

        @Override
        public Agent build() {
            // 1. 解析配置（apiKey 留在 ResolvedConfig 内部，不暴露给调用方）
            ResolvedConfig cfg = configStore.resolve(configId);

            // 2. 转换 Consumer<ChatOptions> → UnaryOperator<OpenAiChatOptions>
            UnaryOperator<OpenAiChatOptions> customizer = toUnaryOperator(optionsCustomizer);

            // 3. 构建模型 + options 快照（emitter 取执行上下文的任务级事件口）
            ChatModelFactory.AgentModel am =
                    chatModelFactory.buildAgentModel(cfg, agentId, exec.emitter(), customizer);

            // 4. 创建 worker Build（聚合全部工具 + advisor 到内部列表）。
            //    configId 透传：保过渡 map 的 "configId" 键与本 agent 实际解析值一致
            //    （审议 agent 覆盖模型时与 exec.snapshot().configId() 不同）。
            AgentBuilder.Build build = agentBuilder.create(
                    agentId, am.chatModel(), am.options(), exec, configId);

            // 5. 应用 fluent 配置
            if (title != null && !title.isEmpty()) {
                build.title(title);
            }
            if (tools != null && toolsMode != null) {
                build.tools(tools, mapMode(toolsMode));
            }
            if (systemPrompt != null) {
                build.systemPrompt(systemPrompt);
            }
            if (userInput != null) {
                build.userInput(userInput);
            }

            // 6. 装配完成，返回 AgentEntity（已 implements Agent）
            return build.build();
        }
    }

    /**
     * 将 plugin-api 的 {@code Consumer<ChatOptions>} 转换为
     * {@code UnaryOperator<OpenAiChatOptions>}（buildAgentModel 所需格式）。
     * {@code OpenAiChatOptions} 实现 {@code ChatOptions}，可直接传入 Consumer。
     */
    private static UnaryOperator<OpenAiChatOptions> toUnaryOperator(
            Consumer<ChatOptions> consumer) {
        if (consumer == null) {
            return null;
        }
        return opts -> {
            consumer.accept(opts);
            return opts;
        };
    }

    /** plugin-api ModifyMode → worker ModifyMode 映射。 */
    private static AgentBuilder.ModifyMode mapMode(
            dev.everyagent.plugin.api.agent.AgentBuilder.ModifyMode mode) {
        return switch (mode) {
            case ADD -> AgentBuilder.ModifyMode.ADD;
            case REMOVE -> AgentBuilder.ModifyMode.REMOVE;
            case REPLACE -> AgentBuilder.ModifyMode.REPLACE;
        };
    }
}
