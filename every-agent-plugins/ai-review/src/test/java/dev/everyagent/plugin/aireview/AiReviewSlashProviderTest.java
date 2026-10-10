package dev.everyagent.plugin.aireview;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.task.TaskService;
import dev.everyagent.plugin.api.slash.SlashCommandItem;
import dev.everyagent.plugin.api.slash.SlashDisplayPosition;
import dev.everyagent.plugin.api.slash.SlashSelectionResult;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiReviewSlashProvider 单测:`/` 候选 id=ai-review:on;
 * select 返回单个 bottom 结果;taskId 非空时置 metadata["ai-review"]=true 并落盘,
 * 为空时不写业务标记仍返回胶囊;cancel 复位并落盘。
 *
 * <p>约束(§14.9):插件对 worker 任何 scope 零依赖——直接断言 provider 静态产物
 * (聚合是 worker SlashCommandRegistry 的职责,不在本插件测试范围),
 * 任务桩在本类内自建(实现 plugin-api ExecContext),不引用 worker TaskEntry。
 */
class AiReviewSlashProviderTest {

    private WorkerServices services;
    private TaskService taskService;
    private TaskRuntime task;
    private final Map<String, Object> taskMetadata = new HashMap<>();

    @BeforeEach
    void setUp() {
        services = mock(WorkerServices.class);
        taskService = mock(TaskService.class);
        // TaskService.get 返回 TaskRuntime(继承 ExecContext);provider 只读写 metadata()。
        task = mock(TaskRuntime.class);
        when(task.metadata()).thenReturn(taskMetadata);
        when(services.task()).thenReturn(taskService);
        doReturn(task).when(taskService).get("t-1");
    }

    private SlashCommandItem item() {
        return AiReviewSlashProvider.items(services).stream()
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

    // TaskRuntime 由 Mockito 桩(仅 stub metadata()):TaskService.get 返回 TaskRuntime,
    // 而 provider 只读写任务级 metadata 业务标记,无需引 worker 任务域类型(§14.9)。
}
