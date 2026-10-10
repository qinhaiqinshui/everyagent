package dev.everyagent.plugin.contextcompression;

import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * {@link ContextCompressionAdvisor} 适配器。
 *
 * <p>order = {@link ToolCallingAdvisor#DEFAULT_ORDER} + 400（工具循环最内层，每轮模型请求前压缩）。
 * 每 run 新建实例。开启摘要时注入 LLM 摘要器（需 a.chatModel），否则传 null 走确定性降级。
 */
public class ContextCompressionAdvisorProvider implements AdvisorProvider {

    private final WorkerConfig config;

    public ContextCompressionAdvisorProvider(WorkerConfig config) {
        this.config = config;
    }

    @Override
    public String pluginId() {
        return "builtin.context-compression";
    }

    @Override
    public int order() {
        return ToolCallingAdvisor.DEFAULT_ORDER + 400;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentContext a = ctx.agentEntity();
        WorkerConfig.Limits limits = config.limits();
        ContextSummarizer summarizer = limits.contextSummaryEnabled()
                ? new LlmContextSummarizer(a.chatModel(), limits.contextSummaryMaxTokens())
                : null;
        return new ContextCompressionAdvisor(a, limits, summarizer);
    }
}
