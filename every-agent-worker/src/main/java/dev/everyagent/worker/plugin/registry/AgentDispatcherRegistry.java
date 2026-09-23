package dev.everyagent.worker.plugin.registry;

import dev.everyagent.worker.plugin.spi.AgentDispatcher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AgentDispatcher SPI 注册表。
 *
 * <p>核心改造点 C4（§6.4）：子 agent 调度从此注册表选择策略。
 * 阶段一仅定义接口和注册表，实际改造在阶段三。
 */
@Component
public class AgentDispatcherRegistry {

    private final List<AgentDispatcher> dispatchers = new CopyOnWriteArrayList<>();

    public void register(AgentDispatcher dispatcher) {
        dispatchers.add(dispatcher);
    }

    public void unregister(AgentDispatcher dispatcher) {
        dispatchers.remove(dispatcher);
    }

    /** 获取默认调度策略（第一个注册的）。 */
    public AgentDispatcher getDefault() {
        return dispatchers.isEmpty() ? null : dispatchers.get(0);
    }

    /** 按 id 获取调度策略。 */
    public AgentDispatcher getById(String id) {
        return dispatchers.stream()
                .filter(d -> d.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    public List<AgentDispatcher> getProviders() {
        return new ArrayList<>(dispatchers);
    }
}
