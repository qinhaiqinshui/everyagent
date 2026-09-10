package dev.everyagent.worker.os;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.modules.WorkspaceManager.Registered;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link OsSandbox#normalizeBackend(String)} 的值域契约:wsl-direct / wsl-bwrap /
 * windows-mic / none 四档 + acl/wsl/direct 别名 + 空/未知值兜底 auto
 * (= 平台默认:Windows wsl-direct,非 Windows 直接 spawn)。纯函数,不发 WSL/Win32 调用。
 * 另钉住 wsl-direct 挂载列表拼装({@link OsSandbox#wslDirectMountRoots()}):
 * 工作区根 + 全部工作区外部授权根(§7.17),Path 去重。
 */
class OsSandboxBackendTest {

    @Test
    void canonicalValuesPassThrough() {
        assertEquals("auto", OsSandbox.normalizeBackend("auto"));
        assertEquals("wsl-direct", OsSandbox.normalizeBackend("wsl-direct"));
        assertEquals("wsl-bwrap", OsSandbox.normalizeBackend("wsl-bwrap"));
        assertEquals("windows-mic", OsSandbox.normalizeBackend("windows-mic"));
        assertEquals("none", OsSandbox.normalizeBackend("none"));
    }

    @Test
    void aliasesMapToCanonicalBackends() {
        assertEquals("windows-mic", OsSandbox.normalizeBackend("acl"));
        assertEquals("windows-mic", OsSandbox.normalizeBackend("mic"));
        assertEquals("wsl-bwrap", OsSandbox.normalizeBackend("wsl"));
        assertEquals("wsl-bwrap", OsSandbox.normalizeBackend("bwrap"));
        assertEquals("wsl-direct", OsSandbox.normalizeBackend("direct"));
    }

    @Test
    void nullBlankAndUnknownFallBackToAuto() {
        assertEquals("auto", OsSandbox.normalizeBackend(null));
        assertEquals("auto", OsSandbox.normalizeBackend(""));
        assertEquals("auto", OsSandbox.normalizeBackend("   "));
        assertEquals("auto", OsSandbox.normalizeBackend("docker"));
    }

    @Test
    void normalizationTrimsAndLowercases() {
        assertEquals("wsl-direct", OsSandbox.normalizeBackend("  WSL-DIRECT  "));
        assertEquals("wsl-direct", OsSandbox.normalizeBackend("DIRECT"));
        assertEquals("wsl-bwrap", OsSandbox.normalizeBackend("  WSL-BWRAP  "));
        assertEquals("windows-mic", OsSandbox.normalizeBackend("ACL"));
    }

    // ---- wsl-direct 挂载列表:工作区根 + 全部工作区 externalRoots(去重;§7.17) ----

    @Test
    void wslDirectMountRootsMergesExternalRootsDeduped() {
        Path wsA = Path.of("C:\\ws\\a");
        Path wsB = Path.of("C:\\ws\\b");
        Path extA = Path.of("C:\\ext\\a");
        Path extB = Path.of("C:\\ext\\b");
        WorkspaceManager wm = mock(WorkspaceManager.class);
        when(wm.list()).thenReturn(List.of(
                new Registered(wsA.toString(), 1000L),
                new Registered(wsB.toString(), 2000L)));
        // extB 与工作区根重叠、extA 与 extB 互不重叠;externalRoots 含工作区根形态时去重
        when(wm.allExternalRoots()).thenReturn(List.of(extA, wsB));

        OsSandbox sandbox = new OsSandbox(new WorkerProperties(), wm);
        assertEquals(List.of(wsA, wsB, extA), sandbox.wslDirectMountRoots(),
                "工作区根在前,外部根去重后并入");
    }

    @Test
    void wslDirectMountRootsDegenerateWithoutRegistry() {
        // 未初始化(无 WorkspaceManager)或注册表空:回退空表,不抛异常
        assertEquals(List.of(), new OsSandbox(new WorkerProperties(), null).wslDirectMountRoots());
        WorkspaceManager wm = mock(WorkspaceManager.class);
        when(wm.list()).thenReturn(List.of());
        when(wm.allExternalRoots()).thenReturn(List.of());
        assertEquals(List.of(), new OsSandbox(new WorkerProperties(), wm).wslDirectMountRoots());
    }
}
