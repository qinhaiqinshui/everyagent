package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.spi.FileNameSearchProvider;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.plugin.api.spi.SuggestionProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SearchProvider 注册表。
 *
 * <p>注册时按 {@link SearchProvider#order()} 升序<b>有序插入</b>(同 order 保持注册
 * 先后,稳定排序),消费方(fs.search / task.search 增补聚合)直接按列表序执行,
 * 无需查询期再排序。变更(注册/批量反注册)在内部锁下串行,查询走 CopyOnWrite
 * 快照、读侧无锁并发安全。
 *
 * <p><b>能力分派</b>(§8.5「能力接口扩展」):{@link FileNameSearchProvider}(fs.find)
 * 与 {@link SuggestionProvider}(mention.query)是 SearchProvider 的子接口,经同一
 * {@link #register} 入口注册;能力消费方经 {@link #getFileNameProviders()} /
 * {@link #getSuggestionProviders()} 从<b>同一份有序存储</b>按 instanceof 过滤取用
 * (不另建第二份存储,order/快照语义与 {@link #getProviders()} 一致)。
 *
 * <p>插件停用时由 WorkerPluginContext 侧按插件维度维护的登记清单批量反注册
 * ({@link #unregisterAll}),防止禁用/卸载后 provider 残留。
 */
@Component
public class SearchProviderRegistry {

    private final List<SearchProvider> providers = new CopyOnWriteArrayList<>();

    /**
     * 按 order 升序有序插入:插到首个 order 严格更大的既有 provider 之前,
     * 同 order 追加在其后(稳定,保持注册先后)。
     */
    public void register(SearchProvider provider) {
        synchronized (providers) {
            int index = providers.size();
            for (int i = 0; i < providers.size(); i++) {
                if (provider.order() < providers.get(i).order()) {
                    index = i;
                    break;
                }
            }
            providers.add(index, provider);
        }
    }

    public void unregister(SearchProvider provider) {
        providers.remove(provider);
    }

    /**
     * 批量反注册(插件停用路径):移除集合中已登记的全部 provider。
     *
     * @return 实际移除的数量(集合中未登记的项忽略)
     */
    public int unregisterAll(Collection<SearchProvider> registered) {
        if (registered == null || registered.isEmpty()) {
            return 0;
        }
        synchronized (providers) {
            int before = providers.size();
            providers.removeAll(registered);
            return before - providers.size();
        }
    }

    /** order 最小的 provider(同 order 取先注册者);注册表为空返回 null。 */
    public SearchProvider getDefault() {
        return providers.stream()
                .findFirst()
                .orElse(null);
    }

    public SearchProvider getById(String id) {
        return providers.stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    /** 全部 provider,按 order 升序(同 order 保持注册先后)排列的快照。 */
    public List<SearchProvider> getProviders() {
        return List.copyOf(providers);
    }

    /**
     * 能力分派查询(§8.5):实现 {@link FileNameSearchProvider} 能力接口的 provider,
     * 从同一有序列表按 instanceof 过滤,保持 order 升序(同 order 保持注册先后)与
     * 快照语义——fs.find 增补聚合的消费入口。
     */
    public List<FileNameSearchProvider> getFileNameProviders() {
        return providers.stream()
                .filter(FileNameSearchProvider.class::isInstance)
                .map(FileNameSearchProvider.class::cast)
                .toList();
    }

    /**
     * 能力分派查询(§8.5):实现 {@link SuggestionProvider} 能力接口的 provider,
     * 从同一有序列表按 instanceof 过滤,保持 order 升序(同 order 保持注册先后)与
     * 快照语义——mention.query 增补聚合的消费入口。
     */
    public List<SuggestionProvider> getSuggestionProviders() {
        return providers.stream()
                .filter(SuggestionProvider.class::isInstance)
                .map(SuggestionProvider.class::cast)
                .toList();
    }
}
