package dev.everyagent.worker.slash;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.slash.SlashCommandItem;
import dev.everyagent.plugin.api.slash.SlashDisplayPosition;
import dev.everyagent.plugin.api.slash.SlashProvider;
import dev.everyagent.plugin.api.slash.SlashSelectionResult;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;

/**
 * {@link SlashMethods#expandChains} 单测:chainIds → 额外 bottom 结果。
 *
 * <p>锁死三条语义:
 * <ol>
 *   <li>联动条目已注册:按 id 反查其自身 insertText 作为 token(经 injectSlashId 注入该条目
 *       id)追加为第二个 bottom 结果;原结果在前、联动结果在后;</li>
 *   <li>联动条目未注册(被联动插件未启用):静默跳过,只剩原结果,不抛异常;</li>
 *   <li>未声明 chainIds:结果原样返回(零行为变化)。</li>
 * </ol>
 *
 * <p>用真实 {@link SlashCommandRegistry}(registerProvider/itemById),不依赖 Spring;
 * 直接调用 package-private static 方法,无需装配 SlashMethods。
 */
class SlashChainExpandTest {

    private static final String KIND_UNATTENDED = "unattended.mode";
    private static final String KIND_AI_REVIEW = "ai.review";

    /** 造一个注册条目(id + 可解析 opaque token 作为 insertText)。 */
    private static SlashCommandItem item(String id, String kind) {
        return new SlashCommandItem(id, id, null, null, "test",
                SlashTokenEncoder.buildToken(kind, id, null, Json.obj()));
    }

    /** 把若干条目分别作为独立来源注册进注册表。 */
    private static SlashCommandRegistry registryOf(SlashCommandItem... items) {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        for (SlashCommandItem it : items) {
            SlashProvider provider = () -> List.of(it);
            registry.registerProvider(it.id(), provider);
        }
        return registry;
    }

    // ---- ① 联动条目已注册 → 展开出第二个 bottom 结果 ----

    @Test
    void expandChainsAppendsRegisteredChainItemAsSecondBottomResult() {
        String tokenA = SlashTokenEncoder.buildToken(KIND_UNATTENDED, "无人值守", null, Json.obj());
        SlashCommandRegistry registry = registryOf(
                item("unattended:on", KIND_UNATTENDED),
                item("ai-review:on", KIND_AI_REVIEW));

        List<SlashSelectionResult> results = List.of(
                SlashSelectionResult.bottom(tokenA, "unattended:on", List.of("ai-review:on")));

        List<SlashSelectionResult> expanded = SlashMethods.expandChains(registry, results);

        assertEquals(2, expanded.size(), "联动展开为 2 条: " + expanded);
        // 第 1 条:原结果(token 原样保留)
        assertEquals(tokenA, expanded.get(0).token(), "原结果 token 不变");
        assertEquals("unattended:on", expanded.get(0).id());

        // 第 2 条:联动条目 ai-review:on 自身的 insertText 注入其 id 后作为 token
        SlashSelectionResult chain = expanded.get(1);
        assertEquals("ai-review:on", chain.id(), "联动条目归属 id = chainId");
        assertEquals(SlashDisplayPosition.BOTTOM, chain.position(), "联动结果为 bottom 胶囊");
        assertEquals(List.of(), chain.chainIds(), "联动结果不再声明 chainIds(避免递归)");
        SlashTokenEncoder.ParsedToken tok = SlashTokenEncoder.parseToken(chain.token());
        assertNotNull(tok, "联动 token 可解析");
        assertEquals(KIND_AI_REVIEW, tok.kind(), "联动 token kind = 被联动条目自身 kind");
        assertEquals("ai-review:on", tok.payload().path("slashId").asString(),
                "slashId 注入为 chainId");
    }

    // ---- ② 联动条目未注册 → 静默降级为单胶囊,不抛异常 ----

    @Test
    void expandChainsSkipsUnregisteredChainItemWithoutError() {
        String tokenA = SlashTokenEncoder.buildToken(KIND_UNATTENDED, "无人值守", null, Json.obj());
        // 只注册 A,不注册 ai-review:on(模拟 ai-review 插件未启用)
        SlashCommandRegistry registry = registryOf(item("unattended:on", KIND_UNATTENDED));

        List<SlashSelectionResult> results = List.of(
                SlashSelectionResult.bottom(tokenA, "unattended:on", List.of("ai-review:on")));

        List<SlashSelectionResult> expanded = assertDoesNotThrow(
                () -> SlashMethods.expandChains(registry, results));

        assertEquals(1, expanded.size(), "联动条目未注册 → 只剩原结果");
        assertEquals(tokenA, expanded.get(0).token());
        assertEquals("unattended:on", expanded.get(0).id());
    }

    // ---- ③ 无 chainIds → 零行为变化 ----

    @Test
    void expandChainsWithoutChainIdsIsNoop() {
        SlashCommandRegistry registry = registryOf(item("unattended:on", KIND_UNATTENDED));
        List<SlashSelectionResult> results = List.of(
                SlashSelectionResult.bottom("plain", "unattended:on"));

        List<SlashSelectionResult> expanded = SlashMethods.expandChains(registry, results);

        assertEquals(1, expanded.size());
        assertEquals("plain", expanded.get(0).token());
    }
}