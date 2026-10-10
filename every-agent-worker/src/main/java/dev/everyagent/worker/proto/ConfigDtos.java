package dev.everyagent.worker.proto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;


/**
 * 模型与运行配置 DTO(架构 §5.8)。归 worker 所有。
 */
public final class ConfigDtos {

    private ConfigDtos() {
    }

    /**
     * 模型配置:provider + baseUrl + fullUrl + model,params 为自由 JSON(temperature 等)。apiKey 仅存 worker 侧。
     * fullUrl 为完整端点 URL(如 https://api.deepseek.com/chat/completions),非空时优先于 baseUrl。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModelConfig(
            String configId,
            String provider,
            String baseUrl,
            String fullUrl,
            String model,
            String apiKey,
            JsonNode params,
            Boolean isDefault) {

        /** 兼容旧调用方:fullUrl 为 null(未配置完整 URL)。 */
        public ModelConfig(String configId, String provider, String baseUrl,
                           String model, String apiKey, JsonNode params, Boolean isDefault) {
            this(configId, provider, baseUrl, null, model, apiKey, params, isDefault);
        }
    }

    /** worker 运行配置(sys.info 上报;任务永久保留,无 retention 概念)。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkerLimits(
            Integer maxConcurrentTasks,
            Long askTimeoutMs) {
    }
}
