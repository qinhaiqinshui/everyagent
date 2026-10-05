package dev.everyagent.plugin.api.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 环境变量凭据清理规则源（架构 §7.10 环境侧信道闸门 / §7.17 凭据纪律的环境维度）。
 *
 * <p>为什么需要它：沙箱隔离了文件系统与网络，却把宿主进程的<b>环境变量整块继承</b>给了
 * 子进程——任何跑在沙箱里的命令（{@code Get-ChildItem Env:}、{@code env}、{@code cat /proc/self/environ}）
 * 都能直接读到宿主 shell 里散落的 API key，再原样写进工具输出、事件日志与模型上下文。
 * 环境变量因此是一条真实的凭据侧信道，与「工作区外路径」同级别，必须有一道统一闸门。
 *
 * <p>本类<b>只负责环境变量清理</b>（进程边界安全），不包含任何文本输出掩码/脱敏逻辑——
 * 后者已全部住在 {@code secret-redaction} 插件内（{@code SecretRedactor}），删除该插件即删除
 * 全部脱敏概念与功能，核心不持有脱敏逻辑也不依赖该插件。
 *
 * <p>两类规则，各自适用面严格分开（误杀代价与信息泄露代价的权衡）：
 * <ol>
 *   <li><b>值形态指纹</b>（内部 {@code hasSecretPattern}）：只看值的形状，不看变量名。
 *       用于 env 清理的主判据——零误杀（普通环境变量值不会长成
 *       {@code sk-ant-…}），且变量名无规律时（如本例的 {@code codex}）照样拦得住。</li>
 *   <li><b>名字形态</b>（{@link #isSecretName}）：兜住「纯随机 hex/base64
 *       值 + 明显是凭据的名字」（如 {@code HUB_KEY=ShubS2389}）这类无指纹可认的情况。</li>
 * </ol>
 *
 * <p>永不修改入参 map、永不返回被删变量的值（审计只给名字），调用方据此打日志是安全的。
 *
 * <p><b>消费面</b>：{@link #scrubEnv} / {@link #scrubInPlace} / {@link #isSecretBearing} /
 * {@link #isSecretName} 由<b>常驻链路</b>调用：sandbox-windows-codex（{@code CodexCommandExecutor.childEnv}、
 * {@code RunnerClient} 两处）、sandbox-windows-mic（{@code WindowsSandbox.buildEnvBlock}）、
 * worker（{@code OsSandbox} 两处 {@code ProcessBuilder}、{@code TerminalPtyFactory}）。
 * 进程边界不该由可选扩展决定存在与否，故这些调用点<b>不放在插件里</b>。
 */
public final class SecretPatterns {

    private SecretPatterns() {
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

    /** 名字形态（仅 env 用）：出现凭据语义词即视为可疑。 */
    private static final Pattern SECRET_NAME = Pattern.compile(
            "(?i).*(api.?key|access.?key|secret|token|password|passwd|pwd|credential|private.?key"
                    + "|key|auth|bearer|cert|session|signature).*");

    /**
     * 名字命中但<b>绝不移除</b>的系统/运行时关键变量（精确名 + 前缀）。
     * 这些是进程能不能正常起的问题，不是凭据问题：例如 {@code SSH_AUTH_SOCK} 名字含 auth,
     * 删了会让沙箱里的 git-over-ssh 直接失去 ssh-agent；{@code AUTHLOGONSERVER} 是域控名。
     */
    private static final Set<String> EXACT_EXEMPT = Set.of(
            "ssh_auth_sock", "authlogonserver", "logonserver", "computername", "username",
            "userdomain", "userdomain_roaming_profile", "systemroot", "windir", "windowsdir",
            "comspec", "driverletter", "path", "pathext", "homepath", "homedrive", "home",
            "userprofile", "appdata", "localappdata", "temp", "tmp", "tmpdir", "pwd",
            "programfiles", "programfiles(x86)", "programw6432", "commonprogramfiles",
            "programdata", "public", "allusersprofile", "sessionname", "shell", "term",
            "display", "lang", "lc_all", "language", "region", "tz", "node_path",
            "java_home", "javahome", "dotnet_cli_home", "dotnetroot", "gopath", "goroot",
            "pythonhome", "pythonpath", "npm_config_prefix", "pnpm_home",
            "onedrive", "onedrivedrop", "wdir", "vsinstalllocation", "visualstudioversion");

    /** 前缀豁免（同族系统变量整族保留）。 */
    private static final List<String> PREFIX_EXEMPT = List.of(
            "path", "programfiles", "commonprogramfiles", "system", "windir", "java", "jdk",
            "dotnet", "python", "node", "npm", "nvm", "go", "rust", "cargo", "gradle", "maven",
            "visualstudio", "vs", "wsl", "onedrive", "microsoft", "windows", "session",
            "computer", "userprofile", "locale", "lc_", "xdg_", "ssh_", "keyb");

    /** 名字命中可疑词时,值短于此长度视为占位/无意义,不动(如 {@code SESSIONNAME=Console})。 */
    private static final int MIN_SUSPECT_VALUE_LEN = 8;

    /** env 清理结果：过滤后的新 map（保序）+ 被移除的<b>变量名</b>列表（绝不含值）。 */
    public record EnvScrub(Map<String, String> env, List<String> removedNames) {

        public boolean cleaned() {
            return !removedNames.isEmpty();
        }
    }

    /**
     * 返回清理后的<b>新</b> map（保留原顺序），入参不被修改。
     * 被移除项只回传名字，调用方可安全写日志。
     */
    public static EnvScrub scrubEnv(Map<String, String> source) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> removed = new ArrayList<>();
        if (source == null) {
            return new EnvScrub(out, removed);
        }
        for (Map.Entry<String, String> e : source.entrySet()) {
            String name = e.getKey();
            if (name == null) {
                continue;
            }
            if (isSecretBearing(name, e.getValue())) {
                removed.add(name);
            } else {
                out.put(name, e.getValue());
            }
        }
        return new EnvScrub(out, removed);
    }

    /**
     * 就地清理<b>可变</b> env（{@code ProcessBuilder.environment()} 返回的活视图）：
     * ProcessBuilder 默认整块继承父进程环境，调用方拿不到「先过滤再塞」的机会，只能就地删。
     *
     * @return 被移除的变量名（不含值）
     */
    public static List<String> scrubInPlace(Map<String, String> mutableEnv) {
        List<String> removed = new ArrayList<>();
        if (mutableEnv == null) {
            return removed;
        }
        for (String name : new ArrayList<>(mutableEnv.keySet())) {
            if (isSecretBearing(name, mutableEnv.get(name))) {
                mutableEnv.remove(name);
                removed.add(name);
            }
        }
        return removed;
    }

    /** 宿主继承环境的清理版（{@code System.getenv()} 去掉凭据后的副本）。 */
    public static Map<String, String> inheritedEnv() {
        return scrubEnv(System.getenv()).env();
    }

    /** 该名字是否属于凭据语义词族（不含豁免表）。 */
    public static boolean isSecretName(String name) {
        if (name == null || isExempt(name)) {
            return false;
        }
        return SECRET_NAME.matcher(name).matches();
    }

    /** 变量整体是否「疑似携带凭据」：值形态命中 → 是；名字可疑且值非空非短 → 是。 */
    public static boolean isSecretBearing(String name, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (hasSecretPattern(value)) {
            return true; // 规则 1：值就是凭据,与变量名无关(codex=sk-ant-… 靠这条拦住)
        }
        if (isExempt(name)) {
            return false; // 系统关键变量永不动
        }
        return isSecretName(name) && value.trim().length() >= MIN_SUSPECT_VALUE_LEN; // 规则 2
    }

    /** 值是否命中凭据形态指纹（env 清理内部判定，不对外暴露——文本输出脱敏见 secret-redaction 插件）。 */
    private static boolean hasSecretPattern(String text) {
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

    /** 名字是否命中豁免表（大小写无关，精确 + 前缀）。 */
    private static boolean isExempt(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (EXACT_EXEMPT.contains(lower)) {
            return true;
        }
        for (String prefix : PREFIX_EXEMPT) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
