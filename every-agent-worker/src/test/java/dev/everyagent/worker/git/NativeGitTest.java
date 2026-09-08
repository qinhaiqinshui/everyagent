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
}
