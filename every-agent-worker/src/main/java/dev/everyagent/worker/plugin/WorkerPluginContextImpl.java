package dev.everyagent.worker.plugin;

import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.PluginConfig;
import dev.everyagent.plugin.api.event.EventEgressFilter;
import dev.everyagent.plugin.api.event.RoundEgressFilter;
import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import dev.everyagent.plugin.api.rpc.RpcMethod;
import dev.everyagent.plugin.api.skill.SkillContributor;
import dev.everyagent.plugin.api.slash.SlashProvider;
import dev.everyagent.plugin.api.slash.SlashTokenResolver;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.plugin.api.spi.FileReferenceHandler;
import dev.everyagent.plugin.api.spi.SandboxProvider;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.plugin.api.spi.TokenEstimator;
import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.plugin.api.model.ChatModelEnhancer;
import dev.everyagent.plugin.api.task.TaskAdmissionPolicy;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.worker.plugin.registry.AdvisorProviderRegistry;
import dev.everyagent.worker.plugin.registry.AuthorizationHandlerRegistry;
import dev.everyagent.worker.plugin.registry.ChatModelEnhancerRegistry;
import dev.everyagent.worker.plugin.registry.FileReferenceHandlerRegistry;
import dev.everyagent.worker.plugin.registry.SandboxProviderRegistry;
import dev.everyagent.worker.plugin.registry.SearchProviderRegistry;
import dev.everyagent.worker.plugin.registry.SkillContributorRegistry;
import dev.everyagent.worker.plugin.registry.TaskAdmissionPolicyRegistry;
import dev.everyagent.worker.plugin.registry.TaskLifecycleRegistry;
import dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry;
import dev.everyagent.worker.plugin.registry.ToolProviderRegistry;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.ship.EventEgressFilterRegistry;
import dev.everyagent.worker.ship.RoundEgressFilterRegistry;
import dev.everyagent.worker.slash.SlashCommandRegistry;
import dev.everyagent.plugin.api.slash.SlashCommandItem;
import dev.everyagent.worker.slash.SlashTokenHandler;

import org.springframework.context.ApplicationContext;

import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WorkerPluginContext 实现 —— 对标 VSCode 的 ExtensionContext。
 *
 * <p>每个外部插件在 activate() 时收到此实例，通过 register* 方法把自己的 SPI 实现
 * 注册到对应的核心注册表。核心不直接知道有哪些插件，只通过注册表聚合。
 */
public class WorkerPluginContextImpl implements WorkerPluginContext {

    private final String pluginId;
    private final Path pluginDir;
    private final AdvisorProviderRegistry advisorRegistry;
    private final ToolProviderRegistry toolRegistry;
    private final SandboxProviderRegistry sandboxRegistry;
    private final SearchProviderRegistry searchRegistry;
    private final AuthorizationHandlerRegistry authHandlerRegistry;
    private final ToolExecutionInterceptorRegistry toolInterceptorRegistry;
    private final TaskLifecycleRegistry lifecycleRegistry;
    private final ChatModelEnhancerRegistry chatModelEnhancerRegistry;
    private final TaskAdmissionPolicyRegistry admissionPolicyRegistry;
    private final SkillContributorRegistry skillContributorRegistry;
    private final FileReferenceHandlerRegistry fileReferenceHandlerRegistry;
    private final EventEgressFilterRegistry eventEgressFilterRegistry;
    private final RoundEgressFilterRegistry roundEgressFilterRegistry;
    private final RpcDispatcher rpcDispatcher;
    private final SlashCommandRegistry slashRegistry;
    private final SlashTokenHandler slashTokenHandler;
    private final WorkerServices services;
    private final PluginConfig config;
    private final ApplicationContext applicationContext;

    /**
     * 本插件经此上下文注册的 SearchProvider 登记清单(pluginId → providers 映射的
     * pluginId 侧:每个上下文绑定唯一插件 id,实例内清单即该插件的映射值)。
     * 插件停用时由 PluginLoader 调 {@link #unregisterSearchProviders()} 批量反注册,
     * 防止禁用/卸载后 provider 在注册表残留。
     */
    private final List<SearchProvider> registeredSearchProviders = new CopyOnWriteArrayList<>();

