package dev.everyagent.plugin.sysinfo;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.task.TaskRuntime;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

/**
 * {@link SystemInfoAdvisor} 适配器。
 *
 * <p>order = {@link Ordered#HIGHEST_PRECEDENCE} + 50。每 run 新建实例。
 *
 * <p>沙箱信息通过 {@link WorkerServices#sandbox()} 获取 {@link SandboxBackend} 接口
 * （通过 {@code sandbox.id()} 判断后端类型）,不直接依赖 worker 的 {@code OsSandbox} 具体类。
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
    public int order() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentContext a = ctx.agentEntity();
        TaskRuntime t = (TaskRuntime) a.properties().get("taskEntry");
        SandboxBackend sandbox = services.sandbox();
        return new SystemInfoAdvisor(
                t.workspaceRoot(),
                sandbox.id());
    }
}
