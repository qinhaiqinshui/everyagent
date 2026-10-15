package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import tools.jackson.databind.JsonNode;

/**
 * 下行节点(order=50)：再运行状态恢复（一事）。
 * meta → TaskEntry 全量恢复：createdAt / metadata（随 meta.json 落盘的持久数据，如 attachments）/
 * seedUsageMeta / log.seed 水位续号。
 * 新建任务无 rerunMeta，整体空转。
 * 必须在 persistence.track(100) 之前执行（首落盘 meta 须含恢复后的字段）。
 */
public final class RerunRestoreNode implements TaskLifecycleNode {

    @Override
    public String id() { return "rerun.restore"; }

    @Override
    public float order() { return 55; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        var impl = (TaskLifecycleContextImpl) ctx;
        JsonNode meta = impl.rerunMeta();
        if (meta != null) {
            var t = impl.taskEntryImpl();
            t.createdAt(meta.path("createdAt").asLong(0));
            // 任务级 metadata（随 meta.json 落盘的持久数据，如 attachments）
            var metaNode = meta.path("metadata");
            if (metaNode.isObject()) {
                metaNode.properties().forEach(e -> {
                    JsonNode v = e.getValue();
                    if (v.isBoolean()) {
                        t.metadata.put(e.getKey(), v.asBoolean());
                    } else if (v.isIntegralNumber()) {
                        t.metadata.put(e.getKey(), v.asLong());
                    } else if (v.isNumber()) {
                        t.metadata.put(e.getKey(), v.asDouble());
                    } else if (v.isTextual()) {
                        t.metadata.put(e.getKey(), v.asText());
                    } else {
                        t.metadata.put(e.getKey(), v);
                    }
                });
            }
            t.seedUsageMeta(meta.path("usage")); // 恢复最近一轮上下文用量(续跑后列表/电池数据不丢)
            // 聚合口径的 per-agent 数据基一并恢复:只回灌聚合值(上一行)时,本轮第一条 usage
            // 事件会把聚合重算成「仅本次运行出现过的 agent」,已终局子 agent 的快照被抹掉——
            // 电池从「Σ 任务下全部 agent」塌成「主 agent 自己」(2026-10 修复)。
            t.seedUsageRounds(meta.path("usageByAgent"));
            // 新事件从 meta.seqLast 水位续号(含瞬态占位水位,磁盘不再写占位行)
            t.log.seed(impl.rerunSeqLast());
        }
        return next.proceed(ctx);
    }
}
