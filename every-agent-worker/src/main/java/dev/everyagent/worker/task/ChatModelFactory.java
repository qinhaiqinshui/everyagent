package dev.everyagent.worker.task;

import dev.everyagent.plugin.api.model.ChatModelEnhancer;
import dev.everyagent.plugin.api.model.EnhancedChatModel;
import dev.everyagent.plugin.api.model.EnhancerContext;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.MemberSpec;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.model.ModelRequestNode;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.ConfigStore.ResolvedConfig;
import dev.everyagent.worker.plugin.registry.ChatModelEnhancerRegistry;
import dev.everyagent.worker.plugin.registry.ModelRequestNodeRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;



/**
 * 按配置快照构建 ChatModel(OpenAI 兼容协议,覆盖 deepseek/qwen/glm 等)。
 * 每任务/每 agent 一个实例;streamUsage 开启以便流式最后一帧带 usage。
 * Spring AI 2.0.1 起 prompt 携带 options 时不再与模型默认 options 合并
 * (buildRequestPrompt 原样透传并强转 OpenAiChatOptions),故每轮请求必须复用
 * options() 产出的完整快照,仅在其上追加工具集。
 *
 * <p>{@code provider: model-pool} 池配置经 {@link #buildAgentModel} 委托给
 * {@link ChatModelEnhancer} 插件构建(产出组合 ChatModel,按序容灾切换),
 * 普通配置照旧产出 {@link OpenAiChatModel}。
 */
@Component
public class ChatModelFactory {

    /** agent 装配结果:chatModel + 每轮 prompt 的 options 基底(池配置取主成员快照)。 */
    public record AgentModel(ChatModel chatModel, OpenAiChatOptions options) {
    }

    private final WorkerProperties props;
    private final ModelRequestNodeRegistry nodeRegistry;
    private final ChatModelEnhancerRegistry enhancerRegistry;
    /**
     * 缓存:按 configId 复用 OpenAiChatModel 实例。openai-java SDK 每次构建
     * OpenAIClient 都会创建新的 Timer("DefaultSleeper") + streamHandler 线程池
     * + OkHttp 连接池,任务结束后不释放 → 线程泄漏(见线程分析)。缓存后同一配置
     * 只创建一次,所有 agent 共用底层 OkHttp 客户端和线程资源。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, OpenAiChatModel> modelCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ChatModelFactory(WorkerProperties props,
            ModelRequestNodeRegistry nodeRegistry,
            ChatModelEnhancerRegistry enhancerRegistry) {
        this.props = props;
        this.nodeRegistry = nodeRegistry;
        this.enhancerRegistry = enhancerRegistry;
    }

    /**
     * 构建 agent 模型 + 请求 options。
     * <ul>
     *   <li>普通配置:chatModel = {@link OpenAiChatModel},options = {@link #options} 完整快照;</li>
     *   <li>池配置({@link ResolvedConfig#isPool()}):chatModel = 模型池插件产出的组合 ChatModel,
     *       options = 主成员完整快照——上下文压缩等 advisor 读 prompt.options 拿到主模型参数。</li>
     * </ul>
     *
     * @param cfg               任务/审议解析出的配置(池配置需已填充 poolMembers)
     * @param agentId           日志归属 agent id
     * @param events            任务事件发射器(池模型发 model_failover trace;普通模型忽略)
     * @param optionsCustomizer 可选:对每个成员/普通模型的 options 统一定制(如审议 timeout 覆盖);null 不覆盖
     */
    public AgentModel buildAgentModel(ResolvedConfig cfg, String agentId, TaskEvents events,
            UnaryOperator<OpenAiChatOptions> optionsCustomizer) {
        if (!cfg.isPool()) {
            OpenAiChatOptions options = apply(options(cfg), optionsCustomizer);
            return new AgentModel(build(cfg, options, agentId, events), options);
        }
        // 池配置 → 委托给 ChatModelEnhancer 插件构建(组合 ChatModel + 主成员 options)
        ChatModelEnhancer enhancer = enhancerRegistry.find(cfg.snapshot().provider());
        if (enhancer == null) {
            throw new IllegalStateException(
                    "provider '" + cfg.snapshot().provider() + "' 需要插件支持，但当前未注册 ChatModelEnhancer");
        }
        // buildMember 回调委托到 ChatModelFactory.build()(复用工厂的成员构建逻辑)。
        UnaryOperator<OpenAiChatOptions> memberCustomizer = optionsCustomizer;
        EnhancerContext ctx = new EnhancerContext() {
            @Override
            public ModelConfig poolConfig() {
                return cfg.snapshot();
            }

            @Override
            public List<MemberSpec> members() {
                List<MemberSpec> specs = new ArrayList<>(cfg.poolMembers().size());
                for (ResolvedConfig m : cfg.poolMembers()) {
                    specs.add(new MemberSpec(m.snapshot(), m.apiKey()));
                }
                return specs;
            }

            @Override
            public String agentId() {
                return agentId;
            }

            @Override
            public EventEmitter events() {
                return event -> {
                    if (events != null) {
                        events.emit(event);
                    }
                };
            }

            @Override
            public ChatModel buildMember(MemberSpec member) {
                ResolvedConfig resolvedCfg = new ResolvedConfig(member.config(), member.apiKey());
                OpenAiChatOptions o = apply(options(resolvedCfg), memberCustomizer);
                return build(resolvedCfg, o, agentId, events);
            }
        };
        EnhancedChatModel enhanced = enhancer.enhance(ctx);
        OpenAiChatOptions primaryOptions = (OpenAiChatOptions) enhanced.primaryOptions();
        return new AgentModel(enhanced.chatModel(), primaryOptions);
    }

