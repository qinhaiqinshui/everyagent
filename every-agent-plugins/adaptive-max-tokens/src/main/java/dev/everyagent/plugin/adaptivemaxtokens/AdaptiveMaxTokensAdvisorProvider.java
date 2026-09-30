package dev.everyagent.plugin.adaptivemaxtokens;

import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.plugin.api.task.TaskRuntime;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import tools.jackson.databind.JsonNode;

/**
 * {@link AdaptiveMaxTokensAdvisor} 适配器。
 *
 * <p>order = {@link ToolCallingAdvisor#DEFAULT_ORDER} + 250(Guard +300 外侧)。
 * 每 run 新建实例。
 *
 * <p>配置从 {@link WorkerConfig.Limits.AdaptiveMaxTokens} 读取;
 * 模型级 ceiling 覆盖:从 {@code agentEntity.task.snapshot.params} 中的
 * {@code maxTokensCeiling} 读取(若存在)。
 */
public class AdaptiveMaxTokensAdvisorProvider implements AdvisorProvider {

    private final WorkerConfig config;

    public AdaptiveMaxTokensAdvisorProvider(WorkerConfig config) {
        this.config = config;
    }

    @Override
    public String pluginId() {
        return "builtin.adaptive-max-tokens";
    }

    @Override
    public int order() {
        return ToolCallingAdvisor.DEFAULT_ORDER + 250;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentContext a = ctx.agentEntity();
        TaskRuntime t = (TaskRuntime) a.properties().get("taskEntry");
        WorkerConfig.Limits.AdaptiveMaxTokens cfg = config.limits().adaptiveMaxTokens();

        // 模型级 ceiling 覆盖:从 snapshot params 中的 maxTokensCeiling 获取。
        long ceiling = cfg.ceiling();
        ModelConfig snapshot = t.snapshot();
        if (snapshot != null && snapshot.params() != null) {
            JsonNode node = snapshot.params().get("maxTokensCeiling");
            if (node != null && node.isNumber()) {
                ceiling = node.asLong();
            }
        }

        return new AdaptiveMaxTokensAdvisor(a, cfg, ceiling);
    }
}
