package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.event.Usage;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.worker.task.TaskEntry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 再运行冷启动恢复回归(§5.2/§7.20):RerunRestoreNode 必须把 meta 里的
 * {@code usageByAgent}(任务级聚合口径的 per-agent 数据基)与 {@code usage}(聚合值)
 * 成对回灌——只回灌聚合值时,续跑后第一条 usage 事件会把聚合重算成「仅本次运行出现过的
 * agent」,上一轮已终局的子 agent 快照被抹掉,电池从「Σ 任务下全部 agent」塌成
 * 「主 agent 自己」。
 */
class RerunRestoreNodeTest {

    private static TaskEntry entry() {
        return new TaskEntry("task-1", "t", new ModelConfig("c1", "openai", "http://x", "gpt-x", null),
                "C:\\ws", "ws1", "main-1", 1000);
    }

    private static void emitUsage(TaskEntry t, String agentId, long in, long out, long window, String model) {
        ObjectNode data = Json.obj();
        data.put("model", model);
        if (window > 0) {
            data.put("contextWindowTokens", window);
        }
        data.set("round", Json.toJson(new Usage(in, out, in + out)));
        t.events.emit(EmitEvent.of(SnowflakeId.next(), Events.USAGE, agentId,
                null, null, null, null, data, EmitEvent.Mode.REPLACE));
    }

    /** 走完 RerunRestoreNode 下行:注入 rerunMeta 后执行该节点(链尾直接返回)。 */
    private static void restore(TaskEntry t, ObjectNode meta) throws Exception {
        TaskLifecycleContextImpl ctx = new TaskLifecycleContextImpl(t, null, null, null);
        ctx.rerunMeta(meta);
        new RerunRestoreNode().invoke(ctx, c -> null);
    }

    @Test
    void rerunRestoreKeepsOtherAgentsUsageInAggregate() throws Exception {
        // 上一轮:主 agent 一轮 + 子 agent 一轮(meta.json 由 summaryJson 落盘)
        TaskEntry first = entry();
        emitUsage(first, "main-1", 100, 20, 256_000, "gpt-x");
        emitUsage(first, "sub-2", 500, 80, 128_000, "gpt-y");
        ObjectNode meta = (ObjectNode) Json.parse(Json.write(first.summaryJson()));
        assertNotNull(meta.path("usageByAgent").path("sub-2"), "meta 应携带 per-agent 数据基: " + meta);

        // 续跑:同 taskId 全新 TaskEntry(新 EventLog)
        TaskEntry second = entry();
        restore(second, meta);

        var restored = second.usageSummary();
        assertNotNull(restored, "续跑恢复后聚合非空");
        assertEquals(600, restored.inputTokens(), "续跑恢复 = Σ 主 + 子(不是只剩主 agent)");
        assertEquals(100, restored.outputTokens());
        assertEquals(384_000L, restored.contextWindowTokens());

        // 主 agent 新一轮:只覆盖主 agent 条目,子 agent 最近快照保留
        emitUsage(second, "main-1", 900, 50, 256_000, "gpt-x");
        var after = second.usageSummary();
        assertEquals(1400, after.inputTokens(), "续跑后聚合 = 主 900 + 子 500(不丢其他 agent)");
        assertEquals(384_000L, after.contextWindowTokens());
    }

    /** 旧任务 meta 无 usageByAgent:仅聚合基线可恢复,不抛错、不清零。 */
    @Test
    void rerunRestoreWithoutPerAgentBaseKeepsAggregate() throws Exception {
        TaskEntry t = entry();
        ObjectNode meta = Json.obj();
        meta.set("usage", Json.parse("{\"inputTokens\":300,\"outputTokens\":40,\"totalTokens\":340,"
                + "\"contextWindowTokens\":200000,\"model\":\"gpt-old\"}"));
        restore(t, meta);

        var s = t.usageSummary();
        assertNotNull(s, "旧任务无 per-agent 数据基时聚合基线原样保留");
        assertEquals(300, s.inputTokens());
        assertEquals(200_000L, s.contextWindowTokens());
    }
}