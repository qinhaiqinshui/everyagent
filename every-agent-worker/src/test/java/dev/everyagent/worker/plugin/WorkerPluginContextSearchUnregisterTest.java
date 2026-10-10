package dev.everyagent.worker.plugin;

import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.worker.plugin.registry.SearchProviderRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * WorkerPluginContextImpl「按插件登记 SearchProvider + 停用批量反注册」的回归测试
 * (插件停用路径:PluginLoader 在 deactivate() 后调 unregisterSearchProviders() 兜底)。
 *
 * <p>锁死:上下文只登记<b>本插件</b>经 registerSearchProvider 注册的 provider,
 * 批量反注册不影响其他插件经各自上下文注册的项;重复调用幂等。
 */
class WorkerPluginContextSearchUnregisterTest {

    @Test
    void unregisterSearchProvidersOnlyDropsOwnRegistrations() {
        SearchProviderRegistry registry = new SearchProviderRegistry();
        WorkerPluginContextImpl ctx = newContext("search-es", registry);
        ctx.registerSearchProvider(stub("slow", 200f));
        ctx.registerSearchProvider(stub("fast", -10f));

        // 另一插件(另一上下文)的 provider 不受本插件反注册影响
        WorkerPluginContextImpl other = newContext("search-vector", registry);
        other.registerSearchProvider(stub("stranger", 0f));

        assertEquals(List.of("fast", "stranger", "slow"), ids(registry));

        ctx.unregisterSearchProviders();
        assertEquals(List.of("stranger"), ids(registry));
        assertNull(registry.getById("fast"));
        assertNull(registry.getById("slow"));

        // 幂等:登记清单已清空,重复调用为 no-op
        ctx.unregisterSearchProviders();
        assertEquals(List.of("stranger"), ids(registry));

        // 各插件全部反注册后注册表为空
        other.unregisterSearchProviders();
        assertEquals(List.of(), ids(registry));
    }

    /** 构造只接通 SearchProviderRegistry 的最小上下文(其余依赖置 null,本用例不触及)。 */
    private static WorkerPluginContextImpl newContext(String pluginId, SearchProviderRegistry registry) {
        return new WorkerPluginContextImpl(pluginId, null,
                null, null, null, registry,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    /** 最小 stub:只关心 id/order(kinds/search 用接口缺省空实现)。 */
    private static SearchProvider stub(String id, float order) {
        return new SearchProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public float order() {
                return order;
            }
        };
    }

    private static List<String> ids(SearchProviderRegistry registry) {
        return registry.getProviders().stream().map(SearchProvider::id).toList();
    }
}