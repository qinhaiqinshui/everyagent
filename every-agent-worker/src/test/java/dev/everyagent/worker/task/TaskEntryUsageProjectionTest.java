package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.event.Usage;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * TaskEntry usage 事件投影回归(§5.2/§7.20):advisor 发射 usage 事件后,任务级
 * TaskSummary.usage 供体与 task.updated 广播钩子由本投影器维护——
 * <ul>
 *   <li>聚合口径:任务下所有 agent「最近一轮上下文占用」之和(Σ input / Σ window),
 *       每 agent 取最近一轮(新轮覆盖旧轮),任务电池数据源;</li>
 *   <li>每条 usage 事件先更新聚合再触发广播(恰好一次);</li>
 *   <li>再运行冷启动 seedUsageMeta 基线不被非 usage 事件冲掉;</li>
 *   <li>编辑重发截断后新 usage 事件(新雪花 seq)天然续读。</li>
 * </ul>
 */
class TaskEntryUsageProjectionTest {

    private static TaskEntry entry(AtomicInteger broadcasts) {
        TaskEntry t = new TaskEntry("task-1", "t", new ModelConfig("c1", "openai", "http://x", "gpt-x", null),
                "C:\\ws", "ws1", "main-1", 1000);
        if (broadcasts != null) {
            t.onUsageBroadcast = broadcasts::incrementAndGet;
        }
        return t;
    }

    private static void emitUsage(TaskEntry t, String agentId, long in, long out, long window, String model) {
        var data = Json.obj();
        data.put("model", model);
        if (window > 0) {
            data.put("contextWindowTokens", window);
        }
        data.set("round", Json.toJson(new Usage(in, out, in + out)));
        t.events.emit(EmitEvent.of(SnowflakeId.next(), Events.USAGE, agentId,
                null, null, null, null, data, EmitEvent.Mode.REPLACE));
    }

    @Test
    void usageEventUpdatesSnapshotAndFiresBroadcastExactlyOnce() {
        AtomicInteger broadcasts = new AtomicInteger();
        TaskEntry t = entry(broadcasts);

        emitUsage(t, "main-1", 100, 20, 256_000, "gpt-x");

        var s = t.usageSummary();
        assertNotNull(s, "usage 事件后 summary 非空");
        assertEquals(100, s.inputTokens());
        assertEquals(20, s.outputTokens());
        assertEquals(120, s.totalTokens());
        assertEquals(256_000L, s.contextWindowTokens());
        assertEquals("gpt-x", s.model());
        assertEquals(1, broadcasts.get(), "先更新后广播,恰好一次");
    }

    @Test
    void aggregateSumsAllAgentsLatestRound() {
        TaskEntry t = entry(null);

        emitUsage(t, "main-1", 100, 20, 256_000, "gpt-x");
        emitUsage(t, "sub-2", 500, 80, 128_000, "gpt-y");

        var s = t.usageSummary();
        assertEquals(600, s.inputTokens(), "聚合 = Σ 各 agent 最近一轮 inputTokens");
        assertEquals(100, s.outputTokens());
        assertEquals(700, s.totalTokens());
        assertEquals(384_000L, s.contextWindowTokens(), "窗口 = Σ 各 agent 窗口");
        assertEquals("gpt-y", s.model(), "model 取最近写者");
    }

    @Test
    void newRoundOverwritesOnlyThatAgent() {
        TaskEntry t = entry(null);

        emitUsage(t, "main-1", 100, 20, 256_000, "gpt-x");
        emitUsage(t, "sub-2", 500, 80, 128_000, "gpt-y");
        // 主 agent 新一轮:只覆盖主 agent 条目,子 agent 快照保留
        emitUsage(t, "main-1", 900, 50, 256_000, "gpt-x");

        var s = t.usageSummary();
        assertEquals(1400, s.inputTokens(), "主 900 + 子 500(子保留最近一轮)");
        assertEquals(130, s.outputTokens());
        assertEquals(384_000L, s.contextWindowTokens());
    }

    @Test
    void seedUsageMetaBaselineSurvivesNonUsageEvents() {
        TaskEntry t = entry(null);
        t.seedUsageMeta(Json.parse("{\"inputTokens\":300,\"outputTokens\":40,\"totalTokens\":340,"
                + "\"contextWindowTokens\":200000,\"model\":\"gpt-old\"}"));

        // 再运行首条事件是 user.message(非 usage):基线不动
        t.events.emit(EmitEvent.of(SnowflakeId.next(), "user.message", null,
                null, null, "hi", null, null, EmitEvent.Mode.REPLACE));

        var s = t.usageSummary();
        assertNotNull(s);
        assertEquals(300, s.inputTokens(), "冷启动基线由 seedUsageMeta 提供,非 usage 事件不覆盖");
        assertEquals(200_000L, s.contextWindowTokens());
    }

    @Test
    void truncationDoesNotStallProjection() {
        TaskEntry t = entry(null);
        emitUsage(t, "main-1", 100, 20, 256_000, "gpt-x");
        long seq = t.log.lastSeq();

        // 编辑重发:截断末尾(含刚发的 usage),再追加新轮 → 投影续读新事件
        t.truncateLogAfter(seq);
        t.events.emit(EmitEvent.of(SnowflakeId.next(), "user.message", null,
                null, null, "again", null, null, EmitEvent.Mode.REPLACE));
        emitUsage(t, "main-1", 700, 90, 256_000, "gpt-x");

        var s = t.usageSummary();
        assertNotNull(s);
        assertEquals(700, s.inputTokens(), "截断后新 usage 事件(新雪花 seq)仍被投影");
    }

    @Test
    void noUsageNoSummaryNoBroadcast() {
        AtomicInteger broadcasts = new AtomicInteger();
        TaskEntry t = entry(broadcasts);
        t.events.emit(EmitEvent.of(SnowflakeId.next(), "message", "main-1",
                null, null, "hello", null, null, EmitEvent.Mode.REPLACE));
        assertNull(t.usageSummary(), "无 usage 事件时 summary 省略");
        assertEquals(0, broadcasts.get(), "非 usage 事件不触发广播");
    }
}
