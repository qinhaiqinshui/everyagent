package dev.everyagent.plugin.api.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SecretPatterns}：值形态指纹、名字形态、env 清理。
 *
 * <p>回归护栏的场景来自一次真实泄露：宿主 shell 里有个<b>名字毫无规律</b>的变量
 * {@code codex=<Anthropic key>}，沙箱内 {@code Get-ChildItem Env:} 把它整块读出并落进
 * 任务日志。因此「只看名字」的规则不够，必须有值形态指纹这条路。
 *
 * <p>文本输出掩码（redact/mask/countSecrets）已迁至 {@code secret-redaction} 插件
 * （{@code SecretRedactor}），此处不再覆盖——删插件即删全部脱敏测试。
 */
class SecretPatternsTest {

    /** 真实形态的 Anthropic key（长度 78,前缀 sk-ant-sid01-,此处为同形态假值）。 */
    private static final String KEY = "sk-ant-sid01-" + "A".repeat(58) + "6280";

    // ---- env 清理 ----

    @Test
    void scrubsValueShapedSecretRegardlessOfName() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", "C:\\Windows\\system32");
        env.put("codex", KEY);
        SecretPatterns.EnvScrub scrub = SecretPatterns.scrubEnv(env);
        assertFalse(scrub.env().containsKey("codex"));
        assertTrue(scrub.env().containsKey("PATH"), "系统变量必须保留(删了沙箱里程序起不动)");
        assertEquals(List.of("codex"), scrub.removedNames(), "审计只给名字");
        assertFalse(String.join(",", scrub.removedNames()).contains("sk-ant"),
                "被删名字列表里不得混进值");
    }

    @Test
    void scrubsNameShapedSecretWithoutFingerprint() {
        Map<String, String> env = Map.of("HUB_KEY", "ShubS2389", "MY_TOKEN", "abc12345");
        SecretPatterns.EnvScrub scrub = SecretPatterns.scrubEnv(env);
        assertTrue(scrub.env().isEmpty(), "名字属凭据词族 + 值非短占位 → 剔除: " + scrub.env());
        assertEquals(2, scrub.removedNames().size());
    }

    @Test
    void keepsShortPlaceholderValuesUnderSuspiciousNames() {
        Map<String, String> env = Map.of("GIT_TERMINAL_PROMPT", "0", "SESSIONNAME", "Console");
        SecretPatterns.EnvScrub scrub = SecretPatterns.scrubEnv(env);
        assertTrue(scrub.env().containsKey("GIT_TERMINAL_PROMPT"), "短值(0)不是凭据");
    }

    @Test
    void exemptsRuntimeCriticalNamesThatLookLikeSecrets() {
        // SSH_AUTH_SOCK 含 auth、AUTHLOGONSERVER 含 AUTH —— 都是运行必需,绝不移除
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SSH_AUTH_SOCK", "/run/user/1000/keyring/ssh-agent-socket-path");
        env.put("AUTHLOGONSERVER", "\\\\DESKTOP-6UG488F.domain.contoso.internal");
        SecretPatterns.EnvScrub scrub = SecretPatterns.scrubEnv(env);
        assertEquals(2, scrub.env().size(), "豁免表未生效会直接打断 git-over-ssh: " + scrub.env().keySet());
    }

    @Test
    void scrubInPlaceMutatesGivenMapAndReportsNames() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SYSTEMROOT", "C:\\Windows");
        env.put("AWS_SECRET_ACCESS_KEY", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
        List<String> removed = SecretPatterns.scrubInPlace(env);
        assertEquals(List.of("AWS_SECRET_ACCESS_KEY"), removed);
        assertEquals(1, env.size(), "活视图就地删除(ProcessBuilder.environment() 用法)");
        assertTrue(env.containsKey("SYSTEMROOT"));
    }

    @Test
    void scrubEnvNeverMutatesSource() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("codex", KEY);
        SecretPatterns.scrubEnv(env);
        assertTrue(env.containsKey("codex"), "入参不被修改(调用方可能传 System.getenv() 只读视图)");
    }

    @Test
    void nullAndBlankInputsAreSafe() {
        assertTrue(SecretPatterns.scrubEnv(null).env().isEmpty());
        assertTrue(SecretPatterns.scrubEnv(null).removedNames().isEmpty());
        assertTrue(SecretPatterns.scrubInPlace(null).isEmpty());
        assertFalse(SecretPatterns.isSecretBearing("MY_TOKEN", "   "), "空白值不算凭据");
    }
}
