package dev.everyagent.worker.ship;

import dev.everyagent.plugin.api.event.EventEgressFilter;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件级出网过滤器注册表(仿 {@code ToolExecutionInterceptorRegistry})。
 *
 * <p>内置过滤器经 Spring {@code @Component} 创建后在 {@code @PostConstruct} 中自注册;
 * 外部插件在 {@code activate()} 时通过 {@code WorkerPluginContext.registerEventEgressFilter()}
 * 注册。查询时按 {@link EventEgressFilter#order()} 升序稳定输出(同 order 保持注册顺序)。
 */
@Component
public class EventEgressFilterRegistry {

    private final List<EventEgressFilter> filters = new CopyOnWriteArrayList<>();

    public void register(EventEgressFilter filter) {
        filters.add(filter);
    }

    public void unregister(EventEgressFilter filter) {
        filters.remove(filter);
    }

    /** 按 order 升序稳定排序的过滤链快照。 */
    public List<EventEgressFilter> sorted() {
        return filters.stream()
                .sorted(Comparator.comparingInt(EventEgressFilter::order))
                .toList();
    }
}