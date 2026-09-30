package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.spi.SandboxBackend.Access;
import dev.everyagent.plugin.api.spi.SandboxBackend.MountRequest;
import dev.everyagent.plugin.api.spi.SandboxProvider.SandboxConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodexSandboxBackend}：mount 恒等映射 + 根登记；
 * {@link CodexSandboxManager}：幂等登记/访问语义切换/SandboxConfig 折叠。
 */
class CodexSandboxBackendTest {

    @TempDir
    Path tempDir;

    private CodexSandboxManager manager() {
        return new CodexSandboxManager(
                new CodexSandboxOptions(tempDir, null, null, null, false, null), 30_000);
    }

    /** mount 返回恒等映射（codex 命令跑宿主路径），同时登记根。 */
    @Test
    void mountIsIdentityMappingAndRegistersRoots() {
        CodexSandboxManager manager = manager();
        CodexSandboxBackend backend = new CodexSandboxBackend(manager);
        Path ws = tempDir.resolve("ws");
        Path ro = tempDir.resolve("ro");
        Map<Path, String> mounted = backend.mount(List.of(
                new MountRequest(ws, Access.READ_WRITE),
                new MountRequest(ro, Access.READ_ONLY)));
        assertEquals(2, mounted.size());
        assertEquals(ws.toString(), mounted.get(ws), "恒等映射:宿主路径原样返回");
        assertEquals(ro.toString(), mounted.get(ro));
        assertEquals(List.of(ws), manager.writeRoots());
        assertEquals(List.of(ro), manager.readRoots());
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

    /** onWorkspaceRemoved no-op:登记与 ACL 均不回收(持久供给,state 文件对账)。 */
    @Test
    void onWorkspaceRemovedIsNoOp() {
        CodexSandboxManager manager = manager();
        CodexSandboxBackend backend = new CodexSandboxBackend(manager);
        Path ws = tempDir.resolve("ws");
        backend.mount(List.of(new MountRequest(ws, Access.READ_WRITE)));
        backend.onWorkspaceRemoved(ws);
        assertEquals(List.of(ws), manager.writeRoots(), "登记保留,无异常抛出");
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
