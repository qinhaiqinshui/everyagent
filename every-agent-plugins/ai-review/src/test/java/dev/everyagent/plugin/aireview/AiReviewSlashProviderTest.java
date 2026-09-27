package dev.everyagent.plugin.aireview;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.task.TaskService;
import dev.everyagent.plugin.api.model.ModelConfig;
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
 * AiReviewSlashProvider 单测:`/` 候选 id=ai-review:on;
 * select 返回单个 bottom 结果;taskId 非空时置 metadata["ai-review"]=true 并落盘,
 * 为空时不写业务标记仍返回胶囊;cancel 复位并落盘。
 */
class AiReviewSlashProviderTest {

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
        ModelConfig snap = new ModelConfig("cfg", "openai-compat",
                "http://localhost:9999/v1", "m", null);
        return new TaskEntry("t-1", "任务", snap, "ws", "defaultworkspace", "main-agent", 10_000);
    }

    private SlashCommandItem item() {
        registry.registerProvider("ai-review", () -> AiReviewSlashProvider.items(services));
        return registry.list().stream()
                .filter(i -> "ai-review:on".equals(i.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未注册 ai-review:on 条目"));
    }

    // ---- 候选 ----

    @Test
    void itemsExposeAiReviewCandidate() {
        SlashCommandItem it = item();
        assertEquals("ai-review:on", it.id());
        assertEquals("AI 审议", it.title());
        assertTrue(it.defaultSelected(), "AI 审议应默认选中(新建任务时预置底部胶囊)");
        assertTrue(AiReviewToken.enabledIn(it.insertText()), "insertText 应为 AI 审议 opaque token");
    }

    // ---- select:单个 bottom 胶囊 ----

    @Test
    void selectReturnsSingleBottomCapsuleAndTurnsOnAiReview() {
        SlashCommandItem it = item();
        List<SlashSelectionResult> results = it.selectHandler().onSelect(it, "t-1");

        assertEquals(1, results.size(), "AI 审议 onSelect 应返回单胶囊");
        SlashSelectionResult r = results.get(0);
        assertEquals("ai-review:on", r.id(), "胶囊归属 AI 审议条目");
        assertEquals(SlashDisplayPosition.BOTTOM, r.position(), "AI 审议胶囊 bottom 渲染");
        SlashTokenEncoder.ParsedToken tok = SlashTokenEncoder.parseToken(r.token());
        assertNotNull(tok, "token 可 parse");
        assertEquals(AiReviewToken.KIND, tok.kind(), "token kind=ai.review");

        verify(taskService).get("t-1");
        assertTrue(Boolean.TRUE.equals(task.metadata().getOrDefault("ai-review", false)), "业务标记 ai-review 应置位");
        verify(taskService).publishUpdated("t-1");
    }

    // ---- select:空 taskId 不写业务标记 ----

    @Test
    void selectWithEmptyTaskIdStillReturnsCapsuleWithoutBusinessMark() {
        SlashCommandItem it = item();
        List<SlashSelectionResult> results = it.selectHandler().onSelect(it, "");

        assertEquals(1, results.size(), "空 taskId(草稿态)仍返回胶囊");
        assertEquals("ai-review:on", results.get(0).id());
        verify(taskService, never()).get(any());
        assertFalse(Boolean.TRUE.equals(task.metadata().getOrDefault("ai-review", false)), "空 taskId 不写业务标记");
        verify(taskService, never()).publishUpdated(any());
    }

    // ---- cancel:复位 aiReview ----

    @Test
    void cancelTurnsAiReviewOff() {
        task.metadata().put("ai-review", true);
        SlashCommandItem it = item();
        it.cancelHandler().onCancel(it, AiReviewToken.buildToken(), "t-1");
        assertFalse(Boolean.TRUE.equals(task.metadata().getOrDefault("ai-review", false)), "取消 AI 审议胶囊应复位 aiReview");
        verify(taskService).publishUpdated("t-1");
    }
}
