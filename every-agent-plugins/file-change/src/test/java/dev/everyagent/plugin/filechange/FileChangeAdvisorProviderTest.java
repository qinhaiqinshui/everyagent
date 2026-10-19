package dev.everyagent.plugin.filechange;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.task.RoundClosedInfo;
import dev.everyagent.plugin.api.task.StoredTaskInfo;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.task.TaskService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileChangeAdvisorProvider} 任务级共享 collector 生命周期测试:
 * 同任务同实例(主/子 agent 共享)、不同任务异实例、收口退役(下一 run 新实例,切断跨轮串扰)、
 * 轮闭合写盘(含收口后迟到子 agent 经同一引用补写的记录)、写盘后 plugin.file-change.roundChanges
 * 瞬态直推(任务驱逐时跳过不抛)与无暂存时的残留清理。
 *
 * <p>TaskService/TaskRuntime 桩按插件测试约定在测试类内自建(实现 plugin-api 接口,
 * 不借 worker 具体类);不触达 TaskService 的用例构造传 null。
 */
class FileChangeAdvisorProviderTest {

    /** TaskService 桩:get 返回注入的 TaskRuntime(null = 任务已驱逐)。 */
    private static final class StubTaskService implements TaskService {
        private final TaskRuntime runtime;

        StubTaskService(TaskRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public TaskRuntime get(String taskId) {
            return runtime;
        }

        @Override
        public StoredTaskInfo diskEntry(String taskId) {
            return null;
        }

        @Override
        public void publishUpdated(String taskId) {
        }
    }

    /** 只接 events()/emitter() 与 taskId() 的 TaskRuntime 动态代理桩(其余方法调用即失败)。 */
    private static TaskRuntime runtimeWithEmitter(EventEmitter emitter, String taskId) {
        return (TaskRuntime) Proxy.newProxyInstance(
                FileChangeAdvisorProviderTest.class.getClassLoader(),
                new Class<?>[] { TaskRuntime.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "events", "emitter" -> emitter;
                    case "taskId", "subjectId" -> taskId;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

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
        // TaskService 桩返回驱逐态(null runtime):验证写盘不受推送路径影响
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(new StubTaskService(null));
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

    @Test
    void roundsClosedEmitsRoundChangesPushAfterWrite() throws IOException {
        List<EmitEvent> emitted = new ArrayList<>();
        TaskRuntime runtime = runtimeWithEmitter(ev -> {
            emitted.add(ev);
            return ev.id();
        }, "t1");
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(new StubTaskService(runtime));
        FileChangesCollector collector = provider.activeCollector("t1");
        collector.onFileSaved("a_main", FileChangesCollector.Source.MAIN, "/a.md", "", "A", "created");
        provider.markPending("t1", collector);

        Path dir = Files.createTempDirectory("file-change-provider-test");
        provider.onRoundsClosed("t1", dir, List.of(new RoundClosedInfo("r_9", 1, 9L, 2)));

        assertTrue(Files.exists(dir.resolve("file-changes").resolve("r_9.json")), "先落盘后推送(同事务顺序)");
        assertEquals(1, emitted.size(), "写盘成功后应直推一条 plugin.file-change.roundChanges 事件");
        EmitEvent ev = emitted.get(0);
        assertEquals("plugin.file-change.roundChanges", ev.kind());
        assertFalse(ev.persist(), "直推事件必须瞬态(ext.persist=false,不落盘)");
        assertEquals(EmitEvent.Mode.REPLACE, ev.mode());
        ObjectNode data = (ObjectNode) ev.data();
        assertEquals("r_9", data.path("roundId").asText(), "data 携带 roundId");
        JsonNode changes = data.path("changes");
        assertTrue(changes.isArray() && changes.size() == 1, "data 携带轻量摘要数组");
        assertEquals("/a.md", changes.get(0).path("filePath").asText());
        assertEquals("created", changes.get(0).path("changeType").asText());
    }

    @Test
    void roundsClosedSkipsPushWhenTaskEvicted() throws IOException {
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(new StubTaskService(null));
        FileChangesCollector collector = provider.activeCollector("t1");
        collector.onFileSaved("a_main", FileChangesCollector.Source.MAIN, "/a.md", "", "A", "created");
        provider.markPending("t1", collector);
        Path dir = Files.createTempDirectory("file-change-provider-test");
        assertDoesNotThrow(
                () -> provider.onRoundsClosed("t1", dir, List.of(new RoundClosedInfo("r_1", 1, 3L, 1))),
                "任务已驱逐(taskService.get 返回 null)时只跳过推送,不抛异常");
        assertTrue(Files.exists(dir.resolve("file-changes").resolve("r_1.json")),
                "落盘不受推送跳过影响(前端经 task.fileChanges RPC 兜底拉取)");
    }
}
