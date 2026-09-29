package dev.everyagent.worker.plugin;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.plugin.adapters.AskUserToolProvider;
import dev.everyagent.worker.plugin.adapters.BashToolProvider;
import dev.everyagent.worker.plugin.adapters.FileToolsProvider;
import dev.everyagent.worker.plugin.adapters.PowerShellToolProvider;
import dev.everyagent.worker.plugin.registry.ToolProviderRegistry;
import dev.everyagent.worker.interaction.InteractionServiceImpl;
import dev.everyagent.worker.tools.FsToolSupport;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 内置工具提供者注册器。
 *
 * <p>Spring 启动时（@PostConstruct）把内置 ToolProvider 适配器注册到
 * {@link ToolProviderRegistry}，替代 TaskManager/SubAgentManager 中硬编码的工具创建。
 *
 * <p>适配器访问的依赖（InteractionServiceImpl、WorkerProperties、SubAgentManager、
 * FsToolSupport、OsSandbox）均为 Spring 单例，构造时注入并传给各适配器。
 */
@Component
public class BuiltInToolProviders {

    private final ToolProviderRegistry registry;
    private final FsToolSupport fs;
    private final OsSandbox sandbox;
    private final InteractionServiceImpl asks;
    private final WorkerProperties props;

    public BuiltInToolProviders(ToolProviderRegistry registry, FsToolSupport fs,
            OsSandbox sandbox, InteractionServiceImpl asks, WorkerProperties props) {
        this.registry = registry;
        this.fs = fs;
        this.sandbox = sandbox;
        this.asks = asks;
        this.props = props;
    }

    @PostConstruct
    void registerBuiltin() {
        registry.register(new FileToolsProvider(fs));
        registry.register(new BashToolProvider(sandbox));
        registry.register(new PowerShellToolProvider(sandbox));
        registry.register(new AskUserToolProvider(asks, props));
    }
}
