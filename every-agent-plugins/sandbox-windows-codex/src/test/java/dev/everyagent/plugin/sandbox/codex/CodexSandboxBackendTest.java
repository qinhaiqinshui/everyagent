package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.spi.SandboxBackend.Access;
import dev.everyagent.plugin.api.spi.SandboxBackend.PathGrant;
import dev.everyagent.plugin.api.spi.SandboxProvider.SandboxConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodexSandboxBackend}：grant/revoke 驱动根登记 + 恒等翻译；
 * {@link CodexSandboxManager}：幂等登记/访问语义切换/回收/配置折叠。
 */
class CodexSandboxBackendTest {

    @TempDir
    Path tempDir;

    private CodexSandboxManager manager() {
        return new CodexSandboxManager(
                new CodexSandboxOptions(tempDir, null, null, null, false, null), 30_000);
    }

    /** grant 登记根（RW → 写根、RO → 读根），翻译恒等（codex 命令跑宿主路径）。 */
    @Test
    void grantRegistersRootsAndTranslationIsIdentity() {
        CodexSandboxManager manager = manager();
        CodexSandboxBackend backend = new CodexSandboxBackend(manager);
        Path ws = tempDir.resolve("ws");
        Path ro = tempDir.resolve("ro");
        backend.grant(List.of(
                new PathGrant(ws, Access.READ_WRITE),
                new PathGrant(ro, Access.READ_ONLY)));

        assertEquals(List.of(ws), manager.writeRoots());
        assertEquals(List.of(ro), manager.readRoots());
        assertEquals(ws.toString(), backend.toSandbox(ws), "恒等翻译:宿主路径原样返回");
        assertEquals(ws, backend.toHost(ws.toString()));
    }

    /** revoke 撤登记（幂等）:回收后该根不再进会话 —— 陈旧 ACE 因 SID 不入令牌而失效。 */
    @Test
    void revokeUnregistersRoots() {
        CodexSandboxManager manager = manager();
        CodexSandboxBackend backend = new CodexSandboxBackend(manager);
        Path ws = tempDir.resolve("ws");
        Path ro = tempDir.resolve("ro");
        backend.grant(List.of(
                new PathGrant(ws, Access.READ_WRITE),
                new PathGrant(ro, Access.READ_ONLY)));

        backend.revoke(List.of(ws));
        assertTrue(manager.writeRoots().isEmpty(), "写根已回收");
        assertEquals(List.of(ro), manager.readRoots(), "无关根不受影响");

        backend.revoke(List.of(ro));
        assertTrue(manager.readRoots().isEmpty());
        backend.revoke(List.of(ro)); // 幂等
        assertTrue(manager.readRoots().isEmpty());
    }

    /** 同根重复登记幂等;RO 覆盖 RW 时从写根表移除(收紧不放宽)。 */
    @Test
    void registerIsIdempotentAndAccessDowngradeMovesRoot() {
        CodexSandboxManager manager = manager();
        Path ws = tempDir.resolve("ws");
        manager.register(ws, Access.READ_WRITE);
        manager.register(ws, Access.READ_WRITE);
        assertEquals(1, manager.writeRoots().size(), "重复 RW 登记去重");
        manager.register(ws, Access.READ_ONLY);
        assertTrue(manager.writeRoots().isEmpty(), "RW → RO 收紧");
        assertEquals(1, manager.readRoots().size());
    }

    /** 后端 id 稳定（供 SandboxPathRegistry 的代次判定与重放）。 */
    @Test
    void backendIdIsStable() {
        CodexSandboxBackend backend = new CodexSandboxBackend(manager());
        assertEquals("codex", backend.id());
    }

    /** SandboxConfig 折叠:timeoutMs>0 生效,networkDenied 透传;0 回退 worker 默认。 */
    @Test
    void acceptFoldsSandboxConfig() {
        CodexSandboxManager manager = manager();
        assertEquals(30_000, manager.execTimeoutMs(), "未折叠前用 worker 默认");
        assertFalse(manager.networkDenied());

        manager.accept(new SandboxConfig("codex", true, true, false,
                123_456, tempDir, new Object()));
        assertEquals(123_456, manager.execTimeoutMs());
        assertTrue(manager.networkDenied());

        CodexSandboxManager zero = manager();
        zero.accept(new SandboxConfig("codex", true, false, false, 0, tempDir, null));
        assertEquals(30_000, zero.execTimeoutMs(), "timeoutMs=0 回退默认");
        assertFalse(zero.networkDenied());
    }
}
