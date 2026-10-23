package dev.everyagent.plugin.api.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 环境变量凭据清理规则源（架构 §7.10 环境侧信道闸门 / §7.17 凭据纪律的环境维度）。
 *
 * <p>为什么需要它：沙箱隔离了文件系统与网络，却把宿主进程的<b>环境变量整块继承</b>给了
 * 子进程——任何跑在沙箱里的命令（{@code Get-ChildItem Env:}、{@code env}、{@code cat /proc/self/environ}）
 * 都能直接读到宿主 shell 里散落的 API key，再原样写进工具输出、事件日志与模型上下文。
 * 环境变量因此是一条真实的凭据侧信道，与「工作区外路径」同级别，必须有一道统一闸门。
 *
 * <p>本类负责两类<b>常驻</b>凭据纪律（进程边界安全，不随 {@code secret-redaction} 插件装卸而变化）：
 * ①环境变量清理（{@code scrubEnv} 等判定 API）；②<b>日志出口形态掩码</b>（{@link #maskSecrets}，
 * 架构 §7.17 ③，审计 L-3）——worker 与沙箱插件自身的运维日志（DEBUG HTTP 请求体、
 * {@code [exec] cmd=} 命令审计）不得原文携带凭据。工具结果/事件流/模型上下文的<b>内容脱敏</b>
 * （{@code redact}/{@code mask} 等）仍全部住在 {@code secret-redaction} 插件内
 * （{@code SecretRedactor}），删除该插件即删除全部内容脱敏功能，核心不持有内容脱敏逻辑
 * 也不依赖该插件。
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
 * 剔除审计的<b>去重</b>（名单变化才 INFO、重复降 DEBUG）统一走 {@link #repeatedScrub}。
 * 进程边界不该由可选扩展决定存在与否，故这些调用点<b>不放在插件里</b>。
 * {@link #maskSecrets} 同为常驻消费面（日志出口，见其 javadoc）。
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

    /**
     * 各调用方(tag)已通报过的剔除名单——去重审计记忆(架构 §7.10 环境闸门)。
     *
     * <p>宿主进程环境在 worker 生命周期内基本恒定,被剔除的名单也就恒定;此前每次
     * 构造子进程环境(powershell/cmd/git/runner/env block)都打一条同名 INFO,
     * 宿主 shell 里只要挂着一个命中指纹的变量(如真实事故里那个名叫 {@code codex}
     * 的),日志就被刷成每秒数条的纯噪音。审计的价值在「闸门确实生效 + 剔了谁」,
     * 首次通报即已完整承载,故名单变化才再打 INFO,重复由调用方降为 DEBUG。
     *
     * <p>key 集合有限(=子进程环境构造点个数),无泄漏;并发下两个线程同时
     * 首次调用可能都拿到「未重复」,多打一条 INFO,无害(去重不是精确一次语义)。
     */
    private static final Map<String, List<String>> ANNOUNCED_SCRUBS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 剔除审计去重:记录该调用方的剔除名单并判定是否与上次相同。
     *
     * @param tag         调用方标识(如 {@code "os-sandbox"}/{@code "runner"}),各构造点各自固定
     * @param removedNames 本次被剔除的变量名(仅名,来自 {@link EnvScrub#removedNames()} 等)
     * @return {@code true} = 与该调用方上次相同(调用方降为 DEBUG);{@code false} = 首次
     *         或名单变化(调用方打 INFO)。名单为 null/空恒返回 {@code false}
     */
    public static boolean repeatedScrub(String tag, List<String> removedNames) {
        if (removedNames == null || removedNames.isEmpty()) {
            return false;
        }
        List<String> frozen = List.copyOf(removedNames);
        List<String> prev = ANNOUNCED_SCRUBS.put(tag, frozen);
        return prev != null && prev.equals(frozen);
    }

    /** 日志出口掩码占位符（4 字符——短于全部规则的最小密钥长度，掩码结果不会再命中任何规则，幂等）。 */
    private static final String MASK = "****";

    /** 整段命中时保留的格式前缀上限（字母数；sk/ghp/xoxb/github 等已知格式前缀均 ≤6）。 */
    private static final int MASK_PREFIX_LETTERS = 6;

    /**
     * 对任意文本做凭据形态掩码（架构 §7.17 ③ 日志出口纪律，审计 L-3）。
     *
     * <p>与 env 清理共用同一份 {@code RULES} 值形态指纹：命中即掩，未命中原文保留
     * （零误杀——普通命令/JSON 不会长成 {@code sk-ant-…} 的形状）。整段命中的规则保留
     * 「≤6 个字母的格式前缀 + 紧随的 {@code -}/{@code _} 分隔符」这一可读形态
     * （{@code sk-…} → {@code sk-****}、{@code ghp_…} → {@code ghp_****}、
     * {@code github_pat_…} → {@code github_****}）；字母段未以分隔符收尾的密钥
     * （AWS {@code AKIA…}、JWT、PEM 标头等）不保留任何字符，整段掩为 {@code ****}。
     * 带前缀写法（{@code Bearer xxx} / {@code api_key=xxx}，secretGroup&gt;0）只掩
     * 密钥本体，前缀原样保留便于人读（与 {@code config.get} 的 apiKey 掩码同族思路）。
     *
     * <p><b>幂等</b>：占位符 {@code ****} 长度 4，小于全部规则的最小密钥长度
     * （k=v 形态 8+，Bearer 16+，sk- 类 16+），二次掩码不产生新命中，输出稳定。
     * 正则静态预编译、逐规则单趟扫描、未命中快速跳过——命令审计日志每条都过，
     * 开销可控。null/空串原样返回。规则间顺序应用（前一轮的输出是下一轮的输入），
     * 如 {@code Bearer sk-ant-…} 先被 sk-ant 规则掩为 {@code Bearer sk-****}。
     *
     * <p>消费面（常驻，只改写<b>日志字符串</b>，不触碰工具结果/事件流/模型上下文——
     * 内容脱敏见 secret-redaction 插件）：worker 的 {@code HttpRequestLoggingInterceptor}
     * （DEBUG 请求体，对话上下文可能含用户粘贴的密钥）、{@code CommandExecutor} 与
     * sandbox-wsl-ubuntu / sandbox-windows-codex 的 {@code [exec] cmd=} 命令审计
     * （掩码须在 200 字符截断<b>之前</b>）。
     */
    public static String maskSecrets(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(result);
            if (!m.find()) {
                continue; // 未命中快速跳过(绝大多数命令/JSON 不含密钥)
            }
            StringBuilder sb = new StringBuilder(result.length() + 16);
            int copied = 0;
            do {
                int group = rule.secretGroup();
                int secretStart = group == 0 ? m.start() : m.start(group);
                int secretEnd = group == 0 ? m.end() : m.end(group);
                sb.append(result, copied, secretStart);
                sb.append(group == 0
                        ? maskWholeMatch(result.substring(secretStart, secretEnd)) : MASK);
                copied = secretEnd;
            } while (m.find());
            sb.append(result, copied, result.length());
            result = sb.toString();
        }
        return result;
    }

    /**
     * 整段命中（secretGroup=0）时的可读前缀保留：仅当开头字母段（≤{@link #MASK_PREFIX_LETTERS}）
     * 以 {@code -}/{@code _} 分隔符收尾才视为「格式前缀」保留（分隔符一并保留）；
     * 否则（字母段超长、以数字/其他符号接续、或为空）不保留任何密钥字符，整段 {@code ****}。
     */
    private static String maskWholeMatch(String secret) {
        int n = secret.length();
        int keep = 0;
        while (keep < n && keep < MASK_PREFIX_LETTERS && Character.isLetter(secret.charAt(keep))) {
            keep++;
        }
        boolean separator = keep > 0 && keep < n
                && (secret.charAt(keep) == '-' || secret.charAt(keep) == '_');
        return separator ? secret.substring(0, keep + 1) + MASK : MASK;
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
