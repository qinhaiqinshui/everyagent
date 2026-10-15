package dev.everyagent.plugin.unattended;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.slash.SlashCommandItem;
import dev.everyagent.plugin.api.slash.SlashDisplayPosition;
import dev.everyagent.plugin.api.slash.SlashSelectionResult;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * UnattendedSlashProvider 单测:真相源 = 任务级 {@code unattended.mode} slash token。
 *
 * <p>断言:`/` 候选 id=unattended:on、insertText 可解析为本 kind;select 只返回单个
 * bottom 胶囊且不再触碰 {@code services.task()}(开关由 slash token 落盘承载,
 * 不再写任务 metadata 副本);cancel 为无副作用(默认 NOOP)。
 *
 * <p>约束(§14.9):插件对 worker 任何 scope 零依赖——只断言 provider 静态产物,
 * WorkerServices 以 Mockito mock 承接。
 */
class UnattendedSlashProviderTest {

    private WorkerServices services;

    @BeforeEach
    void setUp() {
        services = mock(WorkerServices.class);
    }

    private SlashCommandItem item() {
        return UnattendedSlashProvider.items(services).stream()
                .filter(i -> "unattended:on".equals(i.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未注册 unattended:on 条目"));
    }

    // ---- 候选 ----

    @Test
    void itemsExposeUnattendedCandidateWithParseableToken() {
        SlashCommandItem it = item();
        assertEquals("unattended:on", it.id());
        assertEquals("无人值守", it.title());

        SlashTokenEncoder.ParsedToken tok = SlashTokenEncoder.parseToken(it.insertText());
        assertNotNull(tok, "insertText 应为可解析的 opaque token");
        assertEquals(UnattendedToken.KIND, tok.kind(), "token kind=unattended.mode");
    }

    // ---- select:返回单个 bottom 胶囊,且不再触碰 task() ----

    @Test
    void selectReturnsSingleBottomCapsuleWithoutTouchingTask() {
        SlashCommandItem it = item();
        List<SlashSelectionResult> results = it.selectHandler().onSelect(it, "t-1");

        assertEquals(1, results.size(), "无人值守 onSelect 应返回单胶囊");
        SlashSelectionResult r = results.get(0);
        assertEquals("unattended:on", r.id(), "胶囊归属无人值守条目");
        assertEquals(SlashDisplayPosition.BOTTOM, r.position(), "无人值守胶囊 bottom 渲染");
        // 联动:声明额外应用条目 ai-review:on(第二个胶囊由 worker 斜杠层按 id 从注册表取
        // 该条目自身 token 生成,故本插件不依赖 ai-review 插件)
        assertEquals(List.of("ai-review:on"), r.chainIds(), "onSelect 声明联动 AI 审议条目");

        SlashTokenEncoder.ParsedToken tok = SlashTokenEncoder.parseToken(r.token());
        assertNotNull(tok, "token 可 parse");
        assertEquals(UnattendedToken.KIND, tok.kind(), "token kind=unattended.mode");

        // 真相源 = slash token:不再借 services.task() 写 metadata 副本
        verify(services, never()).task();
    }

    // ---- cancel:无副作用(默认 NOOP) ----

    @Test
    void cancelIsSideEffectFree() {
        SlashCommandItem it = item();
        assertDoesNotThrow(() ->
                it.cancelHandler().onCancel(it, UnattendedToken.buildToken(), "t-1"));
        verify(services, never()).task();
    }
}