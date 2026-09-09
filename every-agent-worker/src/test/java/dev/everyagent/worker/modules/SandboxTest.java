package dev.everyagent.worker.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.everyagent.worker.rpc.SandboxViolationException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sandbox.resolveLoose 单元测试(不依赖 Spring/hub):
 * 允许「已删除/已丢失」等缺失路径(供 git.discard 恢复),但仍拒绝越界与符号链接逃逸。
 */
class SandboxTest {

    @TempDir
    Path tempDir;

    private Sandbox sandbox(Path root) throws Exception {
        return new Sandbox(new WorkspaceManager.Root(root, root.toRealPath()));
    }

    @Test
    void resolveLooseAllowsMissingFileInsideRoot() throws Exception {
        Path root = tempDir.resolve("ws");
        Files.createDirectories(root.resolve("novels/三国"));
        Sandbox sb = sandbox(root);
        Path resolved = sb.resolveLoose("novels/三国/修炼体系.md");
        assertEquals(root.resolve("novels/三国/修炼体系.md").normalize(), resolved);
    }

    @Test
    void resolveLooseRejectsLexicalEscape() throws Exception {
        Path root = tempDir.resolve("ws");
        Files.createDirectories(root);
        Sandbox sb = sandbox(root);
        assertThrows(SandboxViolationException.class, () -> sb.resolveLoose("../outside.txt"));
    }

    @Test
    void resolveLooseRejectsSymlinkParentEscape() throws Exception {
        Path root = tempDir.resolve("ws");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(root);
        Files.createDirectories(outside);
        Files.createSymbolicLink(root.resolve("link"), outside);
        Sandbox sb = sandbox(root);
        assertThrows(SandboxViolationException.class, () -> sb.resolveLoose("link/missing.txt"));
    }
}
