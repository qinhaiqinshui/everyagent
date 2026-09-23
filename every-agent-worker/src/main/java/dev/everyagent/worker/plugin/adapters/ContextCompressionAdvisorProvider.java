package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.AdvisorContextImpl;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.worker.task.AgentEntity;
import dev.everyagent.worker.task.ContextCompressionAdvisor;
import dev.everyagent.worker.task.ContextSummarizer;
import dev.everyagent.worker.task.LlmContextSummarizer;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * {@link ContextCompressionAdvisor} 适配器。
 *
 * <p>order = {@link ToolCallingAdvisor#DEFAULT_ORDER} + 400（工具循环最内层，每轮模型请求前压缩），
 * scope = BOTH（主/子 agent 同挂）。每 run 新建实例。
 * 开启摘要时注入 LLM 摘要器（需 a.chatModel），否则传 null 走确定性降级。
 */
public class ContextCompressionAdvisorProvider implements AdvisorProvider {

    private final WorkerProperties props;

    public ContextCompressionAdvisorProvider(WorkerProperties props) {
        this.props = props;
    }

    @Override
    public String pluginId() {
        return "builtin.context-compression";
    }

    @Override
    public Scope scope() {
        return Scope.BOTH;
    }

    @Override
    public int order() {
        return ToolCallingAdvisor.DEFAULT_ORDER + 400;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentEntity a = ((AdvisorContextImpl) ctx).agentEntity();
        WorkerProperties.Limits limits = props.getLimits();
        ContextSummarizer summarizer = limits.isContextSummaryEnabled()
                ? new LlmContextSummarizer(a.chatModel, limits.getContextSummaryMaxTokens())
                : null;
        return new ContextCompressionAdvisor(a, limits, summarizer);
    }
}
