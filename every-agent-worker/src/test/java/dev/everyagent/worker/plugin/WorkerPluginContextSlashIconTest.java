package dev.everyagent.worker.plugin;

import dev.everyagent.plugin.api.slash.SlashCommandItem;
import dev.everyagent.worker.slash.SlashCommandRegistry;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorkerPluginContextImpl「注册 slash provider 时对 icon 做前置粗校验」的回归测试
 * （审计 M-8 双防线的 worker 侧；最终防线是前端 sanitizeSlashIcon → SVG 净化器）。
 *
 * <p>锁死：经上下文注册的条目在加载边界统一过 sanitizeIcon——
 * 非 {@code <svg} 开头 / 剔除 script 与 on* 事件属性；合法内联 SVG 不受影响，
 * 其余展示字段（id/title 等）原样保留。
 */
class WorkerPluginContextSlashIconTest {

    @Test
    void sanitizesIconsAtProviderLoadBoundary() {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        newContext(registry).registerSlashProvider("icon-test", () -> List.of(
                // ① <svg onload=…>：事件属性剔除，图形保留
                new SlashCommandItem("evil:1", "事件注入", null,
                        "<svg onload=\"alert(1)\"><path d=\"M0 0\"/></svg>", "g", "/e1"),
                // ② 非 <svg 开头（img onerror）：整体拒绝 → icon 置 null
                new SlashCommandItem("evil:2", "非svg", null,
                        "<img src=x onerror=alert(1)>", "g", "/e2"),
                // ③ <script> 元素整体剔除，白名单兄弟元素保留
                new SlashCommandItem("evil:3", "脚本元素", null,
                        "<svg><script>alert(1)</script><circle r=\"4\"/></svg>", "g", "/e3"),
                // ④ 合法内联 SVG（SkillSlashProvider 形态）：原样保留
                new SlashCommandItem("ok:1", "正常", null,
                        "<svg viewBox=\"0 0 16 16\" stroke=\"currentColor\" stroke-width=\"1.6\">"
                                + "<path d=\"M3.2 4.1C3.2 3.5 3.7 3 4.3 3H7.6V13H4.5\"/></svg>", "g", "/o1"),
                // ⑤ 无 icon（契约可选）：不受影响
                new SlashCommandItem("ok:2", "无图标", null, null, "g", "/o2")));

        Map<String, SlashCommandItem> byId = new HashMap<>();
        for (SlashCommandItem item : registry.list()) {
            byId.put(item.id(), item);
        }

        // ① onload 剔除、svg/path 保留
        String icon1 = byId.get("evil:1").icon();
        assertTrue(icon1 != null && icon1.startsWith("<svg>"), icon1);
        assertFalse(icon1.contains("onload"), icon1);
        assertFalse(icon1.contains("alert"), icon1);
        assertTrue(icon1.contains("<path"), icon1);

        // ② 非 svg 根 → icon 置 null，其余字段保留（条目本身仍可用）
        assertNull(byId.get("evil:2").icon());
        assertEquals("非svg", byId.get("evil:2").title());

        // ③ script 元素剔除、circle 保留
        String icon3 = byId.get("evil:3").icon();
        assertTrue(icon3 != null && icon3.startsWith("<svg>"), icon3);
        assertFalse(icon3.contains("script"), icon3);
        assertFalse(icon3.contains("alert"), icon3);
        assertTrue(icon3.contains("<circle"), icon3);

        // ④ 合法 icon 原样保留（含 stroke-width 等正常属性不被误伤）
        String okIcon = "<svg viewBox=\"0 0 16 16\" stroke=\"currentColor\" stroke-width=\"1.6\">"
                + "<path d=\"M3.2 4.1C3.2 3.5 3.7 3 4.3 3H7.6V13H4.5\"/></svg>";
        assertEquals(okIcon, byId.get("ok:1").icon());

        // ⑤ null icon 条目原样通过
        assertNull(byId.get("ok:2").icon());
        assertEquals(5, byId.size());
    }

    /** 构造只接通 SlashCommandRegistry 的最小上下文（其余依赖置 null，本用例不触及）。 */
    private static WorkerPluginContextImpl newContext(SlashCommandRegistry slashRegistry) {
        return new WorkerPluginContextImpl("icon-test", null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                slashRegistry, null, null, null, null);
    }
}
