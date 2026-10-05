package dev.everyagent.plugin.secretredaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SecretRedactor}：值形态指纹识别与文本掩码幂等性。
 *
 * <p>这些测试完全住在 {@code secret-redaction} 插件内——删除该插件即删除全部脱敏测试，
 * 核心（plugin-api / worker）不持有任何脱敏逻辑与测试。
 */
class SecretRedactorTest {

    /** 真实形态的 Anthropic key（长度 78,前缀 sk-ant-sid01-,此处为同形态假值）。 */
    private static final String KEY = "sk-ant-sid01-" + "A".repeat(58) + "6280";

    // ---- 值形态识别 ----

    @Test
    void detectsAnthropicKey() {
        assertTrue(SecretRedactor.hasSecret(KEY));
    }

    @Test
    void detectsOtherProviderFingerprints() {
        assertTrue(SecretRedactor.hasSecret("ghp_" + "x".repeat(36)));
        assertTrue(SecretRedactor.hasSecret("github_pat_" + "x".repeat(40)));
        assertTrue(SecretRedactor.hasSecret("AKIA" + "ABCDEFGHIJKL2345".substring(0, 16)));
        assertTrue(SecretRedactor.hasSecret("AIza" + "y".repeat(35)));
        assertTrue(SecretRedactor.hasSecret("xoxb-" + "z".repeat(30)));
        assertTrue(SecretRedactor.hasSecret("-----BEGIN RSA PRIVATE KEY-----"));
        assertTrue(SecretRedactor.hasSecret("sk-" + "w".repeat(40)));
    }

    @Test
    void detectsBearerAndKeyValueForms() {
        assertTrue(SecretRedactor.hasSecret("Authorization: Bearer " + KEY));
        assertTrue(SecretRedactor.hasSecret("password=S3cretValue"));
    }

    @Test
    void ignoresOrdinaryText() {
        assertFalse(SecretRedactor.hasSecret("[System.IO.Directory]::GetCurrentDirectory()"));
        assertFalse(SecretRedactor.hasSecret("cwd=C:\\Users\\haigui\\.yu\\s2384\\eagent"));
        assertFalse(SecretRedactor.hasSecret("LangMode=ConstrainedLanguage PSVer=5.1 TempIL=True"));
        assertFalse(SecretRedactor.hasSecret("任务完成,提交信息 feat: 新增脱敏"));
    }

    // ---- 掩码 ----

    @Test
    void maskKeepsFingerprintButNotTheSecret() {
        String redacted = SecretRedactor.redact(KEY);
        assertFalse(redacted.contains(KEY), "掩码后原文必须不可见");
        assertTrue(redacted.contains("[len=" + KEY.length() + "]"),
                "保留长度指纹便于辨认是哪把: " + redacted);
        assertTrue(redacted.startsWith("sk-ant-sid"), "保留头部形态: " + redacted);
        assertTrue(redacted.contains("6280"), "保留尾 4 位: " + redacted);
    }

    @Test
    void redactMasksOnlyTokenPartOfBearer() {
        String out = SecretRedactor.redact("Authorization: Bearer " + KEY);
        assertTrue(out.startsWith("Authorization: Bearer sk-ant-sid"),
                "Bearer 前缀保留、token 掩掉: " + out);
        assertFalse(out.contains(KEY));
    }

    @Test
    void redactIsIdempotentAndReturnsSameInstanceWhenNoHit() {
        String once = SecretRedactor.redact(KEY);
        assertEquals(once, SecretRedactor.redact(once), "二次调用必须不变(否则历史回放会被反复改写)");
        String plain = "普通输出文本";
        assertSame(plain, SecretRedactor.redact(plain), "无命中返回同一实例,便于调用方判等");
    }

    // ---- null/blank 安全 ----

    @Test
    void nullAndBlankInputsAreSafe() {
        assertFalse(SecretRedactor.hasSecret(null));
        assertFalse(SecretRedactor.hasSecret(""));
        assertNull(SecretRedactor.redact(null));
        assertEquals("[REDACTED]", SecretRedactor.mask(null));
        assertEquals("[REDACTED:len=6]", SecretRedactor.mask("abc123"),
                "过短的凭据不保留任何片段");
    }
}
