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
 * <p><b>token 估算走全局 {@link TokenEstimator} 单一口径</b>（延迟解析，与 model-length-guard
 * 同款：不在构造时固化，每次 {@code create} 取当前生效实例，model-rate-limit 插件
 * {@code registerTokenEstimator} 替换后自动跟随）。压缩阈值是「窗口占用」，直接调
 * {@link TokenEstimator#estimate}——输出侧系数已修正（校准统计纳入工具调用参数、消除自反馈）
 * 后误差 ~5%，`estimate` 对输入同样适用（分词与方向无关）；输入侧的残余偏差由压缩自身的
 * offset 校准承担（`overhead = 实测 inputTokens − 粗估(lastSent)`，逐 agent 每轮对账）。
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
        // 延迟解析:每次 create 取当前生效的 TokenEstimator,构造其估算函数(单一口径)。
        // configId 用本次 run 的 ctx.configId(),取该模型已收敛的系数。
        TokenEstimator estimator = services.tokenEstimator();
        String configId = ctx.configId();
        ContextCompressor.TextTokens tokens = estimator == null
                ? null
                : text -> estimator.estimate(text, configId);
        return new ContextCompressionAdvisor(a, limits, summarizer, tokens);
    }
}
