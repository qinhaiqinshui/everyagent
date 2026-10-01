package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.slash.SlashCommandItem;
import dev.everyagent.plugin.api.slash.SlashDisplayPosition;
import dev.everyagent.plugin.api.slash.SlashSelectionResult;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.task.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NetworkSlashProvider 单测:`/` 候选 id=network:on;select 返回单个 bottom 结果
 * (token 可 parse 为 kind=network.access);taskId 非空时置
 * {@code ExecContext.metadata["networkBlocked"]=true} 并广播 task.updated;taskId 为空
 * (草稿态)不写业务标记仍返回胶囊;cancel 复位;非 wsl-ubuntu 后端不提供该命令
 * (真断网只有本后端做得到)。
 *
 * <p>约束(§14.9):插件对 worker 任何 scope 零依赖——provider 是静态产物,任务桩在本类
 * 内自建(实现 plugin-api TaskRuntime),不引用 worker TaskEntry。
 */
class NetworkSlashProviderTest {

    private WorkerServices services;
    private TaskService taskService;
    private StubTaskRuntime task;

    @BeforeEach
    void setUp() {
        services = mock(WorkerServices.class);
        taskService = mock(TaskService.class);
        SandboxBackend backend = mock(SandboxBackend.class);
        task = new StubTaskRuntime();
        when(services.task()).thenReturn(taskService);
        when(services.sandbox()).thenReturn(backend);
        when(backend.id()).thenReturn("wsl-ubuntu");
        when(taskService.get("t-1")).thenReturn(task);
    }

    private SlashCommandItem item() {
        return NetworkSlashProvider.items(services).stream()
                .filter(i -> NetworkSlashProvider.ITEM_ID.equals(i.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未注册 network:on 条目"));
    }

    // ---- 候选 ----

    @Test
    void itemsExposeNetworkCandidate() {
        SlashCommandItem it = item();
        assertEquals("network:on", it.id());
        assertEquals("禁用网络", it.title());
        assertTrue(NetworkToken.enabledIn(it.insertText()), "insertText 应为网络 opaque token");
    }

    @Test
    void itemsHiddenWhenBackendCannotBlockNetwork() {
        when(services.sandbox().id()).thenReturn("windows-mic");
        assertEquals(List.of(), NetworkSlashProvider.items(services),
                "非 wsl-ubuntu 后端做不到硬断网,不提供 /禁用网络");
    }

    // ---- select:置位任务级禁网开关 ----

    @Test
    void selectTurnsOnNetworkBlockAndBroadcasts() {
        SlashCommandItem it = item();
        List<SlashSelectionResult> results = it.selectHandler().onSelect(it, "t-1");

        assertEquals(1, results.size(), "网络 onSelect 应返回单个 bottom 胶囊");
        SlashSelectionResult r = results.get(0);
        assertEquals(NetworkSlashProvider.ITEM_ID, r.id(), "胶囊归属 network:on 条目");
        assertEquals(SlashDisplayPosition.BOTTOM, r.position(), "网络胶囊 bottom 渲染");
        SlashTokenEncoder.ParsedToken tok = SlashTokenEncoder.parseToken(r.token());
        assertNotNull(tok, "网络 token 可 parse");
        assertEquals(NetworkToken.KIND, tok.kind(), "token kind=network.access");

        verify(taskService).get("t-1");
        assertTrue(NetworkTaskFlag.isOn(task), "业务标记 metadata[networkBlocked] 应置位");
        verify(taskService).publishUpdated("t-1");
    }

    @Test
    void selectWithEmptyTaskIdStillReturnsCapsuleWithoutBusinessMark() {
        SlashCommandItem it = item();
        List<SlashSelectionResult> results = it.selectHandler().onSelect(it, "");

        assertEquals(1, results.size(), "空 taskId(草稿态)仍返回胶囊");
        assertEquals(SlashDisplayPosition.BOTTOM, results.get(0).position());
        verify(taskService, never()).get(any());
        assertFalse(NetworkTaskFlag.isOn(task), "空 taskId 不写业务标记");
        verify(taskService, never()).publishUpdated(any());
    }

    // ---- cancel:复位 ----

    @Test
    void cancelTurnsNetworkBlockOff() {
        task.metadata().put(NetworkTaskFlag.META_KEY, true);
        SlashCommandItem it = item();
        it.cancelHandler().onCancel(it, NetworkToken.buildToken(), "t-1");
        assertFalse(NetworkTaskFlag.isOn(task), "取消禁用网络胶囊应复位 networkBlocked");
        verify(taskService).publishUpdated("t-1");
    }

    /** 执行器侧口径:任务不可寻(已驱逐)按未开启处理,绝不 NPE。 */
    @Test
    void isOnToleratesUnknownTask() {
        assertFalse(NetworkTaskFlag.isOn(null), "null 任务 = 未开启");
        assertFalse(NetworkTaskFlag.isOn(new StubTaskRuntime()), "默认 metadata 无该 key = 未开启");
    }

    // ---- 自建等价桩(实现 plugin-api 接口;§14.9) ----

    /** TaskRuntime 最小桩(provider 只读写 metadata())。 */
    private static final class StubTaskRuntime implements TaskRuntime {
        private final Map<String, Object> metadata = new HashMap<>();

        @Override public String taskId() { return "t-1"; }
        @Override public String status() { return "running"; }
        @Override public boolean terminal() { return false; }
        @Override public Map<String, Object> metadata() { return metadata; }
        @Override public Path taskDir() { return Path.of("workspaces", "defaultworkspace", "tasks", "t-1"); }
        @Override public String workspaceRoot() { return "ws"; }
        @Override public String workspaceId() { return "defaultworkspace"; }
        @Override public String mainAgentId() { return "main-agent"; }
        @Override public ModelConfig snapshot() {
            return new ModelConfig("cfg", "openai-compat", "http://localhost:9999/v1", "m", null);
        }
        @Override public dev.everyagent.plugin.api.model.EventEmitter events() { return e -> e.id(); }
        @Override public Map<String, AgentContext> agents() { return new HashMap<>(); }
        @Override public dev.everyagent.plugin.api.agent.AgentFactory agentFactory() { return null; }
        @Override public dev.everyagent.plugin.api.interaction.InteractionService interaction() { return null; }
        @Override public AgentContext main() { return null; }
        @Override public dev.everyagent.plugin.api.event.EventLogReader log() {
            return new dev.everyagent.plugin.api.event.EventLogReader() {
                @Override public List<dev.everyagent.plugin.api.event.EventRecord> readFrom(int from, int max) { return List.of(); }
                @Override public List<dev.everyagent.plugin.api.event.EventRecord> readAfterSeq(long afterSeq, int max) { return List.of(); }
                @Override public void addListener(Listener listener) { }
                @Override public void removeListener(Listener listener) { }
            };
        }
        @Override public dev.everyagent.plugin.api.task.FileChangesCollector fileChanges() { return null; }
        @Override public void fileChanges(dev.everyagent.plugin.api.task.FileChangesCollector collector) { }
        @Override public tools.jackson.databind.JsonNode fileChangesLight() { return null; }
        @Override public void fileChangesLight(tools.jackson.databind.JsonNode light) { }
        @Override public tools.jackson.databind.JsonNode fileChangesFull() { return null; }
        @Override public void fileChangesFull(tools.jackson.databind.JsonNode full) { }
        @Override public long startedAt() { return 0; }
        @Override public long endedAt() { return 0; }
        @Override public void touch() { }
        @Override public tools.jackson.databind.node.ObjectNode summaryJson() { return null; }
        @Override public void truncateLogAfter(long targetSeq) { }
    }
}
