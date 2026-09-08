package dev.everyagent.worker.authreview;

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
 * AiReviewSlashProvider 单测(plan-unattended-ai-auth 步骤 8):`/` 候选 id=ai-review:on;
 * select 返回 <b>1 个</b> bottom 结果,id=ai-review:on 且 token 可 parse 为 kind=ai.review;
 * taskId 非空时置 {@code TaskEntry.aiReview=true} 并落盘,为空时不写业务标记仍返回胶囊;
 * cancel 复位 aiReview 并落盘。
 */
class AiReviewSlashProviderTest {

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
        new AiReviewSlashProvider(registry, taskManager);
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

        verify(taskManager).runningTask("t-1");
        assertTrue(task.aiReview, "业务标记 aiReview 应置位");
        assertTrue(persisted.get() >= 1, "置位后应落盘 meta");
    }

    // ---- select:空 taskId 不写业务标记 ----

    @Test
    void selectWithEmptyTaskIdStillReturnsCapsuleWithoutBusinessMark() {
        SlashCommandItem it = item();
        List<SlashSelectionResult> results = it.selectHandler().onSelect(it, "");

        assertEquals(1, results.size(), "空 taskId(草稿态)仍返回胶囊");
        assertEquals("ai-review:on", results.get(0).id());
        verify(taskManager, never()).runningTask(any());
        assertFalse(task.aiReview, "空 taskId 不写业务标记");
        assertEquals(0, persisted.get(), "空 taskId 不落盘");
    }

    // ---- cancel:复位 aiReview ----

    @Test
    void cancelTurnsAiReviewOff() {
        task.aiReview = true;
        SlashCommandItem it = item();
        it.cancelHandler().onCancel(it, AiReviewToken.buildToken(), "t-1");
        assertFalse(task.aiReview, "取消 AI 审议胶囊应复位 aiReview");
        assertTrue(persisted.get() >= 1, "取消后应落盘 meta");
    }
}