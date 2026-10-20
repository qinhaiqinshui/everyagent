package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.worker.agent.FirstChunkWatchdogAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * {@link FirstChunkWatchdogAdvisor} 的提供者(架构 §7.4.2 首帧看门狗)。
 *
 * <p>order = {@link ToolCallingAdvisor#DEFAULT_ORDER} + 550 —— 全链最内层
 * (RateLimit +500 之内,紧贴模型 HTTP 调用):限流排队不消耗看门狗预算,
 * 工具循环每轮递归各自享有独立首帧窗口。
 *
 * <p>advisor 无 per-run 状态(仅两段时长配置),每 run 新建实例仅为与既有
 * provider 模式一致;主 agent 与全部派生 agent(子/审议)同挂。
 */
public class FirstChunkWatchdogAdvisorProvider implements AdvisorProvider {

    private final long firstChunkMs;
    private final long nextChunkMs;

    public FirstChunkWatchdogAdvisorProvider(long firstChunkMs, long nextChunkMs) {
        this.firstChunkMs = firstChunkMs;
        this.nextChunkMs = nextChunkMs;
    }

    @Override
    public String pluginId() {
        return "builtin.first-chunk-watchdog";
    }

    @Override
    public int order() {
        return ToolCallingAdvisor.DEFAULT_ORDER + 550;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        return new FirstChunkWatchdogAdvisor(firstChunkMs, nextChunkMs);
    }
}
