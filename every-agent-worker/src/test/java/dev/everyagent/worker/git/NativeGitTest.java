package dev.everyagent.worker.git;

import dev.everyagent.worker.git.NativeGit.NativeResult;
import dev.everyagent.worker.git.NativeGit.StatusData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NativeGit 纯逻辑单元测试(不依赖 Spring 上下文;真实 git 行为由集成测试覆盖)。
 * 解析逻辑与原生 git 输出对齐(docs/GIT_NATIVE_MIGRATION.md §5):porcelain=v1 -z、
 * log -z --format、凭证失败关键字识别。
 */
class NativeGitTest {

    @Test
    void parseStatusAllCategories() {
        // 实测输出形态(git status --porcelain=v1 -z --untracked-files=all)
        StatusData s = NativeGit.parseStatus(
                " M a.txt\0 D d/b.txt\0?? untracked.txt\0A  added.txt\0M  staged.txt\0D  removed.txt\0");
        assertEquals(List.of("added.txt"), s.added());
        assertEquals(List.of("staged.txt"), s.changed());
        assertEquals(List.of("a.txt"), s.modified());
        assertEquals(List.of("removed.txt"), s.removed());
        assertEquals(List.of("d/b.txt"), s.missing());
        assertEquals(List.of("untracked.txt"), s.untracked());
        assertTrue(s.conflicting().isEmpty());
        assertFalse(s.clean());
    }

    @Test
    void parseStatusUnmerged() {
        StatusData s = NativeGit.parseStatus("UU f.txt\0AA a.txt\0DD d.txt\0");
        assertEquals(List.of("f.txt", "a.txt", "d.txt"), s.conflicting());
        assertTrue(s.added().isEmpty(), "unmerged 不得误入 added");
    }

    @Test
    void parseStatusRenameOldPathIgnored() {
        // rename: "R  new\0old\0" — old path 是独立 NUL 段,应被忽略
        StatusData s = NativeGit.parseStatus("R  new.txt\0old.txt\0");
        assertEquals(List.of("new.txt"), s.added());
        assertEquals(0, s.modified().size(), "old path 不应出现在任何字段");
    }

    @Test
    void parseStatusEmpty() {
        StatusData s = NativeGit.parseStatus("");
        assertTrue(s.clean());
        assertTrue(s.added().isEmpty());
        assertTrue(s.untracked().isEmpty());
    }

    @Test
    void trackedChangedExcludesAddedAndUntracked() {
        StatusData s = NativeGit.parseStatus(" M a.txt\0 D b.txt\0?? u.txt\0A  added.txt\0");
        var tc = s.trackedChanged();
        assertTrue(tc.contains("a.txt"));
        assertTrue(tc.contains("b.txt"));
        assertFalse(tc.contains("u.txt"), "untracked 不参与 discard 判定");
        assertFalse(tc.contains("added.txt"), "已暂存新增不参与 discard 判定(对齐 VS Code)");
    }

    @Test
    void authFailureMarkers() {
        assertTrue(NativeGit.isAuthFailure(new NativeResult("", "remote: Authentication failed", 128)));
        assertTrue(NativeGit.isAuthFailure(new NativeResult("", "fatal: could not read Username for 'https://github.com': terminal prompts disabled", 128)));
        assertTrue(NativeGit.isAuthFailure(new NativeResult("", "fatal: could not read Password", 128)));
        assertTrue(NativeGit.isAuthFailure(new NativeResult("", "HTTP 401 Unauthorized", 128)));
        // exit 0 恒非认证失败
        assertFalse(NativeGit.isAuthFailure(new NativeResult("ok", "", 0)));
        // 非凭证错误(如远端 404)不误判
        assertFalse(NativeGit.isAuthFailure(new NativeResult("", "remote: Repository not found.", 128)));
        assertFalse(NativeGit.isAuthFailure(new NativeResult("", "fatal: unable to access 'https://x': Could not resolve host", 128)));
    }

    @Test
    void notRepoDetection() {
        assertTrue(NativeGit.isNotRepo(new NativeResult("", "fatal: not a git repository (or any of the parent directories): .git", 128)));
        assertFalse(NativeGit.isNotRepo(new NativeResult("", "fatal: No such remote 'origin'", 2)));
    }

    @Test
    void parsePushUpdatesIncrementalUpdateKeepsLeadingSpaceFlag() {
        // 实测输出(git 2.x,增量更新):更新行以空格 flag 开头,trim 会吞掉首字段导致条目被丢弃
        String out = "To /repo/bare\n"
                + " \trefs/heads/master:refs/heads/master\ta5a8cb8..53b2587\n"
                + "Done\n";
        var updates = NativeGit.parsePushUpdates(out);
        assertEquals(1, updates.size());
        assertEquals("refs/heads/master", updates.get(0).ref());
        assertEquals("OK", updates.get(0).status());
        assertEquals(" ", updates.get(0).flag());
    }

    @Test
    void parsePushUpdatesAllFlags() {
        // 实测形态:新建引用 '*';up-to-date '='、拒绝 '!'、强推 '+' 同构
        String out = "To https://github.com/u/r.git\n"
                + "*\trefs/heads/dev:refs/heads/dev\t[new branch]\n"
                + "=\trefs/heads/main:refs/heads/main\t[up to date]\n"
                + "!\trefs/heads/x:refs/heads/x\t[rejected] (non-fast-forward)\n"
                + "+\trefs/heads/y:refs/heads/y\tforced update\n"
                + "-\trefs/heads/z\t[deleted]\n"
                + "Done\n";
        var updates = NativeGit.parsePushUpdates(out);
        assertEquals(5, updates.size());
        assertEquals("OK", updates.get(0).status());
        assertEquals("UP_TO_DATE", updates.get(1).status());
        assertEquals("REJECTED", updates.get(2).status());
        assertEquals("FORCED", updates.get(3).status());
        assertEquals("OK", updates.get(4).status()); // 删除 '-' 归 OK(现有状态映射无 DELETE)
        assertEquals("refs/heads/z", updates.get(4).ref()); // 删除行 refspec 无 from: 段
    }

    @Test
    void parsePushUpdatesCrlfAndEdgeLines() {
        // CRLF 行尾(Windows git)+ 信封行/空行/非法行防御
        String out = "To /repo/bare\r\n"
                + " \trefs/heads/master:refs/heads/master\ta..b\r\n"
                + "\r\n"
                + "garbage\r\n"
                + "Done\r\n";
        var updates = NativeGit.parsePushUpdates(out);
        assertEquals(1, updates.size());
        assertEquals("refs/heads/master", updates.get(0).ref());
        assertTrue(NativeGit.parsePushUpdates("").isEmpty());
        assertTrue(NativeGit.parsePushUpdates(null).isEmpty());
    }
}
