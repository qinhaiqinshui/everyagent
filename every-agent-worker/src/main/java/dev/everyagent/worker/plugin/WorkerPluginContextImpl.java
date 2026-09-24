package dev.everyagent.worker.plugin;

import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.PluginConfig;
import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import dev.everyagent.plugin.api.rpc.RpcMethod;
import dev.everyagent.plugin.api.slash.SlashProvider;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.plugin.api.spi.SandboxProvider;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.plugin.api.task.TaskAdmissionPolicy;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.worker.plugin.registry.AdvisorProviderRegistry;
import dev.everyagent.worker.plugin.registry.AuthorizationHandlerRegistry;
import dev.everyagent.worker.plugin.registry.SandboxProviderRegistry;
import dev.everyagent.worker.plugin.registry.SearchProviderRegistry;
import dev.everyagent.worker.plugin.registry.TaskAdmissionPolicyRegistry;
import dev.everyagent.worker.plugin.registry.TaskLifecycleRegistry;
import dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry;
import dev.everyagent.worker.plugin.registry.ToolProviderRegistry;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.slash.SlashCommandRegistry;
import dev.everyagent.worker.slash.SlashCommandItem;

import java.util.ArrayList;
import java.util.List;
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
    private final SearchProviderRegistry searchRegistry;
    private final AuthorizationHandlerRegistry authHandlerRegistry;
    private final ToolExecutionInterceptorRegistry toolInterceptorRegistry;
    private final TaskLifecycleRegistry lifecycleRegistry;
    private final TaskAdmissionPolicyRegistry admissionPolicyRegistry;
    private final RpcDispatcher rpcDispatcher;
    private final SlashCommandRegistry slashRegistry;
    private final WorkerServices services;
    private final PluginConfig config;

    public WorkerPluginContextImpl(String pluginId,
            AdvisorProviderRegistry advisorRegistry,
            ToolProviderRegistry toolRegistry,
            SandboxProviderRegistry sandboxRegistry,
            SearchProviderRegistry searchRegistry,
            AuthorizationHandlerRegistry authHandlerRegistry,
            ToolExecutionInterceptorRegistry toolInterceptorRegistry,
            TaskLifecycleRegistry lifecycleRegistry,
            TaskAdmissionPolicyRegistry admissionPolicyRegistry,
            RpcDispatcher rpcDispatcher,
            SlashCommandRegistry slashRegistry,
            WorkerServices services,
            PluginConfig config) {
        this.pluginId = pluginId;
        this.advisorRegistry = advisorRegistry;
        this.toolRegistry = toolRegistry;
        this.sandboxRegistry = sandboxRegistry;
        this.searchRegistry = searchRegistry;
        this.authHandlerRegistry = authHandlerRegistry;
        this.toolInterceptorRegistry = toolInterceptorRegistry;
        this.lifecycleRegistry = lifecycleRegistry;
        this.admissionPolicyRegistry = admissionPolicyRegistry;
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
    public void registerTaskLifecycleNode(TaskLifecycleNode node) {
        lifecycleRegistry.register(node, pluginId);
    }

    @Override
    public void registerTaskAdmissionPolicy(TaskAdmissionPolicy policy) {
        admissionPolicyRegistry.register(policy);
    }

    @Override
    public void registerRpcMethod(String method, RpcMethod handler) {
        rpcDispatcher.register(method, ctx -> handler.handle(ctx));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void registerSlashProvider(String id, SlashProvider provider) {
        slashRegistry.registerProvider(id, () -> {
            List<?> items = provider.load();
            List<SlashCommandItem> result = new ArrayList<>(items.size());
            for (Object item : items) {
                result.add((SlashCommandItem) item);
            }
            return result;
        });
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
