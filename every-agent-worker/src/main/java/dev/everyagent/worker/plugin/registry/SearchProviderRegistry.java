package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.spi.SearchProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class SearchProviderRegistry {

    private final List<SearchProvider> providers = new CopyOnWriteArrayList<>();
    private final PluginStateStore stateStore;

    public SearchProviderRegistry(PluginStateStore stateStore) {
        this.stateStore = stateStore;
    }

    public void register(SearchProvider provider) {
        providers.add(provider);
    }

    public void unregister(SearchProvider provider) {
        providers.remove(provider);
    }

    public SearchProvider getDefault() {
        return providers.stream()
                .filter(p -> !stateStore.isDisabled(p.id()))
                .findFirst()
                .orElse(null);
    }

    public SearchProvider getById(String id) {
        return providers.stream()
                .filter(p -> p.id().equals(id))
                .filter(p -> !stateStore.isDisabled(p.id()))
                .findFirst()
                .orElse(null);
    }

    public List<SearchProvider> getProviders() {
        return providers.stream()
                .filter(p -> !stateStore.isDisabled(p.id()))
                .toList();
    }
}
