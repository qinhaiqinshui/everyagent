package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.slash.SlashCommandItem;
import dev.everyagent.plugin.api.slash.SlashDisplayPosition;
import dev.everyagent.plugin.api.slash.SlashSelectionResult;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import dev.everyagent.plugin.api.slash.SlashTokenService;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NetworkSlashProvider 单测:`/` 候选 id=network:on;select 返回单个 bottom 结果
 * (token 可 parse 为 kind=network.access)且<b>不触碰任务服务</b>——开关的唯一真相源是
 * 任务级 {@code network.access} token(由 slash 层落盘 {@code slash-tokens.json}),
 * provider 回调不再读写任务 metadata;cancel 无副作用;非 wsl-ubuntu 后端不提供该命令
 * (真断网只有本后端做得到);{@link NetworkTaskFlag#isOn} 改为查任务级 token,恒以磁盘为准。
 *
 * <p>约束(§14.9):插件对 worker 任何 scope 零依赖——本类只 mock plugin-api 接口,
 * 不引用 worker TaskEntry。
 */
class NetworkSlashProviderTest {

    private WorkerServices services;
    private SandboxBackend backend;
    private SlashTokenService slashTokens;

    @BeforeEach
    void setUp() {
        services = mock(WorkerServices.class);
        backend = mock(SandboxBackend.class);
        slashTokens = mock(SlashTokenService.class);
        when(services.sandbox()).thenReturn(backend);
        when(services.slashTokens()).thenReturn(slashTokens);
        when(backend.id()).thenReturn("wsl-ubuntu");
    }

    private SlashCommandItem item() {
        return NetworkSlashProvider.items(services).stream()
                .filter(i -> NetworkSlashProvider.ITEM_ID.equals(i.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未注册 network:on 条目"));
    }

    // ---- 候选 ----

    @Test
    void itemsExposeNetworkCandidate() {
        SlashCommandItem it = item();
        assertEquals("network:on", it.id());
        assertEquals("禁用网络", it.title());
        SlashTokenEncoder.ParsedToken tok = SlashTokenEncoder.parseToken(it.insertText());
        assertNotNull(tok, "insertText 应为可解析的 opaque token");
        assertEquals(NetworkToken.KIND, tok.kind(), "token kind=network.access");
    }

    @Test
    void itemsHiddenWhenBackendCannotBlockNetwork() {
        when(backend.id()).thenReturn("windows-mic");
        assertEquals(List.of(), NetworkSlashProvider.items(services),
                "非 wsl-ubuntu 后端做不到硬断网,不提供 /禁用网络");
    }

    // ---- select:只返回 bottom 胶囊,不写任务状态 ----

    @Test
    void selectReturnsBottomCapsuleWithoutTouchingTask() {
        SlashCommandItem it = item();
        List<SlashSelectionResult> results = it.selectHandler().onSelect(it, "t-1");

        assertEquals(1, results.size(), "网络 onSelect 应返回单个 bottom 胶囊");
        SlashSelectionResult r = results.get(0);
        assertEquals(NetworkSlashProvider.ITEM_ID, r.id(), "胶囊归属 network:on 条目");
        assertEquals(SlashDisplayPosition.BOTTOM, r.position(), "网络胶囊 bottom 渲染");
        SlashTokenEncoder.ParsedToken tok = SlashTokenEncoder.parseToken(r.token());
        assertNotNull(tok, "网络 token 可 parse");
        assertEquals(NetworkToken.KIND, tok.kind(), "token kind=network.access");

        // 开关真相源是任务级 token(slash 层落盘),select 回调不再读写任务服务。
        verify(services, never()).task();
    }

    // ---- cancel:无副作用 ----

    @Test
    void cancelHasNoSideEffect() {
        SlashCommandItem it = item();
        assertDoesNotThrow(
                () -> it.cancelHandler().onCancel(it, NetworkToken.buildToken(), "t-1"),
                "取消应为默认 NOOP,不抛异常");
        assertDoesNotThrow(
                () -> it.cancelHandler().onCancel(it, NetworkToken.buildToken(), null),
                "草稿态取消同样无副作用");
        verify(services, never()).task();
    }

    // ---- isOn:查任务级 network.access token(恒以磁盘为准) ----

    @Test
    void isOnReadsTaskLevelNetworkToken() {
        when(slashTokens.hasKind("t-1", NetworkToken.KIND)).thenReturn(true);
        assertTrue(NetworkTaskFlag.isOn(services, "t-1"), "token 在 → 禁网开启");

        when(slashTokens.hasKind("t-1", NetworkToken.KIND)).thenReturn(false);
        assertFalse(NetworkTaskFlag.isOn(services, "t-1"), "token 不在 → 禁网关闭");
    }

    @Test
    void isOnToleratesMissingServicesOrSubject() {
        assertFalse(NetworkTaskFlag.isOn(null, "t-1"), "services 缺失 = 未开启");
        assertFalse(NetworkTaskFlag.isOn(services, null), "subjectId 缺失 = 未开启");
        assertFalse(NetworkTaskFlag.isOn(services, "  "), "subjectId 空白 = 未开启");
    }
}
