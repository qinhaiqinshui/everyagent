package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.plugin.api.event.EventPayloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 下行节点(order=310)：模型切换 trace（一事）。
 * 用户从输入框切到其它模型后续跑（overrideConfigId ≠ meta.configId）时，在主线显式标注
 * 本轮所用模型——否则用户看不到"本轮用了哪个模型"。仅再运行携带 override 时生效。
 * 在 persistence.track(100) 之后执行：事件落盘时序由节点位置显式保证
 * （原 startRerun 在 track 前发射，依赖 sink 追平回放的隐性假设）。
 */
public final class ModelSwitchTraceNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(ModelSwitchTraceNode.class);

    @Override
    public String id() { return "model.switch.trace"; }

    @Override
    public float order() { return 310; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        var impl = (TaskLifecycleContextImpl) ctx;
        var meta = impl.rerunMeta();
        var overrideConfigId = impl.overrideConfigId();
        if (meta != null && overrideConfigId != null && !overrideConfigId.isEmpty()) {
            String storedConfigId = meta.path("configId").asString(null);
            if (storedConfigId == null || !storedConfigId.equals(overrideConfigId)) {
                var t = impl.taskEntryImpl();
                try {
                    ObjectNode switchData = Json.obj();
                    switchData.put("newConfigId", t.snapshot.configId());
                    if (storedConfigId != null) {
                        switchData.put("oldConfigId", storedConfigId);
                    }
                    t.events.emit(EmitEvent.of(SnowflakeId.next(), "model.switch", null,
                            "模型切换", "已切换至 " + EventPayloads.modelLabel(t.snapshot),
                            null, "done", switchData, EmitEvent.Mode.REPLACE));
                } catch (RuntimeException e) {
                    log.debug("模型切换标注事件写入失败(日志可能已满)", e);
                }
            }
        }
        return next.proceed(ctx);
    }
}
