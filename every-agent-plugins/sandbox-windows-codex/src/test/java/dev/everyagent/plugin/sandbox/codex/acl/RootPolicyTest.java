package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RootPolicy 敏感过滤单测（跨平台；对齐 setup.rs::filter_sensitive_write_roots：
 * codexHome 本身与三个状态目录（及其子路径）剥离，兄弟路径放行）。
 */
class RootPolicyTest {

    @TempDir
    Path tmp;

    private Path codexHome() throws IOException {
        return Files.createDirectories(tmp.resolve("codex-home"));
    }

    @Test
    void stripsSandboxStateDirsAndCodexHomeItself() throws IOException {
        Path home = codexHome();
        Path sandbox = Files.createDirectories(home.resolve(".sandbox"));
        Path bin = Files.createDirectories(home.resolve(".sandbox-bin"));
        Path secrets = Files.createDirectories(home.resolve(".sandbox-secrets"));
        Path deepState = Files.createDirectories(sandbox.resolve("nested").resolve("state"));
        Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        Path homeSibling = Files.createDirectories(home.resolve("tmp-scratch"));

        RootPolicy policy = new RootPolicy(
                List.of(new RootPolicy.WriteRoot(workspace, "S-1-5-21-1-1-1-1"),
                        new RootPolicy.WriteRoot(sandbox, "S-1-5-21-2-2-2-2"),
                        new RootPolicy.WriteRoot(bin, "S-1-5-21-3-3-3-3"),
                        new RootPolicy.WriteRoot(secrets, "S-1-5-21-4-4-4-4"),
                        new RootPolicy.WriteRoot(deepState, "S-1-5-21-5-5-5-5"),
                        new RootPolicy.WriteRoot(home, "S-1-5-21-6-6-6-6"),
                        new RootPolicy.WriteRoot(homeSibling, "S-1-5-21-7-7-7-7")),
                List.of(), List.of(), List.of());

        RootPolicy sanitized = policy.sanitized(home);

        assertEquals(2, sanitized.writeRoots().size());
        assertTrue(sanitized.writeRoots().stream().anyMatch(r -> r.root().equals(workspace)));
        assertTrue(sanitized.writeRoots().stream().anyMatch(r -> r.root().equals(homeSibling)));
    }

    @Test
    void sensitiveDetectionUsesCanonicalKeys() throws IOException {
        Path home = codexHome();
        Path sandbox = Files.createDirectories(home.resolve(".sandbox"));
        // 已存在路径的变体拼法（大小写差异仅在 Windows 文件系统上等价；
        // lexical 键大小写归一在所有平台生效）
        Path variant = home.resolve(".SANDBOX").resolve("x");
        assertTrue(RootPolicy.isSensitivePath(sandbox, home));
        assertTrue(RootPolicy.isSensitivePath(home, home));
        assertTrue(RootPolicy.isSensitivePath(home.resolve(".sandbox-secrets").resolve("creds"),
                home));
        assertFalse(RootPolicy.isSensitivePath(home.resolve("user-tmp"), home));
        // 变体（不存在路径走词法键）也应命中
        assertTrue(RootPolicy.isSensitivePath(variant, home),
                "canonical 键大小写归一：.SANDBOX 变体命中 .sandbox 前缀");
    }

    @Test
    void requireNoSensitiveWriteRootsFailsClosed() throws IOException {
        Path home = codexHome();
        Path sandbox = Files.createDirectories(home.resolve(".sandbox"));
        RootPolicy bad = new RootPolicy(
                List.of(new RootPolicy.WriteRoot(sandbox, "S-1-5-21-1-1-1-1")),
                List.of(), List.of(), List.of());
        assertThrows(IllegalStateException.class, () -> bad.requireNoSensitiveWriteRoots(home));

        RootPolicy good = new RootPolicy(
                List.of(new RootPolicy.WriteRoot(tmp.resolve("ws"), "S-1-5-21-1-1-1-1")),
                List.of(), List.of(), List.of());
        assertEquals(good, good.requireNoSensitiveWriteRoots(home));
    }

    @Test
    void sanitizedDedupsRootsByCanonicalKey() throws IOException {
        Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        RootPolicy policy = new RootPolicy(
                List.of(new RootPolicy.WriteRoot(workspace, "S-1-5-21-1-1-1-1"),
                        new RootPolicy.WriteRoot(workspace, "S-1-5-21-9-9-9-9")),
                List.of(), List.of(), List.of());
        assertEquals(1, policy.sanitized(codexHome()).writeRoots().size());
        assertEquals("S-1-5-21-1-1-1-1",
                policy.sanitized(codexHome()).writeRoots().get(0).capSid(), "先登记的 cap 优先");
    }
}
