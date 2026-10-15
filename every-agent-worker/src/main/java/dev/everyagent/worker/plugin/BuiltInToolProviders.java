package dev.everyagent.worker.plugin;

import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.plugin.adapters.DirectShellToolProvider;
import dev.everyagent.worker.plugin.adapters.FileToolsProvider;
import dev.everyagent.worker.plugin.registry.ToolProviderRegistry;
import dev.everyagent.worker.tools.FsToolSupport;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 内置工具提供者注册器。
 *
 * <p>Spring 启动时（@PostConstruct）把内置 ToolProvider 适配器注册到
 * {@link ToolProviderRegistry}，替代 TaskManager/SubAgentManager 中硬编码的工具创建。
 *
 * <p>适配器访问的依赖（FsToolSupport、OsSandbox）均为 Spring 单例，
 * 构造时注入并传给各适配器。ask_user 工具已迁出为 ask-user 插件
 * （every-agent-plugins/ask-user，经 ToolProvider SPI 同链装配）；rg 工具由
 * ripgrep-tool 插件提供（RipgrepBinary 仅供搜索模块 RgSearchEngine 使用）。
 */
@Component
public class BuiltInToolProviders {

    private final ToolProviderRegistry registry;
    private final FsToolSupport fs;
    private final OsSandbox sandbox;

    public BuiltInToolProviders(ToolProviderRegistry registry, FsToolSupport fs,
            OsSandbox sandbox) {
        this.registry = registry;
        this.fs = fs;
        this.sandbox = sandbox;
    }

    @PostConstruct
    void registerBuiltin() {
        registry.register(new FileToolsProvider(fs));
        registry.register(new DirectShellToolProvider(sandbox));
    }
}

