package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DenyReadPlanner 单测（跨平台；对齐 deny_read_acl.rs 测试面：词法+canonical 双路径、
 * 缺失路径保留、根拒绝、物化与失败回滚）。
 */
class DenyReadPlannerTest {

    @TempDir
    Path tmp;

    @Test
    void planPreservesMissingPaths() throws IOException {
        Path missing = tmp.resolve("future-secret.env");
        assertEquals(List.of(missing), DenyReadPlanner.plan(List.of(missing)));
    }

    @Test
    void planIncludesExistingCanonicalTarget() throws IOException {
        Path existing = Files.writeString(tmp.resolve("secret.env"), "secret");
        Files.createDirectories(tmp.resolve("dot")); // .. 变体的中间元素需存在（toRealPath 逐段解析）
        Path lexicalVariant = tmp.resolve("dot").resolve("..").resolve("secret.env");

        Set<Path> planned = new HashSet<>(DenyReadPlanner.plan(List.of(lexicalVariant)));

        assertEquals(Set.of(lexicalVariant, existing), planned,
                "词法 + canonical 双路径都要保留（lexical 键不同）");
    }

    @Test
    void planDedupsByLexicalKey() throws IOException {
        Path existing = Files.writeString(tmp.resolve("secret.env"), "secret");
        // 已是 canonical 拼法的路径：词法键 == canonical 键 → 只保留一条
        List<Path> planned = DenyReadPlanner.plan(List.of(existing, existing));
        assertEquals(List.of(existing), planned, "重复输入且词法键相同 → 单条");
    }

    @Test
    void planRejectsFileSystemRoot() {
        Path fsRoot = tmp;
        while (fsRoot.getParent() != null) {
            fsRoot = fsRoot.getParent();
        }
        final Path root = fsRoot;
        IOException error = assertThrows(IOException.class,
                () -> DenyReadPlanner.plan(List.of(root)));
        assertTrue(error.getMessage().contains("refusing to apply a deny-read ACE to filesystem "
                + "root"), error.getMessage());
    }

    @Test
    void applyMaterializesMissingPathAsDirectory() throws IOException {
        Path missing = tmp.resolve("nested").resolve("future-secret.env");
        RecordingAclOperations ops = new RecordingAclOperations();

        List<Path> applied = DenyReadPlanner.apply(List.of(missing), "S-1-5-21-9-9-9-9", ops);

        assertTrue(Files.isDirectory(missing), "缺失路径须物化为目录（TOCTOU 防御）");
        assertEquals(List.of(missing), applied);
        assertEquals(1, ops.calls.size());
        assertEquals("denyRead", ops.calls.get(0).op());
    }

    @Test
    void applyRollsBackAddedAcesOnFailure() throws IOException {
        Path first = Files.writeString(tmp.resolve("a.env"), "a");
        Path second = tmp.resolve("b.env");
        Path third = tmp.resolve("c.env");
        RecordingAclOperations ops = new RecordingAclOperations();
        ops.failOn(second, "denyRead");

        IOException error = assertThrows(IOException.class,
                () -> DenyReadPlanner.apply(List.of(first, second, third),
                        "S-1-5-21-9-9-9-9", ops));
        assertTrue(error.getMessage().contains("injected"), error.getMessage());

        // 回滚仅针对本次新增（first 的词法+canonical 两条），尚未触达的 third 不产生任何调用
        List<String> opsOrder = ops.calls.stream().map(RecordingAclOperations.Call::op).toList();
        assertEquals(List.of("denyRead", "denyRead", "revoke"), opsOrder,
                "a.env 成功 + b.env 失败 + 回滚 a.env；third 不触达");
        assertEquals(first, ops.calls.get(2).path());
    }

    @Test
    void applySkipsExistingAceIdempotently() throws IOException {
        Path existing = Files.writeString(tmp.resolve("a.env"), "a");
        RecordingAclOperations ops = new RecordingAclOperations();
        ops.denyReadAdded.put(DenyReadPlanner.lexicalPathKey(existing), true);

        List<Path> applied = DenyReadPlanner.apply(List.of(existing), "S-1-5-21-9-9-9-9", ops);

        assertEquals(List.of(existing), applied, "幂等路径仍计入 applied 集合");
        assertEquals(1, ops.calls.size(), "ACE 已存在：调用发生但无新增");
    }
}
