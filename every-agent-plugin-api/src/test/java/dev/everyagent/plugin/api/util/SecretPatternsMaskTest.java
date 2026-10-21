package dev.everyagent.plugin.api.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SecretPatterns#maskSecrets(String)}:日志出口凭据形态掩码(架构 §7.17 ③,审计 L-3)。
 *
 * <p>覆盖:每条值形态 Rule 一个用例(整段命中保留格式前缀 / 无分隔符整段掩)、带前缀写法
 * (Bearer/k=v)只掩本体、正常文本零误杀、幂等(掩码占位符不产生二次命中)。
 * 所有密钥样本均为同形态假值。内容脱敏(redact/mask)属 secret-redaction 插件,不在此覆盖。
 */
class SecretPatternsMaskTest {

    /** 同形态假 Anthropic key(真实泄露事故形态,与 SecretPatternsTest 同款)。 */
    private static final String ANTHROPIC = "sk-ant-sid01-" + "A".repeat(58) + "6280";
    private static final String OPENAI = "sk-" + "B".repeat(32);
    private static final String GHP = "ghp_" + "c1d2e3f4a5".repeat(4);
    private static final String GH_PAT = "github_pat_" + "d1e2f3g4h5".repeat(4);
    private static final String AWS = "AKIA1234567890ABCDEF";
    private static final String GOOGLE = "AIza0123456789abcdef0123456789abcdef";
    private static final String SLACK = "xoxb-1234567890abcdef";
    private static final String STRIPE = "sk_live_abcdef0123456789";
    private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0"
            + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6y";
    private static final String BEARER_TOKEN = "abcdef0123456789ABCD";

    // ---- 整段命中:保留「格式前缀 + 分隔符」的可读形态 ----

    @Test
    void masksAnthropicKeyToSkPrefix() {
        String out = SecretPatterns.maskSecrets("export KEY=" + ANTHROPIC + " # deploy");
        assertFalse(out.contains(ANTHROPIC), "密钥原文不得残留: " + out);
        assertFalse(out.contains("ant-sid01"), "格式中段也是密钥本体: " + out);
        assertTrue(out.contains("sk-****"), "保留 sk- 格式前缀: " + out);
    }

    @Test
    void masksGenericSkPrefix() {
        String out = SecretPatterns.maskSecrets("env|curl-key|" + OPENAI);
        assertTrue(out.contains("sk-****"), out);
        assertFalse(out.contains(OPENAI), out);
    }

    @Test
    void masksGitHubTokens() {
        assertEquals("git remote set-url ghp_**** x", SecretPatterns.maskSecrets(
                "git remote set-url " + GHP + " x"));
        assertTrue(SecretPatterns.maskSecrets("echo " + GH_PAT).contains("github_****"),
                "github_pat_ 保留 github_ 前缀");
        assertFalse(SecretPatterns.maskSecrets("echo " + GH_PAT).contains("d1e2f3g4h5"),
                "PAT 本体不得残留");
    }

    @Test
    void masksSlackAndStripeShapes() {
        assertTrue(SecretPatterns.maskSecrets("slack " + SLACK).contains("xoxb-****"));
        assertTrue(SecretPatterns.maskSecrets("stripe " + STRIPE).contains("sk_****"));
        assertFalse(SecretPatterns.maskSecrets("stripe " + STRIPE).contains("abcdef0123456789"));
    }

    // ---- 整段命中:无分隔符(格式前缀不可辨)→ 整段 ****,一个字符都不留 ----

    @Test
    void masksAwsAndGoogleKeysEntirely() {
        assertEquals("aws **** google ****", SecretPatterns.maskSecrets(
                "aws " + AWS + " google " + GOOGLE));
    }

    @Test
    void masksJwtAndPemHeaderEntirely() {
        String out = SecretPatterns.maskSecrets("jwt " + JWT + " pem -----BEGIN RSA PRIVATE KEY-----");
        assertFalse(out.contains("eyJhbGci"), "JWT 头段也不保留(整段命中): " + out);
        assertFalse(out.contains("BEGIN RSA"), "PEM 标头打掉: " + out);
        assertTrue(out.contains("****"), out);
    }

