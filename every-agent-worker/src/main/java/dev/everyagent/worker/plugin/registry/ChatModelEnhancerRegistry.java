package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.model.ChatModelEnhancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ChatModelEnhancer SPI 注册表。
 *
 * <p>CopyOnWriteArrayList 存储。
 * 注册时机：内置/外部插件在 {@code activate()} 时经
 * {@code WorkerPluginContext.registerChatModelEnhancer} 注册。
 *
 * <p>{@code ChatModelFactory} 从此注册表查找匹配的 enhancer 委托构建模型。
 */
@Component
public class ChatModelEnhancerRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatModelEnhancerRegistry.class);

    private final List<ChatModelEnhancer> enhancers = new CopyOnWriteArrayList<>();

    /** 注册一个模型构建增强器。 */
    public void register(ChatModelEnhancer enhancer) {
        enhancers.add(enhancer);
        log.info("[plugins] ChatModelEnhancer 已注册: {}", enhancer.id());
    }

    /**
     * 查找支持给定 provider 的 enhancer。
     *
     * @return 匹配的 enhancer，无匹配返回 null
     */
    public ChatModelEnhancer find(String provider) {
        for (ChatModelEnhancer e : enhancers) {
            if (e.supports(provider)) {
                return e;
            }
        }
        return null;
    }
}
