package dev.everyagent.plugin.sandbox.codex.accounts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CapSids 单测（跨平台纯逻辑；对齐 cap.rs tests：等价拼写共享键、写根分键、旧格式兼容）。
 */
class CapSidsTest {

    @TempDir
    Path tempDir;

    @Test
    void generatedSidMatchesSyntheticShape() {
        String sid = CapSids.randomCapSid();
        assertTrue(CapSids.CAP_SID_PATTERN.matcher(sid).matches(),
                "S-1-5-21 + 4 subauthority 形态: " + sid);
        // subauthority 是无符号 32 位（生成源 nextInt 的无符号化）
        for (String part : sid.substring("S-1-5-21-".length()).split("-")) {
            assertTrue(Long.parseLong(part) <= 0xFFFFFFFFL);
        }
        assertNotEquals(CapSids.randomCapSid(), CapSids.randomCapSid(), "随机性");
    }

    @Test
    void loadOrCreatePersistsAndRoundTrips() throws IOException {
        Path home = tempDir.resolve("home");
        CapSids first = CapSids.loadOrCreate(home);
        assertNotNull(first.workspace);
        assertNotNull(first.readonly);
        assertTrue(CapSids.CAP_SID_PATTERN.matcher(first.workspace).matches());
        assertTrue(Files.exists(CapSids.capSidFile(home)), "cap_sid 落盘");

        CapSids second = CapSids.loadOrCreate(home);
        assertEquals(first.workspace, second.workspace, "workspace 稳定");
        assertEquals(first.readonly, second.readonly, "readonly 稳定");
    }

    @Test
    void legacyBareSidFormatIsUpgradedToWorkspaceKey() throws IOException {
        Path home = tempDir.resolve("home");
        Files.createDirectories(home);
        Files.writeString(CapSids.capSidFile(home), "S-1-5-21-111-222-333-444\n");
        CapSids caps = CapSids.loadOrCreate(home);
        assertEquals("S-1-5-21-111-222-333-444", caps.workspace, "裸 SID → workspace 值");
        assertNotNull(caps.readonly, "旧格式缺 readonly：新造");
        assertTrue(caps.workspaceByCwd.isEmpty());
        // 重写为 JSON 后再次加载仍稳定
        assertEquals(caps.workspace, CapSids.loadOrCreate(home).workspace);
        assertTrue(Files.readString(CapSids.capSidFile(home)).trim().startsWith("{"),
                "重写为 JSON 格式");
    }

    @Test
    void corruptedFileIsRebuilt() throws IOException {
        Path home = tempDir.resolve("home");
        Files.createDirectories(home);
        Files.writeString(CapSids.capSidFile(home), "{ not json !!!");
        CapSids caps = CapSids.loadOrCreate(home);
        assertTrue(CapSids.CAP_SID_PATTERN.matcher(caps.workspace).matches(), "损坏即重建");
    }

    @Test
    void equivalentCwdSpellingsShareWorkspaceSidKey() throws IOException {
        Path home = tempDir.resolve("home");
        Path workspace = Files.createDirectories(tempDir.resolve("WorkspaceRoot"));
        String canonical = CapSids.canonicalPathKey(workspace);
        String altSpelling = CapSids.canonicalPathKey(
                Path.of(workspace.toString().toUpperCase()));
        assertEquals(canonical, altSpelling, "大小写归一");

        String first = CapSids.workspaceCapSidForCwd(home, workspace);
        String second = CapSids.workspaceCapSidForCwd(home,
                Path.of(workspace.toString().toUpperCase()));
        assertEquals(first, second, "等价拼写共享同一 SID");
        assertEquals(1, CapSids.loadOrCreate(home).workspaceByCwd.size());
    }

    @Test
    void writeRootsGetPathScopedSids() throws IOException {
        Path home = tempDir.resolve("home");
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path extraRoot = Files.createDirectories(tempDir.resolve("extra-root"));

        String workspaceSid = CapSids.workspaceWriteCapSidForRoot(home, workspace, workspace);
        String extraSid = CapSids.workspaceWriteCapSidForRoot(home, workspace, extraRoot);
        assertNotEquals(workspaceSid, extraSid, "工作区与额外写根分键");
        assertEquals(extraSid, CapSids.writableRootCapSidForPath(home, extraRoot),
                "写根 SID 幂等复取");

        CapSids caps = CapSids.loadOrCreate(home);
        assertEquals(1, caps.workspaceByCwd.size());
        assertEquals(1, caps.writableRootByPath.size());
    }

    @Test
    void canonicalPathKeyNormalizesCaseAndTrailingSlashes() {
        Path base = tempDir.resolve("Repo");
        // 大小写归一（Windows 不敏感语义；Linux 测试路径同用小写归一逻辑）
        assertEquals(CapSids.canonicalPathKey(base),
                CapSids.canonicalPathKey(tempDir.resolve("repo")), "大小写归一");
        // 尾部冗余分隔符归一
        assertEquals(CapSids.canonicalPathKey(base),
                CapSids.canonicalPathKey(Path.of(base + "/")), "尾分隔符归一");
        assertTrue(!CapSids.canonicalPathKey(base).endsWith("/"), "无尾斜杠");
    }
}
