package dev.everyagent.plugin.adaptivemaxtokens;

import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.AdvisorContextImpl;
import dev.everyagent.worker.task.AgentEntity;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import tools.jackson.databind.JsonNode;

/**
 * {@link AdaptiveMaxTokensAdvisor} 适配器。
 *
 * <p>order = {@link ToolCallingAdvisor#DEFAULT_ORDER} + 250(Guard +300 外侧),
 * scope = BOTH(主/子 agent 同挂)。每 run 新建实例。
 *
 * <p>配置从 {@link WorkerProperties.Limits.AdaptiveMaxTokens} 读取;
 * 模型级 ceiling 覆盖:从 {@code agentEntity.task.snapshot.params} 中的
 * {@code maxTokensCeiling} 读取(若存在)。
 */
public class AdaptiveMaxTokensAdvisorProvider implements AdvisorProvider {

    private final WorkerProperties props;

    public AdaptiveMaxTokensAdvisorProvider(WorkerProperties props) {
        this.props = props;
    }

    @Override
    public String pluginId() {
        return "builtin.adaptive-max-tokens";
    }

    @Override
    public Scope scope() {
        return Scope.BOTH;
    }

    @Override
    public int order() {
        return ToolCallingAdvisor.DEFAULT_ORDER + 250;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentEntity a = ((AdvisorContextImpl) ctx).agentEntity();
        WorkerProperties.Limits.AdaptiveMaxTokens cfg = props.getLimits().getAdaptiveMaxTokens();

        // 模型级 ceiling 覆盖:从 snapshot params 中的 maxTokensCeiling 获取。
        long ceiling = cfg.getCeiling();
        ModelConfig snapshot = a.task.snapshot;
        if (snapshot != null && snapshot.params() != null) {
            JsonNode node = snapshot.params().get("maxTokensCeiling");
            if (node != null && node.isNumber()) {
                ceiling = node.asLong();
            }
        }

        return new AdaptiveMaxTokensAdvisor(a, cfg, ceiling);
    }
}
