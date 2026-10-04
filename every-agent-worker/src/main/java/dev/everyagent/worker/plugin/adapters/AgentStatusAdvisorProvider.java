package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.worker.agent.AgentEntity;
import dev.everyagent.worker.agent.AgentStatusAdvisor;
import dev.everyagent.worker.plugin.AdvisorContextImpl;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

/**
 * {@link AgentStatusAdvisor} 适配器（agent 生命周期事件发射，架构 §7.20.1）。
 *
 * <p>order = {@link Ordered#HIGHEST_PRECEDENCE} + 5 —— 全链最外层（比
 * {@code RoundIndexAdvisor}(+10) 更外，比工具循环 {@code WorkerToolEventAdvisor}
 * （{@code ToolCallingAdvisor.DEFAULT_ORDER} = +300）更外），
 * 于是 {@code adviseStream} 每次 {@code AgentRunner.run()} 恰好进入一次，
 * 终态回调晚于内层的 message/usage 与 rounds 闭合。
 *
 * <p>每 run 新建实例（持有 per-run {@link AgentEntity}）；主 agent 与各插件派生 agent
 * （子 agent、审议 agent）共用同一 advisor，不按 creator 分支。
 */
public class AgentStatusAdvisorProvider implements AdvisorProvider {

    @Override
    public String pluginId() {
        return "builtin.agent-status";
    }

    @Override
    public int order() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentEntity a = (AgentEntity) ((AdvisorContextImpl) ctx).agentEntity();
        return new AgentStatusAdvisor(a);
    }
}
