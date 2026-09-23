package dev.everyagent.worker.plugin.registry;

import dev.everyagent.worker.plugin.spi.AgentDispatcher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class AgentDispatcherRegistry {

    private final List<AgentDispatcher> dispatchers = new CopyOnWriteArrayList<>();
    private final PluginStateStore stateStore;

    public AgentDispatcherRegistry(PluginStateStore stateStore) {
        this.stateStore = stateStore;
    }

    public void register(AgentDispatcher dispatcher) {
        dispatchers.add(dispatcher);
    }

    public void unregister(AgentDispatcher dispatcher) {
        dispatchers.remove(dispatcher);
    }

    public AgentDispatcher getDefault() {
        return dispatchers.stream()
                .filter(d -> !stateStore.isDisabled(d.id()))
                .findFirst()
                .orElse(null);
    }

    public AgentDispatcher getById(String id) {
        return dispatchers.stream()
                .filter(d -> d.id().equals(id))
                .filter(d -> !stateStore.isDisabled(d.id()))
                .findFirst()
                .orElse(null);
    }

    public List<AgentDispatcher> getProviders() {
        return dispatchers.stream()
                .filter(d -> !stateStore.isDisabled(d.id()))
                .toList();
    }
}
