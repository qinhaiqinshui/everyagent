package dev.everyagent.worker.plugin.registry;

import dev.everyagent.worker.plugin.spi.ReviewProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ReviewProviderRegistry {

    private final List<ReviewProvider> providers = new CopyOnWriteArrayList<>();
    private final PluginStateStore stateStore;

    public ReviewProviderRegistry(PluginStateStore stateStore) {
        this.stateStore = stateStore;
    }

    public void register(ReviewProvider provider) {
        providers.add(provider);
    }

    public void unregister(ReviewProvider provider) {
        providers.remove(provider);
    }

    public ReviewProvider getDefault() {
        return providers.stream()
                .filter(p -> !stateStore.isDisabled(p.id()))
                .findFirst()
                .orElse(null);
    }

    public ReviewProvider getById(String id) {
        return providers.stream()
                .filter(p -> p.id().equals(id))
                .filter(p -> !stateStore.isDisabled(p.id()))
                .findFirst()
                .orElse(null);
    }

    public List<ReviewProvider> getProviders() {
        return providers.stream()
                .filter(p -> !stateStore.isDisabled(p.id()))
                .toList();
    }
}
