package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import tools.jackson.databind.JsonNode;

/**
 * 下行节点(order=50)：再运行状态恢复（一事）。
 * meta → TaskEntry 全量恢复：createdAt / metadata（含 aiReview、unattended 旧字段兼容迁移）/
 * networkBlocked / powershellEnabled / seedUsageMeta / slashTaskTokens 回读 + log.seed 水位续号。
 * 新建任务无 rerunMeta，整体空转。
 * 必须在 persistence.track(100) 之前执行（首落盘 meta 须含恢复后的 slashTaskTokens 等字段）。
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
            var t = impl.taskEntry();
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
            t.networkBlocked = meta.path("networkBlocked").asBoolean(false); // 禁网开关任务级(/禁用网络)
            t.powershellEnabled = meta.path("powershellEnabled").asBoolean(false); // 启用 powershell 开关任务级(/允许AI访问电脑)
            t.seedUsageMeta(meta.path("usage")); // 恢复最近一轮上下文用量(续跑后列表/电池数据不丢)
            // slash 任务级 token 回读(仅 slash 层存储、业务方不读;随 meta.json 落盘,冷启动续跑恢复)。
            // 在 store.track 之前完成 add,确保首落盘 meta 含 slashTaskTokens。
            JsonNode tt = meta.path("slashTaskTokens");
            if (tt.isArray()) {
                for (JsonNode e : tt) {
                    if (e.isTextual()) {
                        t.addSlashTaskToken(e.asText());
                    }
                }
            }
            // 新事件从 meta.seqLast 水位续号(含瞬态占位水位,磁盘不再写占位行)
            t.log.seed(impl.rerunSeqLast());
        }
        return next.proceed(ctx);
    }
}
