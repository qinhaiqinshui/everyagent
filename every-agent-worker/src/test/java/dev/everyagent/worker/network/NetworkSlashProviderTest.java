package dev.everyagent.worker.network;

import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import dev.everyagent.worker.slash.SlashCommandItem;
import dev.everyagent.worker.slash.SlashCommandRegistry;
import dev.everyagent.worker.slash.SlashDisplayPosition;
import dev.everyagent.worker.slash.SlashSelectionResult;
import dev.everyagent.worker.slash.SlashTokenEncoder;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
 * NetworkSlashProvider 单测:`/` 候选 id=network:on;select 返回 1 个 bottom 结果
 * (token 可 parse 为 kind=network.access);taskId 非空时置 {@code TaskEntry.networkBlocked=true}
 * 并落盘,为空时不写业务标记仍返回胶囊;cancel 复位 networkBlocked 并落盘。
 */
class NetworkSlashProviderTest {

    private SlashCommandRegistry registry;
    private TaskManager taskManager;
    private TaskEntry task;
    private AtomicInteger persisted;

    @BeforeEach
    void setUp() {
        registry = new SlashCommandRegistry();
        taskManager = mock(TaskManager.class);
        task = newTask();
        persisted = new AtomicInteger();
        task.persistHook = persisted::incrementAndGet;
        when(taskManager.runningTask("t-1")).thenReturn(task);
    }

    private static TaskEntry newTask() {
        ModelSnapshot snap = new ModelSnapshot("cfg", "openai-compat",
                "http://localhost:9999/v1", "m", null);
        return new TaskEntry("t-1", "任务", snap, "k", "ws", "main-agent", 10_000);
    }

    private SlashCommandItem item() {
        new NetworkSlashProvider(registry, taskManager);
        return registry.list().stream()
                .filter(i -> "network:on".equals(i.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未注册 network:on 条目"));
    }

    @Test
    void itemsExposeNetworkCandidate() {
        SlashCommandItem it = item();
        assertEquals("network:on", it.id());
        assertEquals("禁用网络", it.title());
        assertTrue(NetworkToken.enabledIn(it.insertText()), "insertText 应为网络 opaque token");
    }

    @Test
    void selectTurnsOnNetworkAndPersists() {
        SlashCommandItem it = item();
        List<SlashSelectionResult> results = it.selectHandler().onSelect(it, "t-1");

        assertEquals(1, results.size(), "网络 onSelect 应返回单个 bottom 胶囊");
        SlashSelectionResult r = results.get(0);
        assertEquals(SlashDisplayPosition.BOTTOM, r.position(), "网络胶囊 bottom 渲染");
        SlashTokenEncoder.ParsedToken tok = SlashTokenEncoder.parseToken(r.token());
        assertNotNull(tok, "网络 token 可 parse");
        assertEquals(NetworkToken.KIND, tok.kind(), "token kind=network.access");

        verify(taskManager).runningTask("t-1");
        assertTrue(task.networkBlocked, "业务标记 networkBlocked 应置位(禁用本任务网络)");
        assertTrue(persisted.get() >= 1, "置位后应落盘 meta");
    }

    @Test
    void selectWithEmptyTaskIdStillReturnsCapsuleWithoutBusinessMark() {
        SlashCommandItem it = item();
        List<SlashSelectionResult> results = it.selectHandler().onSelect(it, "");

        assertEquals(1, results.size(), "空 taskId(草稿态)仍返回胶囊");
        assertEquals(SlashDisplayPosition.BOTTOM, results.get(0).position());
        verify(taskManager, never()).runningTask(any());
        assertFalse(task.networkBlocked, "空 taskId 不写业务标记");
        assertEquals(0, persisted.get(), "空 taskId 不落盘");
    }

    @Test
    void cancelTurnsNetworkOffAndPersists() {
        task.networkBlocked = true;
        SlashCommandItem it = item();
        it.cancelHandler().onCancel(it, NetworkToken.buildToken(), "t-1");
        assertFalse(task.networkBlocked, "取消禁用网络胶囊应复位 networkBlocked");
        assertTrue(persisted.get() >= 1, "取消后应落盘 meta");
    }
}
