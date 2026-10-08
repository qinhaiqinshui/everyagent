package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.plugin.api.spi.SearchProvider.SearchRequest;
import dev.everyagent.plugin.api.spi.SearchProvider.SearchResult;
import dev.everyagent.plugin.api.spi.SearchProvider.TaskSearchRequest;
import dev.everyagent.plugin.api.spi.SearchProvider.TaskSearchResult;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SearchProviderRegistry「有序插入 + 反注册」的回归测试。
 *
 * <p>锁死三条语义:
 * <ol>
 *   <li>注册时按 {@code order()} 升序<b>有序插入</b>(乱序注册也得到升序快照,
 *       消费方 fs.search / task.search 增补聚合直接按列表序执行);</li>
 *   <li>同 order 保持注册先后(稳定排序);{@code getDefault} 取 order 最小的先注册者;</li>
 *   <li>反注册(单个/批量)后未涉及项保留且顺序不变,清空后查询为空;
 *       批量反注册对已移除项幂等(返回 0)。</li>
 * </ol>
 */
class SearchProviderRegistryTest {

    @Test
    void outOfOrderRegistrationYieldsAscendingSnapshot() {
        SearchProviderRegistry registry = new SearchProviderRegistry();
        registry.register(stub("es", 200f));
        registry.register(stub("vector", 100f));
        registry.register(stub("default-a", 0f));
        registry.register(stub("front", -10f));

        assertEquals(List.of("front", "default-a", "vector", "es"), ids(registry));
        assertEquals("front", registry.getDefault().id());
    }

    @Test
    void sameOrderKeepsRegistrationSequence() {
        SearchProviderRegistry registry = new SearchProviderRegistry();
        registry.register(stub("a", 0f));
        registry.register(stub("b", 0f));
        registry.register(stub("c", 0f));
        assertEquals(List.of("a", "b", "c"), ids(registry));

        // 后注册的同 order 项追加在既有同 order 项之后(稳定)
        registry.register(stub("d", 0f));
        assertEquals(List.of("a", "b", "c", "d"), ids(registry));

        // order 更小者插到整组之前
        registry.register(stub("x", -1f));
        assertEquals(List.of("x", "a", "b", "c", "d"), ids(registry));
        assertEquals("x", registry.getDefault().id());
    }

    @Test
    void unregisterSingleKeepsRemainingOrder() {
        SearchProviderRegistry registry = new SearchProviderRegistry();
        SearchProvider a = stub("a", 0f);
        SearchProvider b = stub("b", 10f);
        SearchProvider c = stub("c", 20f);
        registry.register(a);
        registry.register(b);
        registry.register(c);

        registry.unregister(b);
        assertEquals(List.of("a", "c"), ids(registry));
        assertNull(registry.getById("b"));
        assertEquals("a", registry.getById("a").id());
    }

    @Test
    void unregisterAllEmptiesRegistryAndIsIdempotent() {
        SearchProviderRegistry registry = new SearchProviderRegistry();
        SearchProvider a = stub("a", 0f);
        SearchProvider b = stub("b", 5f);
        SearchProvider c = stub("c", 10f);
        registry.register(a);
        registry.register(b);
        registry.register(c);

        assertEquals(2, registry.unregisterAll(List.of(a, c)));
        assertEquals(List.of("b"), ids(registry));

        // 已移除项重复批量反注册返回 0(幂等);空集合为 no-op;未注册项忽略
        assertEquals(0, registry.unregisterAll(List.of(a, c)));
        assertEquals(0, registry.unregisterAll(List.of()));
        assertEquals(1, registry.unregisterAll(List.of(b, stub("ghost", 0f))));

        assertTrue(registry.getProviders().isEmpty());
        assertNull(registry.getDefault());
        assertNull(registry.getById("b"));
    }

    private static List<String> ids(SearchProviderRegistry registry) {
        return registry.getProviders().stream().map(SearchProvider::id).toList();
    }

    /** 最小 stub:只关心 id/order,两个搜索方法恒返回空列表。 */
    static SearchProvider stub(String id, float order) {
        return new SearchProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public float order() {
                return order;
            }

            @Override
            public List<SearchResult> searchFiles(SearchRequest req) {
                return List.of();
            }

            @Override
            public List<TaskSearchResult> searchTasks(TaskSearchRequest req) {
                return List.of();
            }
        };
    }
}
