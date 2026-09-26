package dev.everyagent.plugin.unattended;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.task.TaskService;
import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import dev.everyagent.worker.slash.SlashCommandItem;
import dev.everyagent.worker.slash.SlashCommandRegistry;
import dev.everyagent.worker.slash.SlashDisplayPosition;
import dev.everyagent.worker.slash.SlashSelectionResult;
import dev.everyagent.worker.slash.SlashTokenEncoder;
import dev.everyagent.worker.task.TaskEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
 * UnattendedSlashProvider 单测:`/` 候选 id=unattended:on;
 * select 返回单个 bottom 结果;taskId 非空时置 metadata["unattended"]=true 并落盘,
 * 为空时不写业务标记仍返回胶囊;cancel 复位并落盘。
 */
class UnattendedSlashProviderTest {

    private SlashCommandRegistry registry;
    private WorkerServices services;
    private TaskService taskService;
    private TaskEntry task;

    @BeforeEach
    void setUp() {
        registry = new SlashCommandRegistry();
        services = mock(WorkerServices.class);
        taskService = mock(TaskService.class);
        task = newTask();
        when(services.task()).thenReturn(taskService);
        when(taskService.get("t-1")).thenReturn(task);
    }

    private static TaskEntry newTask() {
        ModelSnapshot snap = new ModelSnapshot("cfg", "openai-compat",
                "http://localhost:9999/v1", "m", null);
        return new TaskEntry("t-1", "任务", snap, "ws", "defaultworkspace", "main-agent", 10_000);
    }

    private SlashCommandItem item() {
        registry.registerProvider("unattended", () -> UnattendedSlashProvider.items(services));
        return registry.list().stream()
                .filter(i -> "unattended:on".equals(i.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未注册 unattended:on 条目"));
    }

    // ---- 候选 ----

    @Test
    void itemsExposeUnattendedCandidate() {
        SlashCommandItem it = item();
        assertEquals("unattended:on", it.id());
        assertEquals("无人值守", it.title());
        assertTrue(UnattendedToken.enabledIn(it.insertText()), "insertText 应为无人值守 opaque token");
    }

    // ---- select:返回单个 bottom 胶囊 ----

    @Test
    void selectReturnsSingleBottomCapsuleAndTurnsOnUnattended() {
        SlashCommandItem it = item();
        List<SlashSelectionResult> results = it.selectHandler().onSelect(it, "t-1");

        assertEquals(1, results.size(), "无人值守 onSelect 应返回单胶囊");
        SlashSelectionResult r = results.get(0);
        assertEquals("unattended:on", r.id(), "胶囊归属无人值守条目");
        assertEquals(SlashDisplayPosition.BOTTOM, r.position(), "无人值守胶囊 bottom 渲染");
        SlashTokenEncoder.ParsedToken tok = SlashTokenEncoder.parseToken(r.token());
        assertNotNull(tok, "token 可 parse");
        assertEquals(UnattendedToken.KIND, tok.kind(), "token kind=unattended.mode");

        verify(taskService).get("t-1");
        assertTrue(Boolean.TRUE.equals(task.metadata().getOrDefault("unattended", false)), "业务标记 unattended 应置位");
        verify(taskService).publishUpdated("t-1");
    }

    @Test
    void selectWithEmptyTaskIdStillReturnsCapsuleWithoutBusinessMark() {
        SlashCommandItem it = item();
        List<SlashSelectionResult> results = it.selectHandler().onSelect(it, "");

        assertEquals(1, results.size(), "空 taskId(草稿态)仍返回胶囊");
        assertEquals("unattended:on", results.get(0).id());
        verify(taskService, never()).get(any());
        assertFalse(Boolean.TRUE.equals(task.metadata().getOrDefault("unattended", false)), "空 taskId 不写业务标记");
        verify(taskService, never()).publishUpdated(any());
    }

    // ---- cancel:复位 unattended ----

    @Test
    void cancelTurnsUnattendedOff() {
        task.metadata().put("unattended", true);
        SlashCommandItem it = item();
        it.cancelHandler().onCancel(it, UnattendedToken.buildToken(), "t-1");
        assertFalse(Boolean.TRUE.equals(task.metadata().getOrDefault("unattended", false)), "取消无人值守胶囊应复位 unattended");
        verify(taskService).publishUpdated("t-1");
    }
}
