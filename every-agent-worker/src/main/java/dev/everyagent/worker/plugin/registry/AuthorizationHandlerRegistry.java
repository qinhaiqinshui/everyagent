package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AuthorizationHandler 注册表。
 *
 * <p>内置 handler（如 HumanAuthorizationHandler）通过 Spring @Component 创建后在
 * @PostConstruct 中 self-register；外部插件在 activate() 时通过
 * WorkerPluginContext.registerAuthorizationHandler() 注册。
 * 查询时按 order 排序输出。
 */
@Component
public class AuthorizationHandlerRegistry {

    private final List<AuthorizationHandler> handlers = new CopyOnWriteArrayList<>();

    public void register(AuthorizationHandler handler) {
        handlers.add(handler);
    }

    public void unregister(AuthorizationHandler handler) {
        handlers.remove(handler);
    }

    public List<AuthorizationHandler> sorted() {
        return handlers.stream()
                .sorted(Comparator.comparingDouble(AuthorizationHandler::order))
                .toList();
    }
}