    private static OpenAiChatOptions apply(OpenAiChatOptions base,
            UnaryOperator<OpenAiChatOptions> customizer) {
        return customizer == null ? base : customizer.apply(base);
    }

    /**
     * 构建 ChatModel(OpenAI 兼容协议;无事件上下文,events=null)。
     */
    public ChatModel build(ResolvedConfig cfg, OpenAiChatOptions options, String agentId) {
        return build(cfg, options, agentId, null);
    }

    /**
     * 构建 ChatModel(OpenAI 兼容协议)。HTTP 层挂 {@link HttpRequestLoggingInterceptor}
     * 打印真实请求体(含 skill 渐进式披露索引等 advisor 注入后的完整报文);
     * agentId 仅用于日志前缀标识。
     *
     * <p>构建模型请求洋葱链({@link ModelRequestChainChatModel} 薄壳):
     * 从 {@link ModelRequestNodeRegistry} 取有序 nodes,构造 {@link EventEmitter}
     * 闭包(转发到 {@link TaskEvents#emit})。若无节点则返回 raw model(直通)。
     * 限流逻辑由插件经 {@link ModelRequestNode} 注入(步骤 4 搬迁)。
     * events 可空(无事件上下文时用 no-op emitter)。
     */
    public ChatModel build(ResolvedConfig cfg, OpenAiChatOptions options, String agentId,
            TaskEvents events) {
        String cacheKey = cfg.snapshot().configId();
        // 缓存 raw OpenAiChatModel:同一 configId 的所有 agent 复用同一 OkHttp 客户端、
        // Timer 和 streamHandler 线程池,避免每次 build 创建新客户端导致线程泄漏。
        OpenAiChatModel raw = modelCache.computeIfAbsent(cacheKey, k ->
                OpenAiChatModel.builder()
                        .options(options)
                        .httpClientBuilderCustomizer(b -> b
                                // 打印真实请求体(含 skill 渐进式披露索引等 advisor 注入后的完整报文)
                                .interceptor(HttpRequestLoggingInterceptor.SHARED)
                                // 解除 okhttp callTimeout 总时长上限(§7.4.2):流式长思考不限总时长,
                                // 静默由 readTimeout + ModelLengthGuardAdvisor stall 兜底。
                                .interceptor(StreamTimeoutReleaseInterceptor.INSTANCE))
                        .build());
        // 构造模型请求洋葱链
        List<ModelRequestNode> nodes = nodeRegistry.getNodes();
        if (nodes.isEmpty()) {
            // 无节点 → 直通 raw model(现状兼容)
            return raw;
        }
        ModelConfig config = cfg.snapshot();
        EventEmitter emitter = events == null
                ? (event) -> {} // no-op
                : (event) -> events.emit(event);
        return new ModelRequestChainChatModel(raw, nodes, emitter, config);
    }

    /**
     * 停机清理:关闭缓存的 OpenAiChatModel,释放底层 OkHttp 客户端及其线程。
     * Spring AI 的 OpenAiChatModel 不直接暴露 close(),但底层 SpringAiOpenAiHttpClient
     * 实现了 close()——通过反射或_gc_ 释放。目前依赖 JVM 停机时线程自动销毁;
     * 缓存化后线程数从无限增长变为固定(每个 configId 一组线程),已解决泄漏。
     */
    @jakarta.annotation.PreDestroy
    void shutdown() {
        modelCache.clear();
    }

    /** 完整请求参数快照:baseUrl/apiKey/model/流式与采样参数,随 prompt 逐轮透传。 */
    public OpenAiChatOptions options(ResolvedConfig cfg) {        OpenAiChatOptions.Builder b = OpenAiChatOptions.builder()
                .baseUrl(cfg.snapshot().baseUrl())
                .apiKey(cfg.apiKey())
                .model(cfg.snapshot().model())
                .streamUsage(true)
                // 关闭 openai-java 内置 HTTP 重试:瞬时错误重试统一由 advisor 层
                // TransientErrorRetryAdvisor 负责(退避/日志/次数更可控),避免双层重试叠加、
                // 以及同一请求在 HTTP 层重复打印(每次重试都是一次新请求)。
                .maxRetries(0)
                // spring-ai OpenAiChatOptions 默认 timeout=60s(AbstractOpenAiOptions.DEFAULT_TIMEOUT)。
                // 注意:该单值在 openai-java 映射为 okhttp callTimeout(调用总时长上限,§7.4.2),
                // 已由 StreamTimeoutReleaseInterceptor 对流式解除;此处保留 worker.model-timeout-ms
                // 作为读间隔超时的期望基准(reasoning 模型深度思考期间 chunk 间隔可能较长)。
                .timeout(Duration.ofMillis(props.getModelTimeoutMs()));
        JsonNode params = cfg.snapshot().params();
        if (params != null && params.isObject()) {
            if (params.has("temperature")) {
                b.temperature(params.path("temperature").asDouble());
            }
            if (params.has("topP")) {
                b.topP(params.path("topP").asDouble());
            }
            if (params.has("maxTokens")) {
                b.maxTokens((int) params.path("maxTokens").asLong());
            }
            if (params.has("frequencyPenalty")) {
                b.frequencyPenalty(params.path("frequencyPenalty").asDouble());
            }
            if (params.has("presencePenalty")) {
                b.presencePenalty(params.path("presencePenalty").asDouble());
            }
            if (params.has("reasoningEffort")) {
                b.reasoningEffort(params.path("reasoningEffort").asText());
            }
        }
        return b.build();
    }
}
