package dev.everyagent.worker.proto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 模型与运行配置 DTO(架构 §5.8)。归 worker 所有。
 */
public final class ConfigDtos {

    private ConfigDtos() {
    }

    /**
     * 模型配置:provider + baseUrl + model,params 为自由 JSON(temperature 等)。apiKey 仅存 worker 侧。
     * provider = model-pool 时该条是「容灾池」:model 是逗号分隔的池成员 configId 列表(首个 = 主模型),
     * members 为解析出的池成员 configId 列表(仅池配置有值,供前端展示);普通模型 members 恒为 null。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModelConfig(
            String configId,
            String provider,
            String baseUrl,
            String model,
            String apiKey,
            JsonNode params,
            Boolean isDefault,
            List<String> members) {
    }

    /** worker 运行配置(sys.info 上报;任务永久保留,无 retention 概念)。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkerLimits(
            Integer maxConcurrentTasks,
            Integer maxConcurrentSubs,
            Long askTimeoutMs,
            Long subWaitTimeoutMs) {
    }
}
