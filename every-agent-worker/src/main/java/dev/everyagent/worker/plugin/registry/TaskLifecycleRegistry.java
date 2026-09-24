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
 * CopyOnWriteArrayList + PluginStateStore 过滤 + float 稳定排序。
 * 注册时机：内置节点在 Spring 启动时由 BuiltInTaskLifecycleNodes 注册；
 * 外部插件在 activate() 时注册。
 * 禁用过滤：查询时经 PluginStateStore 过滤掉被禁用的节点所属插件。
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
    private final PluginStateStore stateStore;

    public TaskLifecycleRegistry(PluginStateStore stateStore) {
        this.stateStore = stateStore;
    }

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
     * 获取全部有效节点（经 PluginStateStore 过滤 + float 稳定排序）。
     * 稳定排序：同 order 按注册顺序（CopyOnWriteArrayList 自然保持插入序）。
     */
    public List<TaskLifecycleNode> getNodes() {
        List<TaskLifecycleNode> filtered = new ArrayList<>();
        for (TaskLifecycleNode n : nodes) {
            // 内置节点（pluginId 为 null 或 "worker"）不过滤
            String pluginId = nodePluginId(n);
            if (pluginId != null && !"worker".equals(pluginId) && stateStore.isDisabled(pluginId)) {
                continue;
            }
            filtered.add(n);
        }
        // 稳定排序：同 order 保持注册顺序
        filtered.sort(Comparator.comparingDouble(TaskLifecycleNode::order));
        return filtered;
    }

    /**
     * 获取节点关联的 pluginId。
     * 内置节点没有 pluginId 关联（返回 null）；外部插件节点需要自行携带。
     * 当前 Phase 1 只有内置节点，此方法返回 null。
     */
    private String nodePluginId(TaskLifecycleNode node) {
        // Phase 1 只有内置节点，无 pluginId 关联
        // Phase 4+ 外部插件节点可通过额外接口或注册时关联
        return null;
    }
}
