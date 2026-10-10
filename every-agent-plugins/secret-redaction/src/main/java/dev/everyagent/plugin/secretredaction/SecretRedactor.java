package dev.everyagent.plugin.secretredaction;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 凭据文本掩码引擎（架构 §7.17「真实 key 只写用户覆盖文件、不进事件日志」
 * 的输出侧执行落点）。
 *
 * <p>本类<b>完全住在 {@code secret-redaction} 插件内</b>——删除该插件即删除全部
 * 文本脱敏概念与功能，核心（worker / plugin-api）不持有任何脱敏逻辑，也不依赖本类。
 *
 * <p>值形态指纹规则与 {@code SecretPatterns}（plugin-api）中供 env 清理使用的
 * 值形态检测<b>独立维护</b>：两份规则当前口径一致（都覆盖 Anthropic / OpenAI /
 * GitHub / AWS / Google / Slack / Stripe / JWT / PEM / Bearer / key=value 形态），
 * 但各自演化——env 侧是进程边界安全，输出侧是文本掩码，删除插件不应影响 env 清理。
 *
 * <p>掩码策略：把凭据本体替换成 {@code sk-ant-sid…6280[len=78]} 形态——
 * 保留可辨识的头尾指纹与总长度（用户能确认「是哪把被遮了」），但不泄露可用信息。
 * 幂等：掩码结果不再命中任何规则（{@link #redact} 可安全重复调用）。
 */
public final class SecretRedactor {

    private SecretRedactor() {
    }

    /**
     * 值形态规则：{@code secretGroup} 指明「哪一段才是密钥本体」——带前缀的写法
     * （{@code Bearer xxx} / {@code password=xxx}）只掩本体，保留可读前缀，
     * 便于人判断「这条日志里哪个位置曾有凭据」；0 表示整段命中即整段是密钥。
     */
    private record Rule(Pattern pattern, int secretGroup) {
    }

    /** 值形态指纹集（顺序无关，逐条独立替换）。 */
    private static final List<Rule> RULES = List.of(
            // Anthropic
            new Rule(Pattern.compile("sk-ant-[A-Za-z0-9_\\-]{16,}"), 0),
            // OpenAI / 通用 sk- 前缀
            new Rule(Pattern.compile("(?<![A-Za-z0-9_\\-])sk-[A-Za-z0-9_\\-]{16,}"), 0),
            // GitHub
            new Rule(Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"), 0),
            new Rule(Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"), 0),
            // AWS
            new Rule(Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"), 0),
            // Google API key
            new Rule(Pattern.compile("\\bAIza[0-9A-Za-z_\\-]{30,}\\b"), 0),
            // Slack
            new Rule(Pattern.compile("\\bxox[abprs]-[A-Za-z0-9_\\-]{10,}\\b"), 0),
            // Stripe
            new Rule(Pattern.compile("\\b(?:sk|rk|pk)_live_[A-Za-z0-9]{16,}\\b"), 0),
            // JWT(三段;只认 header/payload 均为 eyJ 的形态,避开普通 base64)
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_\\-]{8,}\\.eyJ[A-Za-z0-9_\\-]{8,}"
                    + "\\.[A-Za-z0-9_\\-]{8,}\\b"), 0),
            // PEM 私钥体标记(正文无法靠形状识别,先把标头本身打掉,提示去查完整私钥)
            new Rule(Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY( BLOCK)?-----"), 0),
            // Authorization: Bearer / Basic <token>
            new Rule(Pattern.compile("(?i)\\b(bearer|basic)\\s+([A-Za-z0-9._~+/=\\-]{16,})"), 2),
            // 配置/命令行里的 key=value 形态(k=v 值段随后被掩)
            new Rule(Pattern.compile("(?i)\\b(api[_-]?key|secret|token|password|passwd|access[_-]?key)"
                    + "\\b\\s*[=:]\\s*([^\\s\"'&,;]{8,})"), 2));

    /** 文本中是否存在凭据形态（输出脱敏的前置判定）。 */
    public static boolean hasSecret(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 掩码后的文本：把所有凭据本体替换成 {@code sk-ant-sid…6280[len=78]} 形态——
     * 保留可辨识的头尾指纹与总长度（用户能确认「是哪把被遮了」），但不泄露可用信息。
     * 幂等：掩码结果不再命中任何规则（{@link #redact} 可安全重复调用）。
     *
     * @return 无命中的输入原样返回（同一实例，便于调用方判等）
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty() || !hasSecret(text)) {
            return text;
        }
        String current = text;
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(current);
            if (!m.find()) {
                continue;
            }
            StringBuilder sb = new StringBuilder(current.length());
            m.reset();
            while (m.find()) {
                int g = rule.secretGroup();
                String secret = m.group(g);
                m.appendReplacement(sb, Matcher.quoteReplacement(
                        m.group(0).substring(0, g == 0 ? 0 : m.start(g) - m.start(0))
                                + mask(secret)));
            }
            m.appendTail(sb);
            current = sb.toString();
        }
        return current;
    }

    /** 统计文本内凭据形态的命中次数（审计用，不返回内容）。 */
    public static int countSecrets(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(text);
            while (m.find()) {
                n++;
            }
        }
        return n;
    }

    /** 单个凭据的掩码形式：前 10 + … + 后 4 + 长度。 */
    public static String mask(String secret) {
        if (secret == null) {
            return "[REDACTED]";
        }
        int n = secret.length();
        if (n <= 12) {
            return "[REDACTED:len=" + n + "]";
        }
        int head = Math.min(10, n / 3);
        int tail = Math.min(4, n / 3);
        return secret.substring(0, head) + "…" + secret.substring(n - tail) + "[len=" + n + "]";
    }
}
