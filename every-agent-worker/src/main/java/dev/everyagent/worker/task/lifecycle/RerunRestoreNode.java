package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import tools.jackson.databind.JsonNode;

/**
 * 下行节点(order=50)：再运行状态恢复（一事）。
 * meta → TaskEntry 全量恢复：createdAt / metadata（含 aiReview、unattended 旧字段兼容迁移,
 * 插件自管的禁网等业务标记随 metadata 一并回读）/ seedUsageMeta / log.seed 水位续号。
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
            // 兼容旧格式:aiReview/unattended 布尔字段自动迁移到 metadata
            if (meta.path("aiReview").asBoolean(false)) t.metadata.put("ai-review", true);
            if (meta.path("unattended").asBoolean(false)) t.metadata.put("unattended", true);
            // 旧格式 taskFlags（Map<String, Boolean>）→ 迁移到 metadata
            var flagsNode = meta.path("taskFlags");
            if (flagsNode.isObject()) {
                flagsNode.properties().forEach(e -> {
                    if (e.getValue().isBoolean()) {
                        t.metadata.put(e.getKey(), e.getValue().asBoolean());
                    }
                });
            }
            // 新格式 metadata（Map<String, Object>）
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
            // 新事件从 meta.seqLast 水位续号(含瞬态占位水位,磁盘不再写占位行)
            t.log.seed(impl.rerunSeqLast());
        }
        return next.proceed(ctx);
    }
}
