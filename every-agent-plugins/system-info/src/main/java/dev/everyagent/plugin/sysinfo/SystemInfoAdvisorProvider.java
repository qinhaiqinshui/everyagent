package dev.everyagent.plugin.sysinfo;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.execution.ExecContext;
import java.nio.file.Path;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

/**
 * {@link SystemInfoAdvisor} 适配器。
 *
 * <p>order = {@link Ordered#HIGHEST_PRECEDENCE} + 50。每 run 新建实例。
 *
 * <p>沙箱信息通过 {@link WorkerServices#sandbox()} 获取 {@link SandboxBackend} 接口
 * （通过 {@code sandbox.id()} 判断后端类型）,不直接依赖 worker 的 {@code OsSandbox} 具体类。
 * 工作区路径经 {@link WorkerServices#toSandboxPath} 翻译为 AI 沙箱内可见路径
 * (如 WSL 后端 {@code C:\\...} → {@code /c/...}),由 {@code AgentBuilder} 在装配前
 * 注册到 {@code SandboxPathRegistry}。
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
        ExecContext exec = a.execution();
        SandboxBackend sandbox = services.sandbox();
        // 宿主工作区路径 → AI 沙箱内可见路径(经 SandboxPathRegistry 查表翻译;
        // 无映射时原样返回——DIRECT 场景或路径尚未注册)
        String shownWorkspace = exec.workspaceRoot();
        if (shownWorkspace != null && !shownWorkspace.isBlank()) {
            shownWorkspace = services.toSandboxPath(Path.of(shownWorkspace));
        }
        return new SystemInfoAdvisor(
                shownWorkspace,
                sandbox.id());
    }
}
