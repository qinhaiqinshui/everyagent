package dev.everyagent.worker.plugin.registry;

import dev.everyagent.worker.plugin.spi.ToolProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ToolProvider SPI 注册表。
 *
 * <p>核心改造点 C1（§6.1）：{@code TaskManager.buildMainAgent()} 从此注册表聚合工具，
 * 替代硬编码 {@code new AskUserTool()} / {@code new BashTool()} / {@code new FileTools()}。
 *
 * <p>注册时机：内置适配器在 Spring 启动时注册；外部插件在 {@code activate()} 时注册。
 */
@Component
public class ToolProviderRegistry {

    private final List<ToolProvider> providers = new CopyOnWriteArrayList<>();

    /** 注册一个 ToolProvider。 */
    public void register(ToolProvider provider) {
        providers.add(provider);
    }

    /** 注销一个 ToolProvider。 */
    public void unregister(ToolProvider provider) {
        providers.remove(provider);
    }

    /** 获取全部已注册的 ToolProvider。 */
    public List<ToolProvider> getProviders() {
        return new ArrayList<>(providers);
    }

    /**
     * 获取适用于主 agent 的 ToolProvider（scope != SUB）。
     */
    public List<ToolProvider> getForMain() {
        return providers.stream()
                .filter(p -> p.scope() != ToolProvider.Scope.SUB)
                .toList();
    }

    /**
     * 获取适用于子 agent 的 ToolProvider（scope != MAIN）。
     */
    public List<ToolProvider> getForSub() {
        return providers.stream()
                .filter(p -> p.scope() != ToolProvider.Scope.MAIN)
                .toList();
    }
}
