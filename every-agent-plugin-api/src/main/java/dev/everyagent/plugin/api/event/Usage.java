package dev.everyagent.plugin.api.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 模型调用 usage 记录(从 worker 的 TaskDtos.Usage 拆出,供 plugin-api 共享)。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Usage(long inputTokens, long outputTokens, long totalTokens) {

    public static Usage zero() {
        return new Usage(0, 0, 0);
    }

    public Usage plus(Usage other) {
        return new Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                totalTokens + other.totalTokens);
    }
}
