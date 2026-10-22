package dev.everyagent.worker.os.pty;

import dev.everyagent.plugin.api.exception.BadParamsException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * term.open shell 白名单与系统信任目录校验(安全整改 M-7)。
 *
 * <p>shell 是 RPC 调用方自由字符串,若不加校验直接 setCommand 拉起,调用方可指定
 * 任意可执行文件(如 {@code C:\tools\malware.exe})伪装"终端"。本类在 PTY 拉起前
 * 强制校验,允许形态仅两种:
 * <ol>
 *   <li><b>名称形态</b>:白名单名称(powershell/pwsh/cmd/bash/sh/zsh/wsl,大小写不敏感,
 *       Windows 自动忽略 {@code .exe} 后缀),由 OS 经 PATH 解析——具体落在哪个二进制
 *       由宿主 PATH 决定,调用方无法指定路径;</li>
 *   <li><b>绝对路径形态</b>:仅当目标 <b>realpath</b> 位于系统信任目录
 *       (Windows: %SystemRoot%、System32、SysWOW64、Program Files*;
 *       Linux/macOS: /usr/bin、/bin、/usr/local/bin)<b>且</b>文件名与白名单名称匹配。
 *       realpath 解析击穿 {@code ..} 与符号链接伪装(判定以真实位置为准,而非调用方
 *       书写的路径文本)。</li>
 * </ol>
 *
 * <p>白名单可经 {@code worker.terminal.allowed-shells} 配置扩展(名称与内置清单取并集,
 * 见 WorkerProperties.Terminal)。不匹配一律抛 {@link BadParamsException}(rpc.err
 * BAD_PARAMS),异常消息以中文说明允许形态与扩展方式。
 */
public final class ShellWhitelist {

    /** 内置白名单(名称,小写):常见交互式 shell + wsl;各名称按 OS 可用性自然生效(不可用则 PTY 拉起失败)。 */
    public static final List<String> DEFAULT_NAMES = List.of(
            "powershell", "pwsh", "cmd", "bash", "sh", "zsh", "wsl");

    /** 校验通过描述:request 为调用方原样入参;realpath 为绝对路径形态解析出的真实路径(名称形态为 null)。 */
    public record Checked(String request, String realpath) {}

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private ShellWhitelist() {
    }

    /**
     * 校验 term.open 的 shell 入参。
     *
     * @param shell      RPC 调用方提供的 shell(非 null;null = 平台默认,由调用方自行处理,不经本校验)
     * @param extraNames worker.terminal.allowed-shells 配置扩展名称(与内置清单并集;可 null)
     * @return 校验通过描述
     * @throws BadParamsException shell 为空 / 名称不在白名单 / 相对路径 / 不存在 /
     *                            文件名不匹配 / 路径不在信任目录
     */
    public static Checked check(String shell, List<String> extraNames) {
        List<String> names = effectiveNames(extraNames);
        String raw = shell == null ? "" : shell.trim();
        if (raw.isEmpty()) {
            throw bad(raw, names, "shell 为空");
        }
        if (isPathForm(raw)) {
            return checkAbsolute(raw, names);
        }
        if (!names.contains(baseName(raw))) {
            throw bad(raw, names, "名称不在白名单");
        }
        return new Checked(raw, null);
    }

    // ---- 形态判定与各形态校验 ----

    /** 含路径分隔符或 Windows 盘符前缀 → 按路径形态处理(相对路径随后被拒)。 */
    private static boolean isPathForm(String s) {
        return s.indexOf('/') >= 0 || s.indexOf('\\') >= 0
                || (s.length() >= 2 && s.charAt(1) == ':');
    }

