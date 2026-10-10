package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TaskLifecycleNode SPI 注册表（第 8 个注册表）。
 *
 * <p>模式同 ToolProviderRegistry / AdvisorProviderRegistry：
 * CopyOnWriteArrayList + float 稳定排序。
 * 注册时机：内置节点在 Spring 启动时由 BuiltInTaskLifecycleNodes 注册；
 * 外部插件在 activate() 时注册。
 * 注册进来的都有效，查询直接返回全量。
 *
 * <p>顺序约束：850..420 区间不允许插件节点插入（§7.3 锁策略），
 * 注册时检测并打 WARN 拒绝。
 */
@Component
public class TaskLifecycleRegistry {

    private static final Logger log = LoggerFactory.getLogger(TaskLifecycleRegistry.class);

    /** 临界段 order 范围：插件节点不允许落入此区间（内置节点的锁内连续段）。 */
    private static final float CRITICAL_SECTION_MIN = 420f;
    private static final float CRITICAL_SECTION_MAX = 850f;

    private final List<TaskLifecycleNode> nodes = new CopyOnWriteArrayList<>();

    /**
     * 注册一个生命周期节点。
     * <p>内置节点（pluginId 为 "worker" 或类似核心标识）不受临界段区间限制。
     * 外部插件节点若 order ∈ [420, 850]，打 WARN 并拒绝注册。
     */
    public void register(TaskLifecycleNode node, String pluginId) {
        if (pluginId != null && !pluginId.isEmpty()
                && !"worker".equals(pluginId)
                && node.order() >= CRITICAL_SECTION_MIN
                && node.order() <= CRITICAL_SECTION_MAX) {
            log.warn("插件 {} 尝试注册生命周期节点 {} 的 order={} 落入临界段 [420,850]，已拒绝",
                    pluginId, node.id(), node.order());
            return;
        }
        nodes.add(node);
    }

    /** 取消注册。 */
    public void unregister(TaskLifecycleNode node) {
        nodes.remove(node);
    }

    /**
     * 获取全部有效节点（float 稳定排序）。
     * 稳定排序：同 order 按注册顺序（CopyOnWriteArrayList 自然保持插入序）。
     */
    public List<TaskLifecycleNode> getNodes() {
        List<TaskLifecycleNode> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparingDouble(TaskLifecycleNode::order));
        return sorted;
    }
}
