package dev.everyagent.plugin.aireview;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.slash.SlashCommandItem;
import dev.everyagent.plugin.api.slash.SlashDisplayPosition;
import dev.everyagent.plugin.api.slash.SlashSelectionResult;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * AiReviewSlashProvider 单测:`/` 候选 id=ai-review:on、insertText 为合法 opaque token;
 * select 返回单个 bottom 结果且<b>不触碰任务实体 / metadata</b>(开关真相源是任务级 slash token,
 * token 写入由 slash 层落盘);cancel 无副作用(取消时 token 由 slash 层移除)。
 *
 * <p>约束(§14.9):插件对 worker 任何 scope 零依赖——直接断言 provider 静态产物
 * (聚合是 worker SlashCommandRegistry 的职责,不在本插件测试范围);
 * WorkerServices 用 Mockito 桩,断言 provider 不再访问 task() 任务域。
 */
class AiReviewSlashProviderTest {

    private WorkerServices services;

    @BeforeEach
    void setUp() {
        services = mock(WorkerServices.class);
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
        // insertText 为合法 opaque token(kind=ai.review)
        SlashTokenEncoder.ParsedToken tok = SlashTokenEncoder.parseToken(it.insertText());
        assertNotNull(tok, "insertText 应为可 parse 的 opaque token");
        assertEquals(AiReviewToken.KIND, tok.kind(), "token kind=ai.review");
    }

    // ---- select:单个 bottom 胶囊,不触碰任务实体 ----

    @Test
    void selectReturnsSingleBottomCapsuleWithoutTouchingTask() {
        SlashCommandItem it = item();
        List<SlashSelectionResult> results = it.selectHandler().onSelect(it, "t-1");

        assertEquals(1, results.size(), "AI 审议 onSelect 应返回单胶囊");
        SlashSelectionResult r = results.get(0);
        assertEquals("ai-review:on", r.id(), "胶囊归属 AI 审议条目");
        assertEquals(SlashDisplayPosition.BOTTOM, r.position(), "AI 审议胶囊 bottom 渲染");
        SlashTokenEncoder.ParsedToken tok = SlashTokenEncoder.parseToken(r.token());
        assertNotNull(tok, "token 可 parse");
        assertEquals(AiReviewToken.KIND, tok.kind(), "token kind=ai.review");

        // 开关真相源是 slash token(token 写入由 slash 层落盘):select 回调不再查询/写入任务实体与 metadata
        verify(services, never()).task();
    }

    // ---- select:空 taskId(草稿态)仍返回胶囊,同样不触碰任务实体 ----

    @Test
    void selectWithEmptyTaskIdStillReturnsCapsule() {
        SlashCommandItem it = item();
        List<SlashSelectionResult> results = it.selectHandler().onSelect(it, "");

        assertEquals(1, results.size(), "空 taskId(草稿态)仍返回胶囊");
        assertEquals("ai-review:on", results.get(0).id());
        verify(services, never()).task();
    }

    // ---- cancel:无副作用(取消时 token 由 slash 层移除) ----

    @Test
    void cancelIsSideEffectFree() {
        SlashCommandItem it = item();
        // 不挂回调 → 默认 NOOP;直接调用不抛异常且不触碰任务实体
        it.cancelHandler().onCancel(it, AiReviewToken.buildToken(), "t-1");
        verify(services, never()).task();
    }
}