    // ---- 带前缀写法(secretGroup>0):前缀保留、本体掩为 **** ----

    @Test
    void masksBearerAndBasicTokenBodiesOnly() {
        assertEquals("Authorization: Bearer ****", SecretPatterns.maskSecrets(
                "Authorization: Bearer " + BEARER_TOKEN));
        assertEquals("Proxy-Authorization: Basic ****", SecretPatterns.maskSecrets(
                "Proxy-Authorization: Basic " + BEARER_TOKEN));
    }

    @Test
    void realWorldCurlCommandKeepsStructureButMasksBody() {
        String cmd = "curl -sS -H \"Authorization: Bearer " + BEARER_TOKEN
                + "\" https://api.example.com/v1/models";
        assertEquals("curl -sS -H \"Authorization: Bearer ****\" https://api.example.com/v1/models",
                SecretPatterns.maskSecrets(cmd), "命令结构原样,仅密钥本体打码");
    }

    @Test
    void bearerWithSkKeyKeepsSkShape() {
        // 规则顺序:sk-ant 先掩,Bearer 规则后到时本体已短于 16 → 不再命中(幂等的根源之一)
        String out = SecretPatterns.maskSecrets("curl -H \"Authorization: Bearer " + ANTHROPIC + "\"");
        assertEquals("curl -H \"Authorization: Bearer sk-****\"", out);
    }

    @Test
    void masksKeyValueFormsKeepingName() {
        // 独立形态命中(\b 词边界);前缀拼接名(如 OPENAI_API_KEY=xxx)按既有 Rule 的
        // 零误杀设计不在此列——其值若具密钥形状(sk- 等)仍会被前序规则掩掉。
        assertEquals("api_key=****", SecretPatterns.maskSecrets(
                "api_key=" + BEARER_TOKEN));
        assertEquals("password: ****", SecretPatterns.maskSecrets(
                "password: ShubS2389abcdef12"));
        assertTrue(SecretPatterns.maskSecrets("--token=XYZ987654321abc").contains("token=****"));
    }

    // ---- 零误杀:正常文本原样 ----

    @Test
    void leavesOrdinaryTextUntouched() {
        assertEquals("git status && mvn -q -pl every-agent-worker -am test",
                SecretPatterns.maskSecrets("git status && mvn -q -pl every-agent-worker -am test"));
        assertEquals("Bearer short token", SecretPatterns.maskSecrets("Bearer short token"),
                "Bearer 后短串(<16)不是密钥");
        assertEquals("token=abc123", SecretPatterns.maskSecrets("token=abc123"),
                "k=v 值 <8 不是密钥");
        assertEquals("https://api.openai.com/v1/chat/completions",
                SecretPatterns.maskSecrets("https://api.openai.com/v1/chat/completions"));
    }

    // ---- 幂等:掩码输出二次掩码不变 ----

    @Test
    void maskingIsIdempotent() {
        String mixed = "deploy --api_key=" + BEARER_TOKEN + " --git " + GHP
                + " --aws " + AWS + " --sk " + ANTHROPIC;
        String once = SecretPatterns.maskSecrets(mixed);
        assertEquals(once, SecretPatterns.maskSecrets(once), "二次掩码必须无新增命中");
        assertFalse(once.contains(BEARER_TOKEN) || once.contains(GHP)
                || once.contains(AWS) || once.contains(ANTHROPIC), once);
        assertEquals("sk-****", SecretPatterns.maskSecrets("sk-****"),
                "已掩码文本再过一遍保持稳定");
        assertEquals("Bearer ****", SecretPatterns.maskSecrets("Bearer ****"));
    }

    // ---- 空值安全 ----

    @Test
    void nullAndEmptyPassThrough() {
        assertNull(SecretPatterns.maskSecrets(null));
        assertEquals("", SecretPatterns.maskSecrets(""));
    }
}