    private static Checked checkAbsolute(String raw, List<String> names) {
        Path p = Path.of(raw);
        if (!p.isAbsolute()) {
            throw bad(raw, names, "相对路径不允许(仅白名单名称或信任目录绝对路径)");
        }
        Path real;
        try {
            real = p.toRealPath(); // 击穿 .. 与符号链接,判定以真实位置为准
        } catch (IOException e) {
            throw new BadParamsException("shell 不存在或无法解析: " + raw + "。" + allowedHint(names));
        }
        if (!Files.isRegularFile(real)) {
            throw new BadParamsException("shell 不是普通可执行文件: " + real + "。" + allowedHint(names));
        }
        if (!names.contains(baseName(real.getFileName().toString()))) {
            throw bad(raw, names, "文件名与白名单不匹配: " + real.getFileName());
        }
        if (!inTrustedDir(real)) {
            throw new BadParamsException("shell 路径不在系统信任目录: " + real + "。信任目录: "
                    + trustedDirsHint() + "。" + allowedHint(names));
        }
        return new Checked(raw, real.toString());
    }

    // ---- 白名单归一 ----

    /** 名称归一:去空白、小写;Windows 忽略 .exe 后缀(powershell.exe == powershell)。 */
    private static String baseName(String s) {
        String v = s.trim().toLowerCase(Locale.ROOT);
        if (WINDOWS && v.endsWith(".exe")) {
            v = v.substring(0, v.length() - ".exe".length());
        }
        return v;
    }

    /** 有效白名单 = 内置清单 ∪ 配置扩展(同样归一;包私有供测试)。 */
    static List<String> effectiveNames(List<String> extraNames) {
        List<String> out = new ArrayList<>(DEFAULT_NAMES);
        if (extraNames != null) {
            for (String e : extraNames) {
                if (e == null) {
                    continue;
                }
                String n = baseName(e);
                if (!n.isEmpty() && !out.contains(n)) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    // ---- 信任目录 ----

    /** 信任目录(已 realpath 归一;Windows 小写化做大小写不敏感比较。包私有供测试)。 */
    static List<String> trustedDirs() {
        List<String> out = new ArrayList<>();
        if (WINDOWS) {
            String root = envFirst("C:\\Windows", "SystemRoot", "windir");
            addDir(out, Path.of(root));
            addDir(out, Path.of(root, "System32"));
            addDir(out, Path.of(root, "SysWOW64"));
            for (String key : new String[]{"ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"}) {
                String v = System.getenv(key);
                if (v != null && !v.isBlank()) {
                    addDir(out, Path.of(v));
                }
            }
        } else {
            addDir(out, Path.of("/usr/bin"));
            addDir(out, Path.of("/bin"));
            addDir(out, Path.of("/usr/local/bin"));
        }
        return out;
    }

    /** 取第一个非空环境变量,全空用 fallback。 */
    private static String envFirst(String fallback, String... keys) {
        for (String k : keys) {
            String v = System.getenv(k);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return fallback;
    }

    private static void addDir(List<String> out, Path dir) {
        try {
            String s = dir.toRealPath().toString();
            out.add(WINDOWS ? s.toLowerCase(Locale.ROOT) : s);
        } catch (IOException e) {
            // 环境变量指向不存在的目录:跳过(fail-closed,只是少一个允许目录)
        }
    }

    /** realpath 是否位于任一信任目录下(含目录本身)。包私有供测试。 */
    static boolean inTrustedDir(Path real) {
        String s = WINDOWS ? real.toString().toLowerCase(Locale.ROOT) : real.toString();
        String sep = WINDOWS ? "\\" : "/";
        for (String dir : trustedDirs()) {
            if (s.equals(dir) || s.startsWith(dir + sep)) {
                return true;
            }
        }
        return false;
    }

    // ---- 错误消息(中文,说明允许形态与扩展方式) ----

    private static BadParamsException bad(String raw, List<String> names, String reason) {
        return new BadParamsException("shell 被拒绝(" + reason + "): " + raw + "。" + allowedHint(names));
    }

    /** 允许形态说明。包私有供测试。 */
    static String allowedHint(List<String> names) {
        return "允许形态: ① 白名单名称(" + String.join("/", names) + ",经 PATH 解析);"
                + "② 绝对路径仅当文件名与白名单匹配且 realpath 位于系统信任目录("
                + trustedDirsHint() + ")。白名单可经 worker.terminal.allowed-shells 配置扩展。";
    }

    /** 信任目录人类可读描述。 */
    static String trustedDirsHint() {
        return WINDOWS ? "Windows/System32/SysWOW64/Program Files*" : "/usr/bin、/bin、/usr/local/bin";
    }
}
