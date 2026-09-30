package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AllowDenyPaths 计算单测（跨平台；对齐 allow.rs 测试面：只读子路径入 deny、
 * TEMP/TMP 展开、缺失路径跳过、canonical 化）。
 */
class AllowDenyPathsTest {

    @TempDir
    Path tmp;

    @Test
    void readOnlySubpathsFallIntoDeny() throws IOException {
        Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        Path git = Files.createDirectories(workspace.resolve(".git"));
        Path everyagent = Files.createDirectories(workspace.resolve(".everyagent"));
        Files.writeString(workspace.resolve("agents.md"), "rules");
        Path agentsMd = workspace.resolve("agents.md");

        AllowDenyPaths paths = AllowDenyPaths.compute(
                List.of(new AllowDenyPaths.WritableRoot(workspace,
                        List.of(git, everyagent, agentsMd))),
                false, Map.of());

        assertTrue(paths.allow().contains(workspace.toRealPath()));
        assertEquals(1, paths.allow().size());
        assertTrue(paths.deny().contains(git));
        assertTrue(paths.deny().contains(everyagent));
        assertTrue(paths.deny().contains(agentsMd));
    }

    @Test
    void missingRootsAndSubpathsSkipped() throws IOException {
        Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        Path missingSub = workspace.resolve(".git");

        AllowDenyPaths paths = AllowDenyPaths.compute(
                List.of(new AllowDenyPaths.WritableRoot(workspace, List.of(missingSub)),
                        new AllowDenyPaths.WritableRoot(tmp.resolve("no-such-root"), List.of())),
                false, Map.of());

        assertEquals(1, paths.allow().size());
        assertTrue(paths.deny().isEmpty(), "missing subpaths must not enter deny");
    }

    @Test
    void tempEnvRootsIncludedWhenRequested() throws IOException {
        Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        Path tempDir = Files.createDirectories(tmp.resolve("temp"));
        Map<String, String> env = Map.of("TEMP", tempDir.toString(), "TMP", tempDir.toString());

        AllowDenyPaths withTemp = AllowDenyPaths.compute(
                List.of(new AllowDenyPaths.WritableRoot(workspace, List.of())), true, env);
        assertTrue(withTemp.allow().contains(tempDir.toRealPath()));
        assertEquals(2, withTemp.allow().size(), "TEMP 与 TMP 同目录去重后应为 2 个 allow 根");

        AllowDenyPaths withoutTemp = AllowDenyPaths.compute(
                List.of(new AllowDenyPaths.WritableRoot(workspace, List.of())), false, env);
        assertFalse(withoutTemp.allow().contains(tempDir.toRealPath()),
                "exclude_tmpdir_env_var 语义：TEMP/TMP 不计入");
    }

    @Test
    void relativeTempValuesIgnored() throws IOException {
        Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        AllowDenyPaths paths = AllowDenyPaths.compute(
                List.of(new AllowDenyPaths.WritableRoot(workspace, List.of())),
                true, Map.of("TEMP", "relative/temp", "TMP", ""));
        assertEquals(1, paths.allow().size(), "仅绝对路径 TEMP/TMP 计入（windows_temp_env_roots）");
    }
}
