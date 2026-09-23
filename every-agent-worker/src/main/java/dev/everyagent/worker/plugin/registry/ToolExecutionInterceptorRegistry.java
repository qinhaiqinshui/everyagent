package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ToolExecutionInterceptor 注册表。
 *
 * <p>内置 interceptor 通过 Spring @Component 创建后在 @PostConstruct 中 self-register；
 * 外部插件在 activate() 时通过 WorkerPluginContext.registerToolExecutionInterceptor() 注册。
 * 查询时按 order 排序输出。
 */
@Component
public class ToolExecutionInterceptorRegistry {

    private final List<ToolExecutionInterceptor> interceptors = new CopyOnWriteArrayList<>();

    public void register(ToolExecutionInterceptor interceptor) {
        interceptors.add(interceptor);
    }

    public void unregister(ToolExecutionInterceptor interceptor) {
        interceptors.remove(interceptor);
    }

    public List<ToolExecutionInterceptor> sorted() {
        return interceptors.stream()
                .sorted(Comparator.comparingInt(ToolExecutionInterceptor::order))
                .toList();
    }
}
