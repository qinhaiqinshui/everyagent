package dev.everyagent.plugin.filechange;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.task.RoundClosedInfo;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link FileChangeAdvisorProvider} 任务级共享 collector 生命周期测试:
 * 同任务同实例(主/子 agent 共享)、不同任务异实例、收口退役(下一 run 新实例,切断跨轮串扰)、
 * 轮闭合写盘(含收口后迟到子 agent 经同一引用补写的记录)与无暂存时的残留清理。
 *
 * <p>TaskService 仅 advisor 收口路径使用,本类不触达,构造传 null。
 */
class FileChangeAdvisorProviderTest {

    @Test
    void sameTaskSharesCollectorAcrossFetches() {
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(null);
        FileChangesCollector a = provider.activeCollector("t1");
        assertSame(a, provider.activeCollector("t1"), "同任务重复取用应返回同一共享实例");
        assertNotSame(a, provider.activeCollector("t2"), "不同任务各自独立实例");
    }

    @Test
    void markPendingRetiresActiveSoNextRunGetsFreshCollector() {
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(null);
        FileChangesCollector run1 = provider.activeCollector("t1");
        run1.onFileSaved("a_main", FileChangesCollector.Source.MAIN, "/old.md", "", "旧", "created");
        provider.markPending("t1", run1);
        assertNotSame(run1, provider.activeCollector("t1"),
                "收口退役后下一 run 应新建 collector(切断跨轮串扰)");
    }

    @Test
    void roundsClosedWritesShardIncludingLateSubAgentWrites() throws IOException {
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(null);
        FileChangesCollector collector = provider.activeCollector("t1");
        collector.onFileSaved("a_main", FileChangesCollector.Source.MAIN, "/a.md", "", "A", "created");
        collector.onFileSaved("a_sub", FileChangesCollector.Source.SUB_AGENT, "/sub.md", "", "S", "created");
        provider.markPending("t1", collector);
        // 主 agent 流收口后,仍在流式输出的迟到子 agent 持同一引用继续补写
        collector.onFileSaved("a_sub2", FileChangesCollector.Source.SUB_AGENT, "/late.md", "", "L", "created");

        Path dir = Files.createTempDirectory("file-change-provider-test");
        provider.onRoundsClosed("t1", dir, List.of(new RoundClosedInfo("r_9", 1, 9L, 2)));

        JsonNode changes = Json.parse(
                Files.readString(dir.resolve("file-changes").resolve("r_9.json"))).path("changes");
        assertEquals(3, changes.size(), "分片应含主 agent + 子 agent + 迟到子 agent 的记录");
        assertNotSame(collector, provider.activeCollector("t1"), "轮闭合后活跃实例应退役");
    }

    @Test
    void roundsClosedWithoutPendingClearsStaleActive() throws IOException {
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(null);
        FileChangesCollector stale = provider.activeCollector("t1"); // 模拟未收口(异常/取消)的 run
        stale.onFileSaved("a_main", FileChangesCollector.Source.MAIN, "/a.md", "", "A", "created");
        Path dir = Files.createTempDirectory("file-change-provider-test");
        provider.onRoundsClosed("t1", dir, List.of(new RoundClosedInfo("r_1", 1, 3L, 1)));
        assertFalse(Files.exists(dir.resolve("file-changes").resolve("r_1.json")),
                "未收口不写分片(与旧行为一致)");
        assertNotSame(stale, provider.activeCollector("t1"),
                "轮闭合应清理残留活跃实例,防止跨轮串数据");
    }
}
