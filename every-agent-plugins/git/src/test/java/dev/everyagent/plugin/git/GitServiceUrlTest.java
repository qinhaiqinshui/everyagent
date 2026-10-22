package dev.everyagent.plugin.git;

import dev.everyagent.plugin.api.exception.BadParamsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 远端 URL 防注入校验测试(安全整改 L-2/L-4):git.clone / git.remote.add argv 直传前
 * 必须经 {@link GitService#requireSafeRemoteUrl(String, String)}——
 * "-" 开头会被 git 解释为选项(--upload-pack 注入),ext::/fd:: 会被解释为特殊传输命令。
 * 纯逻辑单测,不触碰真实 git / 网络与工作区。
 */
class GitServiceUrlTest {

    // ---- 注入形态:全部拒绝 ----

    @Test
    void rejectsUploadPackOptionInjection() {
        assertThrows(BadParamsException.class,
                () -> GitService.requireSafeRemoteUrl("--upload-pack=evil", "克隆"));
    }

    @Test
    void rejectsExtTransport() {
        assertThrows(BadParamsException.class,
                () -> GitService.requireSafeRemoteUrl("ext::sh -c x", "克隆"));
    }

    @Test
    void rejectsExtTransportCaseInsensitive() {
        // 传输前缀大小写不敏感,变体同样拒绝
        assertThrows(BadParamsException.class,
                () -> GitService.requireSafeRemoteUrl("EXT::sh -c x", "克隆"));
        assertThrows(BadParamsException.class,
                () -> GitService.requireSafeRemoteUrl("Ext::sh -c x", "克隆"));
    }

    @Test
    void rejectsFdTransport() {
        assertThrows(BadParamsException.class,
                () -> GitService.requireSafeRemoteUrl("fd::17/foo", "克隆"));
        assertThrows(BadParamsException.class,
                () -> GitService.requireSafeRemoteUrl("FD::", "关联远程"));
    }

    @Test
    void rejectsAnyDashPrefixedUrl() {
        assertThrows(BadParamsException.class,
                () -> GitService.requireSafeRemoteUrl("-anything", "克隆"));
        assertThrows(BadParamsException.class,
                () -> GitService.requireSafeRemoteUrl("--upload-pack", "关联远程"));
    }

    @Test
    void rejectsNullAndBlank() {
        assertThrows(BadParamsException.class,
                () -> GitService.requireSafeRemoteUrl(null, "克隆"));
        assertThrows(BadParamsException.class,
                () -> GitService.requireSafeRemoteUrl("   ", "克隆"));
        assertThrows(BadParamsException.class,
                () -> GitService.requireSafeRemoteUrl("", "关联远程"));
    }

    // ---- 常规 URL:放行且返回 trim 后的值 ----

    @Test
    void acceptsHttpsUrl() {
        assertEquals("https://github.com/x/y.git",
                GitService.requireSafeRemoteUrl("https://github.com/x/y.git", "克隆"));
    }

    @Test
    void acceptsScpLikeSshUrl() {
        assertEquals("git@github.com:x/y.git",
                GitService.requireSafeRemoteUrl("git@github.com:x/y.git", "克隆"));
    }

    @Test
    void acceptsHttpSshGitFileUrls() {
        assertEquals("http://example.com/a.git",
                GitService.requireSafeRemoteUrl("http://example.com/a.git", "克隆"));
        assertEquals("ssh://git@example.com/x/y.git",
                GitService.requireSafeRemoteUrl("ssh://git@example.com/x/y.git", "克隆"));
        assertEquals("git://example.com/x/y.git",
                GitService.requireSafeRemoteUrl("git://example.com/x/y.git", "克隆"));
        assertEquals("file:///srv/git/repo.git",
                GitService.requireSafeRemoteUrl("file:///srv/git/repo.git", "克隆"));
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertEquals("https://github.com/x/y.git",
                GitService.requireSafeRemoteUrl("  https://github.com/x/y.git  ", "克隆"));
    }
}
