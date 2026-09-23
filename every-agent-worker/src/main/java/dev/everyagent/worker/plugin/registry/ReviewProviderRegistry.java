package dev.everyagent.worker.plugin.registry;

import dev.everyagent.worker.plugin.spi.ReviewProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ReviewProvider SPI 注册表。
 *
 * <p>核心改造点 C5（§6.5）：授权审议从此注册表选择策略。
 * 阶段一仅定义接口和注册表，实际改造在阶段三。
 */
@Component
public class ReviewProviderRegistry {

    private final List<ReviewProvider> providers = new CopyOnWriteArrayList<>();

    public void register(ReviewProvider provider) {
        providers.add(provider);
    }

    public void unregister(ReviewProvider provider) {
        providers.remove(provider);
    }

    /** 获取默认审议策略（第一个注册的）。 */
    public ReviewProvider getDefault() {
        return providers.isEmpty() ? null : providers.get(0);
    }

    /** 按 id 获取审议策略。 */
    public ReviewProvider getById(String id) {
        return providers.stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    public List<ReviewProvider> getProviders() {
        return new ArrayList<>(providers);
    }
}
