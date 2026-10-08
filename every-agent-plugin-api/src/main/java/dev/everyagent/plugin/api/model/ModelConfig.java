package dev.everyagent.plugin.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.everyagent.plugin.api.config.WorkerConfig;
import tools.jackson.databind.JsonNode;

/**
 * 模型配置快照（原 worker 的 {@code ModelSnapshot}，搬到 plugin-api 改名）。
 *
 * <p>任务创建时定死，配置后续变更不影响运行中任务。插件可获取只读参考，
 * 用于限流参数解析、日志、自适应决策等。
 *
 * <p>注意：下游内核使用缓存的 ChatModel（按 configId 缓存 OpenAiChatModel），
 * 不消费此配置。修改此配置不影响实际请求。
 * 未来支持动态配置时，内核改为从 context 取模型（版本化缓存），接口不变。
 *
 * <p>{@code fullUrl} 为完整请求端点 URL（如 {@code https://api.deepseek.com/chat/completions}），
 * 非 null/非空时优先于 {@code baseUrl}：ChatModelFactory 据此反推 SDK 所需的 baseUrl
 * （剥离 {@code /chat/completions} 后缀），使 SDK 拼接后正好命中用户指定的完整 URL。
 * 为 null/空时回退 {@code baseUrl}（SDK 自行拼接默认路径）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelConfig(String configId, String provider, String baseUrl,
                          String fullUrl, String model, JsonNode params) {

    /**
     * 兼容旧调用方:fullUrl 为 null(未配置完整 URL,回退 baseUrl + SDK 默认路径)。
     */
    public ModelConfig(String configId, String provider, String baseUrl,
                       String model, JsonNode params) {
        this(configId, provider, baseUrl, null, model, params);
    }

    /**
     * 上下文窗口 token 数——{@code params.contextWindowTokens} 解析口径的唯一定义点:
     * 未配置/非法(≤0)时回退 {@link WorkerConfig#DEFAULT_CONTEXT_WINDOW_TOKENS}。
     * usage 事件载荷(worker 电池分母)、上下文压缩触发阈值、超限诊断共用此口径,防漂移。
     */
    public long contextWindowTokens() {
        JsonNode p = params();
        if (p != null && p.isObject() && p.has("contextWindowTokens")) {
            long v = p.path("contextWindowTokens").asLong(0);
            if (v > 0) {
                return v;
            }
        }
        return WorkerConfig.DEFAULT_CONTEXT_WINDOW_TOKENS;
    }
}
