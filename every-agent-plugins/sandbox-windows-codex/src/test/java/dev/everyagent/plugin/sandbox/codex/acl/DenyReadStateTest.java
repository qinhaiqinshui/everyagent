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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DenyReadState 单测（跨平台；对齐 deny_read_state.rs：按 principal SID 键、
 * apply-then-revoke-stale 对账、原子写、其他主体分区互不干扰）。
 */
class DenyReadStateTest {

    private static final String GROUP = "S-1-5-21-1-2-3-100";
    private static final String OTHER = "S-1-5-21-1-2-3-200";

    @TempDir
    Path tmp;

    private Path stateRoot() throws IOException {
        Path root = tmp.resolve(".sandbox");
        Files.createDirectories(root);
        return root;
    }

    @Test
    void syncAppliesDesiredAndPersistsJson() throws IOException {
        Path stateRoot = stateRoot();
        Path secret = Files.writeString(tmp.resolve("secret.env"), "s");
        Path missing = tmp.resolve("missing.env");
        RecordingAclOperations ops = new RecordingAclOperations();

        List<Path> applied = DenyReadState.sync(stateRoot, GROUP,
                List.of(secret, missing), ops);

        // @TempDir 已是 canonical 拼法：secret 词法==canonical 单条 + 缺失词法单条
        assertEquals(2, applied.size());
        assertTrue(applied.contains(secret));
        assertTrue(applied.contains(missing));
        Map<String, List<String>> state = DenyReadState.load(DenyReadState.stateFile(stateRoot));
        assertEquals(1, state.size());
        List<String> persisted = state.get(GROUP);
        assertEquals(applied.size(), persisted.size());
        for (Path path : applied) {
            assertTrue(persisted.contains(path.toString()));
        }
    }

    @Test
    void syncRevokesStalePathsFromPreviousRun() throws IOException {
        Path stateRoot = stateRoot();
        Path keep = Files.writeString(tmp.resolve("keep.env"), "k");
        Path stale = Files.writeString(tmp.resolve("stale.env"), "s");
        RecordingAclOperations ops = new RecordingAclOperations();

        DenyReadState.sync(stateRoot, GROUP, List.of(keep, stale), ops);
        ops.calls.clear();
        DenyReadState.sync(stateRoot, GROUP, List.of(keep), ops);

        assertTrue(ops.calls.stream().anyMatch(
                c -> c.op().equals("revoke") && c.path().equals(stale)));
        assertTrue(ops.calls.stream().noneMatch(
                c -> c.op().equals("revoke") && c.path().equals(keep)));
        Map<String, List<String>> state = DenyReadState.load(DenyReadState.stateFile(stateRoot));
        assertFalse(state.get(GROUP).stream().map(String::toLowerCase)
                .anyMatch(p -> p.contains("stale")));
    }

    @Test
    void emptyDesiredRemovesPrincipalButKeepsOthers() throws IOException {
        Path stateRoot = stateRoot();
        Path otherSecret = Files.writeString(tmp.resolve("other.env"), "o");
        RecordingAclOperations ops = new RecordingAclOperations();
        DenyReadState.sync(stateRoot, GROUP, List.of(tmp.resolve("a.env")), ops);
        DenyReadState.sync(stateRoot, OTHER, List.of(otherSecret), ops);

        DenyReadState.sync(stateRoot, GROUP, List.of(), ops);

        Map<String, List<String>> state = DenyReadState.load(DenyReadState.stateFile(stateRoot));
        assertFalse(state.containsKey(GROUP), "新集为空则移除主体分区");
        assertTrue(state.containsKey(OTHER));
    }

    @Test
    void atomicWriteLeavesValidJsonBehind() throws IOException {
        Path stateRoot = stateRoot();
        DenyReadState.sync(stateRoot, GROUP, List.of(tmp.resolve("a.env")),
                new RecordingAclOperations());
        Path file = DenyReadState.stateFile(stateRoot);
        assertTrue(Files.exists(file));
        // 再写一轮（覆盖路径），文件仍可解析且无残留 tmp
        DenyReadState.sync(stateRoot, GROUP, List.of(tmp.resolve("b.env")),
                new RecordingAclOperations());
        try (var stream = Files.list(stateRoot)) {
            assertEquals(1, stream.count(), "无 .tmp 残留");
        }
        assertFalse(DenyReadState.load(file).isEmpty());
    }

    @Test
    void corruptStateFileFailsClosed() throws IOException {
        Path stateRoot = stateRoot();
        Files.writeString(DenyReadState.stateFile(stateRoot), "{not-json");
        assertThrows(IOException.class,
                () -> DenyReadState.sync(stateRoot, GROUP, List.of(tmp.resolve("a.env")),
                        new RecordingAclOperations()));
    }
}
