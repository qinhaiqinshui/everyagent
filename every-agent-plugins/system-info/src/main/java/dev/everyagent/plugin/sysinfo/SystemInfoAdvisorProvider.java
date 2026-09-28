package dev.everyagent.plugin.sysinfo;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.worker.plugin.AdvisorContextImpl;
import dev.everyagent.worker.task.AgentEntity;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

/**
 * {@link SystemInfoAdvisor} 适配器。
 *
 * <p>order = {@link Ordered#HIGHEST_PRECEDENCE} + 50，scope = BOTH（主/子 agent 同挂）。
 * 每 run 新建实例。
 *
 * <p>沙箱信息通过 {@link WorkerServices#sandbox()} 获取 {@link SandboxBackend} 接口
 * （已有 {@code isWslBackend()} / {@code isWslDirect()}），不直接依赖 worker 的 {@code OsSandbox} 具体类。
 */
public class SystemInfoAdvisorProvider implements AdvisorProvider {

    private final WorkerServices services;

    public SystemInfoAdvisorProvider(WorkerServices services) {
        this.services = services;
    }

    @Override
    public String pluginId() {
        return "builtin.system-info";
    }

    @Override
    public Scope scope() {
        return Scope.BOTH;
    }

    @Override
    public int order() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentEntity a = ((AdvisorContextImpl) ctx).agentEntity();
        SandboxBackend sandbox = services.sandbox();
        return new SystemInfoAdvisor(
                a.task.workspaceRoot,
                sandbox.isWslBackend(),
                sandbox.isWslDirect());
    }
}
