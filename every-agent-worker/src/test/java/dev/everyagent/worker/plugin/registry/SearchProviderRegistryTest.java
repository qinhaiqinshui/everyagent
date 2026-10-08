package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.spi.FileNameSearchProvider;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.plugin.api.spi.SearchProvider.SearchRequest;
import dev.everyagent.plugin.api.spi.SearchProvider.SearchResult;
import dev.everyagent.plugin.api.spi.SearchProvider.TaskSearchRequest;
import dev.everyagent.plugin.api.spi.SearchProvider.TaskSearchResult;
import dev.everyagent.plugin.api.spi.SuggestionProvider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** 能力查询的 id 视图(getFileNameProviders / getSuggestionProviders 共用)。 */
    private static List<String> capabilityIds(List<? extends SearchProvider> providers) {
        return providers.stream().map(SearchProvider::id).toList();
    }

    /** 多能力桩:同一对象同时实现 FileNameSearchProvider 与 SuggestionProvider(基两方法恒空)。 */
    private static final class MultiCapabilityStub implements FileNameSearchProvider, SuggestionProvider {
        private final String id;
        private final float order;

        MultiCapabilityStub(String id, float order) {
            this.id = id;
            this.order = order;
        }

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

        @Override
        public List<FileNameSearchProvider.FileResult> findFiles(FileNameSearchProvider.FindRequest req) {
            return List.of();
        }

        @Override
        public List<Suggestion> suggest(SuggestRequest req) {
            return List.of();
        }
    }

    /** 仅 SuggestionProvider 能力的桩(乱序注册验证能力查询的 order 语义)。 */
    private static final class SuggestOnlyStub implements SuggestionProvider {
        private final String id;
        private final float order;

        SuggestOnlyStub(String id, float order) {
            this.id = id;
            this.order = order;
        }

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

        @Override
        public List<Suggestion> suggest(SuggestRequest req) {
            return List.of();
        }
    }

    /** 仅 FileNameSearchProvider 能力的桩(乱序注册验证能力查询的 order 语义)。 */
    private record NameOnlyStub(String id, float order) implements FileNameSearchProvider {
        @Override
        public List<SearchResult> searchFiles(SearchRequest req) {
            return List.of();
        }

        @Override
        public List<TaskSearchResult> searchTasks(TaskSearchRequest req) {
            return List.of();
        }

        @Override
        public List<FileNameSearchProvider.FileResult> findFiles(FileNameSearchProvider.FindRequest req) {
            return List.of();
        }

        @Override
        public float order() {
            return order;
        }
    }

    /**
     * 能力分派查询(§8.5 能力接口扩展):同一对象实现多个能力接口时两个查询都能拿到;
     * instanceof 过滤不混入仅基接口的实现;保持 order 升序(乱序注册同样得到升序);
     * 反注册走同一份存储(能力查询同步消失,无第二份残留)。
     */
    @Test
    void capabilityDispatchQueriesFilterByInterfaceFromSameOrderedList() {
        SearchProviderRegistry registry = new SearchProviderRegistry();
        SearchProvider plain = stub("plain", 0f);
        SearchProvider multi = new MultiCapabilityStub("multi", 10f);
        SearchProvider suggestOnly = new SuggestOnlyStub("suggest", 5f);
        SearchProvider nameOnly = new NameOnlyStub("name", -5f);
        // 乱序注册:能力查询仍按 order 升序(name -5 → plain 0 → suggest 5 → multi 10)
        registry.register(multi);
        registry.register(suggestOnly);
        registry.register(plain);
        registry.register(nameOnly);

        assertEquals(List.of("name", "plain", "suggest", "multi"), ids(registry));
        // 同一对象实现多能力接口:两个查询都能拿到
        assertEquals(List.of("name", "multi"), capabilityIds(registry.getFileNameProviders()));
        assertEquals(List.of("suggest", "multi"), capabilityIds(registry.getSuggestionProviders()));
        // 仅基接口实现不混入任何能力查询
        assertFalse(capabilityIds(registry.getFileNameProviders()).contains("plain"));
        assertFalse(capabilityIds(registry.getSuggestionProviders()).contains("plain"));

        // 反注册走同一份存储:能力查询同步消失(无第二份残留)
        registry.unregister(multi);
        assertTrue(registry.getFileNameProviders().contains(nameOnly));
        assertEquals(List.of("name"), capabilityIds(registry.getFileNameProviders()));
        assertEquals(List.of("suggest"), capabilityIds(registry.getSuggestionProviders()));
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
