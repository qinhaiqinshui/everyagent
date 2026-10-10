package dev.everyagent.worker.ship;

import dev.everyagent.plugin.api.event.RoundEgressFilter;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 轮次级出网过滤器注册表(仿 {@code ToolExecutionInterceptorRegistry})。
 *
 * <p>内置过滤器经 Spring {@code @Component} 创建后在 {@code @PostConstruct} 中自注册;
 * 外部插件在 {@code activate()} 时通过 {@code WorkerPluginContext.registerRoundEgressFilter()}
 * 注册。查询时按 {@link RoundEgressFilter#order()} 升序稳定输出(同 order 保持注册顺序)。
 */
@Component
public class RoundEgressFilterRegistry {

    private final List<RoundEgressFilter> filters = new CopyOnWriteArrayList<>();

    public void register(RoundEgressFilter filter) {
        filters.add(filter);
    }

    public void unregister(RoundEgressFilter filter) {
        filters.remove(filter);
    }

    /** 按 order 升序稳定排序的过滤链快照。 */
    public List<RoundEgressFilter> sorted() {
        return filters.stream()
                .sorted(Comparator.comparingInt(RoundEgressFilter::order))
                .toList();
    }
}