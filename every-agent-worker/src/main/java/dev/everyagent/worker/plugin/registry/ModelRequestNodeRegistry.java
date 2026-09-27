package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.model.ModelRequestNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ModelRequestNode SPI 注册表。
 *
 * <p>模式同 {@link ToolProviderRegistry} / {@link TaskLifecycleRegistry}：
 * CopyOnWriteArrayList + float 稳定排序。
 * 注册时机：内置节点在 Spring 启动时由 {@code BuiltIn*} 注册；
 * 外部插件在 {@code activate()} 时经 {@code WorkerPluginContext.registerModelRequestNode} 注册。
 * 注册进来的都有效，查询直接返回全量。
 *
 * <p>{@code ChatModelFactory} 从此注册表取有序节点构造模型请求洋葱链。
 */
@Component
public class ModelRequestNodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRequestNodeRegistry.class);

    private final List<ModelRequestNode> nodes = new CopyOnWriteArrayList<>();

    /** 注册一个模型请求洋葱链节点。 */
    public void register(ModelRequestNode node) {
        nodes.add(node);
    }

    /** 取消注册。 */
    public void unregister(ModelRequestNode node) {
        nodes.remove(node);
    }

    /**
     * 获取全部有效节点（float 稳定排序）。
     * 稳定排序：同 order 按注册顺序（CopyOnWriteArrayList 自然保持插入序）。
     */
    public List<ModelRequestNode> getNodes() {
        List<ModelRequestNode> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparingDouble(ModelRequestNode::order));
        return sorted;
    }
}