    public WorkerPluginContextImpl(String pluginId,
            Path pluginDir,
            AdvisorProviderRegistry advisorRegistry,
            ToolProviderRegistry toolRegistry,
            SandboxProviderRegistry sandboxRegistry,
            SearchProviderRegistry searchRegistry,
            AuthorizationHandlerRegistry authHandlerRegistry,
            ToolExecutionInterceptorRegistry toolInterceptorRegistry,
            TaskLifecycleRegistry lifecycleRegistry,
            ChatModelEnhancerRegistry chatModelEnhancerRegistry,
            TaskAdmissionPolicyRegistry admissionPolicyRegistry,
            SkillContributorRegistry skillContributorRegistry,
            FileReferenceHandlerRegistry fileReferenceHandlerRegistry,
            EventEgressFilterRegistry eventEgressFilterRegistry,
            RoundEgressFilterRegistry roundEgressFilterRegistry,
            RpcDispatcher rpcDispatcher,
            SlashCommandRegistry slashRegistry,
            SlashTokenHandler slashTokenHandler,
            WorkerServices services,
            PluginConfig config,
            ApplicationContext applicationContext) {
        this.pluginId = pluginId;
        this.pluginDir = pluginDir;
        this.advisorRegistry = advisorRegistry;
        this.toolRegistry = toolRegistry;
        this.sandboxRegistry = sandboxRegistry;
        this.searchRegistry = searchRegistry;
        this.authHandlerRegistry = authHandlerRegistry;
        this.toolInterceptorRegistry = toolInterceptorRegistry;
        this.lifecycleRegistry = lifecycleRegistry;
        this.chatModelEnhancerRegistry = chatModelEnhancerRegistry;
        this.admissionPolicyRegistry = admissionPolicyRegistry;
        this.skillContributorRegistry = skillContributorRegistry;
        this.fileReferenceHandlerRegistry = fileReferenceHandlerRegistry;
        this.eventEgressFilterRegistry = eventEgressFilterRegistry;
        this.roundEgressFilterRegistry = roundEgressFilterRegistry;
        this.rpcDispatcher = rpcDispatcher;
        this.slashRegistry = slashRegistry;
        this.slashTokenHandler = slashTokenHandler;
        this.services = services;
        this.config = config;
        this.applicationContext = applicationContext;
    }

    @Override
    public String pluginId() {
        return pluginId;
    }

    @Override
    public Path pluginDir() {
        return pluginDir;
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
        registeredSearchProviders.add(provider);
    }

    /**
     * 反注册本插件经此上下文注册的全部 SearchProvider(worker 优雅关闭时由
     * PluginLoader 在插件 deactivate() 之后兜底调用,防止 provider 残留)。
     * 重复调用安全(登记清单清空后为 no-op)。
     */
    public void unregisterSearchProviders() {
        if (registeredSearchProviders.isEmpty()) {
            return;
        }
        searchRegistry.unregisterAll(registeredSearchProviders);
        registeredSearchProviders.clear();
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
    public void registerChatModelEnhancer(ChatModelEnhancer enhancer) {
        chatModelEnhancerRegistry.register(enhancer);
    }

    @Override
    public void registerTaskAdmissionPolicy(TaskAdmissionPolicy policy) {
        admissionPolicyRegistry.register(policy);
    }

    @Override
    public void registerTokenEstimator(TokenEstimator estimator) {
        // 外部插件注册的自定义 TokenEstimator 替换内置实现。
        ((WorkerServicesImpl) services).replaceTokenEstimator(estimator);
    }

    @Override
    public void registerSkillContributor(SkillContributor contributor) {
        skillContributorRegistry.register(contributor);
    }

    @Override
    public void registerFileReferenceHandler(FileReferenceHandler handler) {
        fileReferenceHandlerRegistry.register(handler);
    }

    @Override
    public void registerEventEgressFilter(EventEgressFilter filter) {
        eventEgressFilterRegistry.register(filter);
    }

    @Override
    public void registerRoundEgressFilter(RoundEgressFilter filter) {
        roundEgressFilterRegistry.register(filter);
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
    public void registerSlashTokenResolver(SlashTokenResolver resolver) {
        slashTokenHandler.registerResolver(new SlashTokenHandler.SlashTokenResolver() {
            @Override
            public String kind() {
                return resolver.kind();
            }

            @Override
            public String resolveSubmissionText(JsonNode payload) {
                return resolver.resolveSubmissionText(payload);
            }
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

    @Override
    public <T> T getService(Class<T> type) {
        return applicationContext.getBean(type);
    }
}
