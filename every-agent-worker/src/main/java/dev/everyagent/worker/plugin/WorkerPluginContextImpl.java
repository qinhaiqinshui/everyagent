package dev.everyagent.worker.plugin;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.plugin.registry.AdvisorProviderRegistry;
import dev.everyagent.worker.plugin.registry.AgentDispatcherRegistry;
import dev.everyagent.worker.plugin.registry.AuthorizationHandlerRegistry;
import dev.everyagent.worker.plugin.registry.SandboxProviderRegistry;
import dev.everyagent.worker.plugin.registry.SearchProviderRegistry;
import dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry;
import dev.everyagent.worker.plugin.registry.ToolProviderRegistry;
import dev.everyagent.worker.plugin.spi.AdvisorProvider;
import dev.everyagent.worker.plugin.spi.AgentDispatcher;
import dev.everyagent.worker.plugin.spi.SandboxProvider;
import dev.everyagent.worker.plugin.spi.SearchProvider;
import dev.everyagent.worker.plugin.spi.ToolExecutionInterceptor;
import dev.everyagent.worker.plugin.spi.ToolProvider;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.slash.SlashCommandRegistry;
import dev.everyagent.worker.tools.PermissionGate;
import dev.everyagent.worker.tools.permission.AuthorizationHandler;

import java.util.Map;

/**
 * WorkerPluginContext 实现 —— 对标 VSCode 的 ExtensionContext。
 *
 * <p>每个外部插件在 activate() 时收到此实例，通过 register* 方法把自己的 SPI 实现
 * 注册到对应的核心注册表。核心不直接知道有哪些插件，只通过注册表聚合。
 */
public class WorkerPluginContextImpl implements WorkerPluginContext {

    private final String pluginId;
    private final AdvisorProviderRegistry advisorRegistry;
    private final ToolProviderRegistry toolRegistry;
    private final SandboxProviderRegistry sandboxRegistry;
    private final AgentDispatcherRegistry dispatcherRegistry;
    private final SearchProviderRegistry searchRegistry;
    private final AuthorizationHandlerRegistry authHandlerRegistry;
    private final ToolExecutionInterceptorRegistry toolInterceptorRegistry;
    private final RpcDispatcher rpcDispatcher;
    private final SlashCommandRegistry slashRegistry;
    private final WorkerServices services;
    private final PluginConfig config;

    public WorkerPluginContextImpl(String pluginId,
            AdvisorProviderRegistry advisorRegistry,
            ToolProviderRegistry toolRegistry,
            SandboxProviderRegistry sandboxRegistry,
            AgentDispatcherRegistry dispatcherRegistry,
            SearchProviderRegistry searchRegistry,
            AuthorizationHandlerRegistry authHandlerRegistry,
            ToolExecutionInterceptorRegistry toolInterceptorRegistry,
            RpcDispatcher rpcDispatcher,
            SlashCommandRegistry slashRegistry,
            WorkerServices services,
            PluginConfig config) {
        this.pluginId = pluginId;
        this.advisorRegistry = advisorRegistry;
        this.toolRegistry = toolRegistry;
        this.sandboxRegistry = sandboxRegistry;
        this.dispatcherRegistry = dispatcherRegistry;
        this.searchRegistry = searchRegistry;
        this.authHandlerRegistry = authHandlerRegistry;
        this.toolInterceptorRegistry = toolInterceptorRegistry;
        this.rpcDispatcher = rpcDispatcher;
        this.slashRegistry = slashRegistry;
        this.services = services;
        this.config = config;
    }

    @Override
    public String pluginId() {
        return pluginId;
    }

    @Override
    public void registerToolProvider(ToolProvider provider) {
        toolRegistry.register(provider);
    }

    @Override
    public void registerAdvisorProvider(AdvisorProvider provider) {
        advisorRegistry.register(provider);
    }

    @Override
    public void registerSandboxProvider(SandboxProvider provider) {
        sandboxRegistry.register(provider);
    }

    @Override
    public void registerAgentDispatcher(AgentDispatcher dispatcher) {
        dispatcherRegistry.register(dispatcher);
    }

    @Override
    public void registerSearchProvider(SearchProvider provider) {
        searchRegistry.register(provider);
    }

    @Override
    public void registerAuthorizationHandler(AuthorizationHandler handler) {
        authHandlerRegistry.register(handler);
    }

    @Override
    public void registerToolExecutionInterceptor(ToolExecutionInterceptor interceptor) {
        toolInterceptorRegistry.register(interceptor);
    }

    @Override
    public void registerRpcMethod(String method, RpcDispatcher.Method handler) {
        rpcDispatcher.register(method, handler);
    }

    @Override
    public void registerSlashProvider(String id, SlashCommandRegistry.SlashProvider provider) {
        slashRegistry.registerProvider(id, provider);
    }

    @Override
    public WorkerServices services() {
        return services;
    }

    @Override
    public PluginConfig config() {
        return config;
    }
}
