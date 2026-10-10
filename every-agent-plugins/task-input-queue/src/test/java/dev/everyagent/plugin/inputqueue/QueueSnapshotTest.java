package dev.everyagent.plugin.inputqueue;

import dev.everyagent.plugin.api.event.StreamEmitter;
import dev.everyagent.plugin.api.rpc.RpcContext;
import dev.everyagent.plugin.api.task.StoredTaskInfo;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.task.TaskService;
import dev.everyagent.plugin.api.task.TaskStoreService;
import dev.everyagent.plugin.api.task.UserInput;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task.queueSnapshot 快照单测：面板「编辑」回填要靠快照里的原始输入串
 * （{@code pendingInputsRaw}，与 {@code pendingInputs} 等长按位对齐）才能还原 @文件胶囊，
 * 只有纯文本时回填会退化成明文。热任务走内存队列，终态任务走磁盘悬空队列，两条路径都要带。
 * 桩一律 mock plugin-api 接口，不借 worker 具体类。
 */
class QueueSnapshotTest {

    private static final String TASK_ID = "t_snap";
    private static final String TOKEN = "看这个文件[[[[agent-token::::system.workspace_file|||]]]]";

    @Test
    void hotQueueSnapshotCarriesRawAlignedWithText() {
        TaskQueueRegistry registry = new TaskQueueRegistry();
        InputQueue queue = registry.getOrCreateInputQueue(TASK_ID);
        queue.offer(stub("纯文本项", null));
        queue.offer(stub("带胶囊项", TOKEN));

        RpcContext rpc = mock(RpcContext.class);
        JsonNode ok = snapshot(registry, rpc, mock(TaskService.class), mock(TaskStoreService.class));

        assertEquals(List.of("纯文本项", "带胶囊项"), textsOf(ok), "pendingInputs 顺序 = 队首在前");
        List<String> raws = rawsOf(ok);
        assertEquals(2, raws.size(), "raw 与 text 等长按位对齐");
        assertEquals("", raws.get(0), "无原始内容时空串占位(不用 null,前端按空串退化)");
        assertEquals(TOKEN, raws.get(1), "原始输入串随快照带出");
        assertEquals(TASK_ID, ok.path("taskId").asString(""));
    }

    @Test
    void coldQueueSnapshotReadsRawFromDisk() {
        TaskQueueRegistry registry = new TaskQueueRegistry(); // 无内存队列 = 终态/冷任务
        StoredTaskInfo stored = mock(StoredTaskInfo.class);
        when(stored.dir()).thenReturn(Path.of("data/tasks", TASK_ID));
        TaskService taskService = mock(TaskService.class);
        when(taskService.diskEntry(TASK_ID)).thenReturn(stored);
        TaskStoreService store = mock(TaskStoreService.class);
        when(store.readQueue(any())).thenReturn(List.of(UserInput.of("磁盘项", TOKEN), UserInput.of("无原始项")));

        JsonNode ok = snapshot(registry, mock(RpcContext.class), taskService, store);

        assertEquals(List.of("磁盘项", "无原始项"), textsOf(ok));
        assertEquals(List.of(TOKEN, ""), rawsOf(ok), "悬空队列的 rawContent 一并回读");
    }

    @Test
    void runningTaskWithoutRegisteredQueueSnapshotsEmpty() {
        TaskQueueRegistry registry = new TaskQueueRegistry();
        TaskRuntime running = mock(TaskRuntime.class);
        when(running.terminal()).thenReturn(false);
        TaskService taskService = mock(TaskService.class);
        when(taskService.get(TASK_ID)).thenReturn(running);

        RpcContext rpc = mock(RpcContext.class);
        JsonNode ok = snapshot(registry, rpc, taskService, mock(TaskStoreService.class));

        verify(rpc).ok(any(JsonNode.class));
        assertTrue(textsOf(ok).isEmpty(), "queue.loop 注册前的极窄窗口视为空队列");
        assertTrue(rawsOf(ok).isEmpty(), "空队列两数组同形,前端按下标取不越界");
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private static JsonNode snapshot(TaskQueueRegistry registry, RpcContext rpc,
            TaskService taskService, TaskStoreService store) {
        when(rpc.strParam("taskId")).thenReturn(TASK_ID);
        new QueueRpcHandler(registry, taskService, store, mock(StreamEmitter.class))
                .rpcQueueSnapshot(rpc);
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(rpc).ok(captor.capture());
        return captor.getValue();
    }

    private static List<String> textsOf(JsonNode ok) {
        return listOf(ok.path("pendingInputs"));
    }

    private static List<String> rawsOf(JsonNode ok) {
        return listOf(ok.path("pendingInputsRaw"));
    }

    private static List<String> listOf(JsonNode arr) {
        if (!arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : arr) {
            out.add(item.asString(""));
        }
        return out;
    }

    private static TaskLifecycleContext stub(String input, String rawContent) {
        return new TaskLifecycleContext() {
            @Override public String taskId() { return TASK_ID; }
            @Override public String title() { return ""; }
            @Override public String workspaceRoot() { return ""; }
            @Override public String workspaceId() { return "defaultworkspace"; }
            @Override public String mainAgentId() { return "a_main"; }
            @Override public String status() { return "running"; }
            @Override public TaskRuntime taskInfo() { return null; }
            @Override public Object taskLock() { return new Object(); }
            @Override public long startedAt() { return 0; }
            @Override public void startedAt(long ms) { }
            @Override public void onUsageBroadcast(Runnable hook) { }
            @Override public String input() { return input; }
            @Override public String rawContent() { return rawContent; }
            @Override public Map<String, Object> runParams() { return Map.of(); }
            @Override public Object rpcContext() { return null; }
            @Override public void input(String i) { }
            @Override public void rawContent(String r) { }
            @Override public void runParams(Map<String, Object> r) { }
            @Override public void metadata(Map<String, Object> m) { }
        };
    }

}
