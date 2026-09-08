package dev.everyagent.worker.os;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link OsSandbox#normalizeBackend(String)} 的值域契约:wsl-direct / wsl-bwrap /
 * windows-mic / none 四档 + acl/wsl/direct 别名 + 空/未知值兜底 auto
 * (= 平台默认:Windows wsl-direct,非 Windows 直接 spawn)。纯函数,不发 WSL/Win32 调用。
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
}
