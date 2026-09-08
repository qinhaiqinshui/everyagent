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
 * UnattendedSlashProvider 单测(plan-unattended-ai-auth 步骤 8):`/` 候选 id=unattended:on;
 * select 返回 <b>2 个</b> bottom 结果——第一个无人值守(token 可 parse 为 kind=unattended.mode),
 * 第二个联动 AI 审议(token 可 parse 为 kind=ai.review),id 分别指向各自注册条目;
 * taskId 非空时置 {@code TaskEntry.unattended=true} 并落盘,为空时不写业务标记仍返回胶囊;
 * cancel 按 token kind 各自复位(取消 AI 审议胶囊不影响无人值守)。
 */
class UnattendedSlashProviderTest {

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
        new UnattendedSlashProvider(registry, taskManager);
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

    // ---- select:一次返回两个 bottom 胶囊 ----

    @Test
    void selectReturnsTwoBottomCapsulesAndTurnsOnUnattended() {
        SlashCommandItem it = item();
        List<SlashSelectionResult> results = it.selectHandler().onSelect(it, "t-1");

        assertEquals(2, results.size(), "无人值守 onSelect 应联动返回「无人值守 + AI 审议」双胶囊");

        SlashSelectionResult unattended = results.get(0);
        assertEquals("unattended:on", unattended.id(), "第一个胶囊属于无人值守条目");
        assertEquals(SlashDisplayPosition.BOTTOM, unattended.position(), "无人值守胶囊 bottom 渲染");
        SlashTokenEncoder.ParsedToken unattendedTok = SlashTokenEncoder.parseToken(unattended.token());
        assertNotNull(unattendedTok, "无人值守 token 可 parse");
        assertEquals(UnattendedToken.KIND, unattendedTok.kind(), "token kind=unattended.mode");

        SlashSelectionResult aiReview = results.get(1);
        assertEquals("ai-review:on", aiReview.id(), "第二个胶囊属于 AI 审议条目");
        assertEquals(SlashDisplayPosition.BOTTOM, aiReview.position(), "AI 审议胶囊 bottom 渲染");
        SlashTokenEncoder.ParsedToken aiReviewTok = SlashTokenEncoder.parseToken(aiReview.token());
        assertNotNull(aiReviewTok, "AI 审议 token 可 parse");
        assertEquals(AiReviewToken.KIND, aiReviewTok.kind(), "token kind=ai.review");

        verify(taskManager).runningTask("t-1");
        assertTrue(task.unattended, "业务标记 unattended 应置位");
        assertTrue(persisted.get() >= 1, "置位后应落盘 meta");
    }

    @Test
    void selectWithEmptyTaskIdStillReturnsCapsulesWithoutBusinessMark() {
        SlashCommandItem it = item();
        List<SlashSelectionResult> results = it.selectHandler().onSelect(it, "");

        assertEquals(2, results.size(), "空 taskId(草稿态)仍返回双胶囊");
        assertEquals("unattended:on", results.get(0).id());
        assertEquals("ai-review:on", results.get(1).id());
        verify(taskManager, never()).runningTask(any());
        assertFalse(task.unattended, "空 taskId 不写业务标记");
        assertEquals(0, persisted.get(), "空 taskId 不落盘");
    }

    // ---- cancel:按 token kind 复位 ----

    @Test
    void cancelUnattendedTokenTurnsUnattendedOff() {
        task.unattended = true;
        SlashCommandItem it = item();
        it.cancelHandler().onCancel(it, UnattendedToken.buildToken(), "t-1");
        assertFalse(task.unattended, "取消无人值守胶囊应复位 unattended");
        assertTrue(persisted.get() >= 1, "取消后应落盘 meta");
    }

    @Test
    void cancelAiReviewTokenOnlyTurnsAiReviewOff() {
        task.unattended = true;
        task.aiReview = true;
        SlashCommandItem it = item();
        it.cancelHandler().onCancel(it, AiReviewToken.buildToken(), "t-1");
        assertFalse(task.aiReview, "取消 AI 审议胶囊应复位 aiReview");
        assertTrue(task.unattended, "取消 AI 审议胶囊不影响无人值守开关");
    }
}