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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TaskEntry usage 事件投影回归(§5.2/§7.20):advisor 发射 usage 事件后,任务级
 * TaskSummary.usage 供体与 task.updated 广播钩子由本投影器维护——
 * <ul>
 *   <li>聚合口径:任务下所有 agent「最近一轮上下文占用」之和(Σ input / Σ window),
 *       每 agent 取最近一轮(新轮覆盖旧轮),任务电池数据源;</li>
 *   <li>每条 usage 事件先更新聚合再触发广播(恰好一次);</li>
 *   <li>再运行冷启动 seedUsageMeta + seedUsageRounds 成对回灌,基线不被非 usage 事件冲掉,
 *       且续跑后第一轮新 usage 不会把聚合重算成「仅本次运行出现过的 agent」;</li>
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
        data.set("round", Json.toJson(new Usage(in, out, in + out, 0)));
        t.events.emit(EmitEvent.of(SnowflakeId.next(), Events.USAGE, agentId,
                null, null, null, null, data, EmitEvent.Mode.REPLACE));
    }

    /** fullViewEst > 0 时随 usage 事件携带(= 该轮发送视图为全量未压缩;§7.5 冷启动种子)。 */
    private static void emitUsage(TaskEntry t, String agentId, long in, long out, long window,
            String model, long fullViewEst) {
        var data = Json.obj();
        data.put("model", model);
        if (window > 0) {
            data.put("contextWindowTokens", window);
        }
        if (fullViewEst > 0) {
            data.put("fullViewEstTokens", fullViewEst);
        }
        data.set("round", Json.toJson(new Usage(in, out, in + out, 0)));
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
    void summaryCarriesPerAgentBaseForRerunRestore() {
        TaskEntry t = entry(null);
        emitUsage(t, "main-1", 100, 20, 256_000, "gpt-x");
        emitUsage(t, "sub-2", 500, 80, 128_000, "gpt-y");

        var byAgent = t.summaryJson().path("usageByAgent");
        assertTrue(byAgent.isObject(), "聚合口径的 per-agent 数据基随 meta 落盘: " + t.summaryJson());
        assertEquals(100, byAgent.path("main-1").path("inputTokens").asLong());
        assertEquals(256_000L, byAgent.path("main-1").path("contextWindowTokens").asLong());
        assertEquals(500, byAgent.path("sub-2").path("inputTokens").asLong());
        assertEquals(128_000L, byAgent.path("sub-2").path("contextWindowTokens").asLong());
    }

    /**
     * 续跑(再运行冷启动)必须保住上一轮其他 agent 的最近快照:只回灌聚合值时,续跑后第一条
     * usage 事件会把聚合重算成「仅本次运行出现过的 agent」,电池随之从「Σ 全部 agent」塌成
     * 「主 agent 自己」。
     */
    @Test
    void rerunKeepsOtherAgentsLastRoundInAggregate() {
        TaskEntry first = entry(null);
        emitUsage(first, "main-1", 100, 20, 256_000, "gpt-x");
        emitUsage(first, "sub-2", 500, 80, 128_000, "gpt-y");
        assertEquals(600, first.usageSummary().inputTokens());

        // 续跑:同 taskId 全新 TaskEntry(新 EventLog),meta 里的聚合值与 per-agent 数据基成对回灌
        TaskEntry second = entry(null);
        second.seedUsageMeta(Json.parse(Json.write(first.summaryJson())).path("usage"));
        second.seedUsageRounds(Json.parse(Json.write(first.summaryJson())).path("usageByAgent"));

        // 未跑任何一轮:聚合与上一轮结束一致(sub(resume) 前列表/电池值不缩水)
        assertEquals(600, second.usageSummary().inputTokens(), "续跑未跑轮前聚合不缩水");
        assertEquals(384_000L, second.usageSummary().contextWindowTokens());

        // 主 agent 新一轮:只覆盖主 agent 条目,子 agent 最近快照保留
        emitUsage(second, "main-1", 900, 50, 256_000, "gpt-x");
        var s = second.usageSummary();
        assertEquals(1400, s.inputTokens(), "续跑后聚合 = 主 900 + 子 500(不丢其他 agent)");
        assertEquals(130, s.outputTokens());
        assertEquals(384_000L, s.contextWindowTokens(), "窗口仍为 Σ 各 agent 窗口");
    }

    /** 无 per-agent 数据基(旧任务 meta)时,续跑仅沿用聚合基线(优雅降级,不清零)。 */
    @Test
    void rerunWithoutPerAgentBaseKeepsAggregateBaseline() {
        TaskEntry t = entry(null);
        t.seedUsageMeta(Json.parse("{\"inputTokens\":300,\"outputTokens\":40,\"totalTokens\":340,"
                + "\"contextWindowTokens\":200000,\"model\":\"gpt-old\"}"));
        t.seedUsageRounds(null);
        t.seedUsageRounds(Json.parse("{}"));
        // 形状异常(顶层键值不是对象)/零值条目一律不入数据基,更不触发空基重算
        t.seedUsageRounds(Json.parse("{\"inputTokens\":300,\"contextWindowTokens\":200000}"));

        var s = t.usageSummary();
        assertNotNull(s, "无数据基时聚合基线原样保留: " + s);
        assertEquals(300, s.inputTokens());
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

    // ---- 压缩冷启动校准种子(§7.5)投影/落盘/回灌 ----

    @Test
    void fullViewEstimateBecomesCompressionSeedAndIsSticky() {
        TaskEntry first = entry(null);
        // 全量视图轮次(携带 fullViewEstTokens):种子 = (实测, 估算, 模型)
        emitUsage(first, "main-1", 123_297, 1_000, 256_000, "gpt-x", 233_413);

        var seed = first.compressionSeed("main-1");
        assertNotNull(seed, "全量视图轮次的 usage 事件投影出压缩冷启动种子");
        assertEquals(123_297L, seed.measuredInputTokens());
        assertEquals(233_413L, seed.estimatedSentTokens());
        assertEquals("gpt-x", seed.model());

        // 后续压缩视图轮次(不携带 fullViewEstTokens)不得覆盖种子——粘性保持全量视图样本
        emitUsage(first, "main-1", 7_631, 500, 256_000, "gpt-x", 0);
        var sticky = first.compressionSeed("main-1");
        assertNotNull(sticky);
        assertEquals(233_413L, sticky.estimatedSentTokens(),
                "压缩视图轮次不得覆盖全量视图种子(比率不表征全量历史偏差)");

        // 其他 agent → null(无种子)
        assertNull(first.compressionSeed("sub-2"));
    }

    @Test
    void seedPersistsIntoUsageByAgentAndRestoresOnRerun() {
        TaskEntry first = entry(null);
        emitUsage(first, "main-1", 123_297, 1_000, 256_000, "gpt-x", 233_413);

        // 随 usageByAgent 落盘
        var item = first.summaryJson().path("usageByAgent").path("main-1");
        assertEquals(123_297L, item.path("seedInputTokens").asLong());
        assertEquals(233_413L, item.path("seedEstTokens").asLong());
        assertEquals("gpt-x", item.path("seedModel").asString());

        // 再运行冷启动:seedUsageRounds 一并回灌,经 ExecContext.compressionSeed 暴露
        TaskEntry second = entry(null);
        second.seedUsageRounds(Json.parse(Json.write(first.summaryJson())).path("usageByAgent"));
        var restored = second.compressionSeed("main-1");
        assertNotNull(restored, "再运行冷启动应回灌压缩校准种子");
        assertEquals(123_297L, restored.measuredInputTokens());
        assertEquals(233_413L, restored.estimatedSentTokens());
        assertEquals("gpt-x", restored.model());
    }

    @Test
    void legacyTaskWithoutSeedFieldsDegradesGracefully() {
        TaskEntry legacy = entry(null);
        // 旧任务 meta 无 seedInputTokens/seedEstTokens 字段 → compressionSeed 返回 null
        legacy.seedUsageRounds(Json.parse("{\"main-1\":{\"inputTokens\":100,\"outputTokens\":20,"
                + "\"contextWindowTokens\":256000}}"));
        assertNull(legacy.compressionSeed("main-1"),
                "无种子字段的旧任务优雅降级(压缩 advisor 回退固定 reserve)");
        assertNull(legacy.compressionSeed("sub-2"));
    }
}
