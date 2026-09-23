package dev.everyagent.worker.plugin.registry;

import dev.everyagent.worker.plugin.spi.SearchProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SearchProvider SPI 注册表。
 *
 * <p>核心改造点 C6（§6.6）：搜索 RPC 从此注册表选择引擎。
 * 阶段一仅定义接口和注册表，实际改造在阶段三。
 */
@Component
public class SearchProviderRegistry {

    private final List<SearchProvider> providers = new CopyOnWriteArrayList<>();

    public void register(SearchProvider provider) {
        providers.add(provider);
    }

    public void unregister(SearchProvider provider) {
        providers.remove(provider);
    }

    /** 获取默认搜索引擎（第一个注册的）。 */
    public SearchProvider getDefault() {
        return providers.isEmpty() ? null : providers.get(0);
    }

    /** 按 id 获取搜索引擎。 */
    public SearchProvider getById(String id) {
        return providers.stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    public List<SearchProvider> getProviders() {
        return new ArrayList<>(providers);
    }
}
