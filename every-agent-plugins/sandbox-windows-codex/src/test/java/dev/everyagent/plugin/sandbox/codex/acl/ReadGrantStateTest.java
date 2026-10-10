package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读授权账本单测（§7.10 读回收缺口修复）。
 *
 * <p>读 ACE 只能授给沙箱组 SID（capability SID 是 restricting SID，只参与写检查），
 * 故读回收必须<b>物理撤 ACE</b>。本组测试锁死:
 * <ol>
 *   <li>账本落盘/读回（按 principal SID 分区）；</li>
 *   <li>本轮不再是读根的旧路径 → {@code revoke}（回收生效）；仍在读根集合 → 保留；</li>
 *   <li><b>保护集</b>（当前写根 / 当前 deny-read 目标）只「遗忘」不撤 ACE——
 *       撤掉写根的组 ACE 会连带丢掉读；撤掉 deny-read 的组 ACE 会连带删掉 deny（反而放宽）；</li>
 *   <li>全空目标集 → 主体条目清除（不残留账本）。</li>
 * </ol>
 */
class ReadGrantStateTest {

    private static final String GROUP = "S-1-5-21-1-1-1-1";

    @TempDir
    Path tmp;

    private Path stateRoot() {
        return tmp.resolve(".sandbox");
    }

    private static Set<String> keys(Path... paths) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (Path p : paths) {
            out.add(DenyReadPlanner.lexicalPathKey(p));
        }
        return out;
    }

    @Test
    void recordsAppliedReadRootsAndRevokesStaleOnes() throws IOException {
        Path a = Files.createDirectories(tmp.resolve("a"));
        Path b = Files.createDirectories(tmp.resolve("b"));
        RecordingAclOperations ops = new RecordingAclOperations();

        // 第一轮:读根 = {a, b}
        ReadGrantState.sync(stateRoot(), GROUP, List.of(a, b), Set.of(), ops);
        assertTrue(Files.exists(ReadGrantState.stateFile(stateRoot())), "账本落盘");
        assertEquals(List.of(a.toString(), b.toString()),
                DenyReadState.load(ReadGrantState.stateFile(stateRoot())).get(GROUP));
        assertTrue(ops.calls.stream().noneMatch(c -> c.op().equals("revoke")), "首轮无回收");

        // 第二轮:读根 = {a} → b 陈旧必须被物理撤销
        ReadGrantState.sync(stateRoot(), GROUP, List.of(a), Set.of(), ops);
        List<Path> revoked = ops.calls.stream()
                .filter(c -> c.op().equals("revoke")).map(RecordingAclOperations.Call::path).toList();
        assertEquals(List.of(b), revoked, "陈旧读根 b 必须被 revokeAce");
        assertEquals(List.of(a.toString()),
                DenyReadState.load(ReadGrantState.stateFile(stateRoot())).get(GROUP));
    }

    @Test
    void stillGrantedRootsAreNotRevoked() throws IOException {
        Path a = Files.createDirectories(tmp.resolve("a"));
        RecordingAclOperations ops = new RecordingAclOperations();

        ReadGrantState.sync(stateRoot(), GROUP, List.of(a), Set.of(), ops);
        ReadGrantState.sync(stateRoot(), GROUP, List.of(a), Set.of(), ops);

        assertTrue(ops.calls.stream().noneMatch(c -> c.op().equals("revoke")),
                "仍在读根集合中 → 不得回收");
        assertEquals(1, DenyReadState.load(ReadGrantState.stateFile(stateRoot())).get(GROUP).size());
    }

    @Test
    void protectedPathsAreForgottenWithoutRevoking() throws IOException {
        Path writeRoot = Files.createDirectories(tmp.resolve("ws"));
        Path denyTarget = Files.createDirectories(tmp.resolve("secret"));
        RecordingAclOperations ops = new RecordingAclOperations();

        // 曾按读根施加过
        ReadGrantState.sync(stateRoot(), GROUP, List.of(writeRoot, denyTarget), Set.of(), ops);
        ops.calls.clear();

        // 本轮都不再是读根,但成为写根 / deny-read 目标 → 只遗忘不撤
        ReadGrantState.sync(stateRoot(), GROUP, List.of(),
                keys(writeRoot, denyTarget), ops);

        assertTrue(ops.calls.stream().noneMatch(c -> c.op().equals("revoke")),
                "受保护路径不得撤 ACE: " + ops.calls);
        assertFalse(DenyReadState.load(ReadGrantState.stateFile(stateRoot())).containsKey(GROUP),
                "保护路径应从账本遗忘(不再视为我们的读授权)");
    }

    @Test
    void emptyDesiredSetClearsLedgerAndRevokesAll() throws IOException {
        Path a = Files.createDirectories(tmp.resolve("a"));
        RecordingAclOperations ops = new RecordingAclOperations();
        ReadGrantState.sync(stateRoot(), GROUP, List.of(a), Set.of(), ops);
        ops.calls.clear();

        ReadGrantState.sync(stateRoot(), GROUP, List.of(), Set.of(), ops);

        assertEquals(List.of(a), ops.calls.stream()
                .filter(c -> c.op().equals("revoke")).map(RecordingAclOperations.Call::path).toList());
        assertFalse(DenyReadState.load(ReadGrantState.stateFile(stateRoot())).containsKey(GROUP),
                "空目标集不残留主体条目");
    }

    @Test
    void revokeFailureDoesNotAbortLedgerUpdate() throws IOException {
        Path a = Files.createDirectories(tmp.resolve("a"));
        RecordingAclOperations ops = new RecordingAclOperations();
        ReadGrantState.sync(stateRoot(), GROUP, List.of(a), Set.of(), ops);
        ops.failOn(a, "revoke");

        ReadGrantState.sync(stateRoot(), GROUP, List.of(), Set.of(), ops); // 不应抛出

        assertFalse(DenyReadState.load(ReadGrantState.stateFile(stateRoot())).containsKey(GROUP),
                "撤销失败不阻断账本落盘(下轮 preflight 自然重试)");
    }
}