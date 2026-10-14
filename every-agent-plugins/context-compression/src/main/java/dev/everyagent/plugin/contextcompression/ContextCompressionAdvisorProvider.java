package dev.everyagent.plugin.contextcompression;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.plugin.api.spi.TokenEstimator;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * {@link ContextCompressionAdvisor} 适配器。
 *
 * <p>order = {@link ToolCallingAdvisor#DEFAULT_ORDER} + 400（工具循环最内层，每轮模型请求前压缩）。
 * 每 run 新建实例。开启摘要时注入 LLM 摘要器（需 a.chatModel），否则传 null 走确定性降级。
 *
 * <p><b>token 估算口径复用全局 {@link TokenEstimator}</b>（延迟解析，与 model-length-guard
 * 同款：不在构造时固化，每次 {@code create} 取当前生效实例，model-rate-limit 插件
 * {@code registerTokenEstimator} 替换后自动跟随）。走 {@link TokenEstimator#estimateInput}——
 * 压缩阈值是「窗口占用」，属<b>输入/prompt 口径</b>；而 {@code estimate} 带的是由
 * {@code completionTokens} 收敛出的<b>输出</b>校准系数，直接套用会把输入估算放大数倍
 * （实测某任务全量历史：基础式 28.9 万 ≈ 真实 33.9 万，套上系数 2.34 后变 67.4 万）。
 * 估算器缺失（理论上不会，worker 恒有 {@code SimpleTokenEstimator} 兜底）时传 null，
 * Advisor 回退内置 UTF-8 bytes/3。
 */
public class ContextCompressionAdvisorProvider implements AdvisorProvider {

    private final WorkerConfig config;
    private final WorkerServices services;

    public ContextCompressionAdvisorProvider(WorkerConfig config, WorkerServices services) {
        this.config = config;
        this.services = services; // 延迟解析的关键：不存 TokenEstimator 引用，存 WorkerServices
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
        // 延迟解析:每次 create 取当前生效的 TokenEstimator,构造其输入口径估算函数。
        TokenEstimator estimator = services.tokenEstimator();
        ContextCompressor.TextTokens tokens = estimator == null
                ? null
                : estimator::estimateInput;
        return new ContextCompressionAdvisor(a, limits, summarizer, tokens);
    }
}
