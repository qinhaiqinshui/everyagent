package dev.everyagent.worker.tools.permission;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.tools.PermissionDeniedException;
import dev.everyagent.worker.tools.PermissionGate.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 责任链节点(命令):bash/powershell 命令执行前的授权门禁(原 PermissionGate.requireCommand)。
 * 流程:提取路径候选 → 判定是否触及工作区外(含主目录/系统环境变量引用)→ 危险动词逐个
 * 授权(verb key)→ 越界已存在路径逐个授权(EXEC key)。任一授权被拒 → DENY(命令不执行);
 * 全部通过 → ALLOW。
 *
 * <p>系统目录引用不再「忽略不弹」(原 isDeniedPath 防误报分支已删):命令访问系统目录
 * 与访问普通工作区外路径同权,一律走 {@link GrantRegistry} 授权决议链(弹窗/AI 审议)。
 * 授权用用户原始命令(保留引号,搜索模式里的 delete 类关键词不误判)。
 *
 * <p>扫描工具(extractPathCandidates/scanQuoted/QuotedScan/looksLikePath/
 * referencesOutsideWorkspace/referencesHomeDirVars)为 public static,供单测直接钉住契约。
 */
@Component
public class CommandCheck implements PermissionCheck {

    private static final Logger log = LoggerFactory.getLogger(CommandCheck.class);

    /** Windows 绝对路径(C:\... 或 C:/...)。终止符含 ;(语句分隔)与 ,(PowerShell 数组分隔)。 */
    private static final Pattern WIN_ABS = Pattern.compile("(?i)\\b[a-z]:[\\\\/][^\\s\"'|&<>;,]*");
    /** UNC 路径(\\server\share\...)。 */
    private static final Pattern UNC =
            Pattern.compile("\\\\\\\\[^\\\\/\\s\"'|&<>;,]+[\\\\/][^\\s\"'|&<>;,]*");
    /** POSIX 绝对路径(独立 / 开头 token)。 */
    private static final Pattern POSIX_ABS = Pattern.compile("(?<![\\w\\\\])/[^\\s\"'|&<>;,]+");
    /** 含 .. 的相对 token(可能向上逃出工作区)。 */
    private static final Pattern DOTDOT = Pattern.compile("[^\\s\"'|&<>;,]*\\.\\.[^\\s\"'|&<>;,]*");
    /** 指向工作区外高概率的主目录/系统环境变量标记(小写;cmd %VAR% / PowerShell $env:VAR / bash $VAR)。 */
    private static final List<String> HOME_DIR_VARS = List.of(
            "%userprofile%", "%homepath%", "%appdata%", "%localappdata%", "%temp%", "%tmp%",
            "%systemroot%", "%windir%", "%programdata%", "%programfiles%", "%programfiles(x86)%",
            "$env:userprofile", "$env:appdata", "$env:localappdata", "$env:temp", "$env:tmp",
            "$env:systemroot", "$env:windir", "$env:programdata", "$env:programfiles",
            "$home", "$userprofile");

    private final WorkerProperties props;
    private final WorkspaceManager workspaces;
    private final GrantRegistry grants;

    private volatile List<Pattern> dangerousPatterns;

    public CommandCheck(WorkerProperties props, WorkspaceManager workspaces, GrantRegistry grants) {
        this.props = props;
        this.workspaces = workspaces;
        this.grants = grants;
    }

    @Override
    public PermissionDecision check(PermissionContext ctx) {
        String command = ctx.command();
        if (command == null || command.isBlank()) {
            return PermissionDecision.allow("空命令无需授权");
        }
        TaskEntry t = ctx.task();
        try {
            WorkspaceManager.Root ws = workspaces.resolve(t.workspaceRoot);
            List<String> candidates = extractPathCandidates(command);
            // 命令可能影响工作区外(越界路径候选 / 主目录环境变量引用)→ 危险动词才需授权;
            // 仅工作区内(或无可识别外部引用)→ 危险动词直接放行(工作区内增删改查自由)
            boolean touchesOutside = referencesOutsideWorkspace(ws, candidates)
                    || referencesHomeDirVars(command);
            if (touchesOutside) {
                // ① 危险动词(引号内文案不参与,防 echo "rm 很危险" 误报)
                String stripped = scanQuoted(command).stripped();
                for (Pattern p : dangerousPatterns()) {
                    Matcher m = p.matcher(stripped);
                    if (m.find()) {
                        String verb = m.group().trim().toLowerCase();
                        String prompt = "AI 请求执行危险命令: " + PathSupport.abbreviate(command) + "\n"
                                + "命令类别: " + verb + "(删除/破坏类动词)。"
                                + "授权后同类命令(" + verb + ")在所选范围内不再询问。";
                        grants.authorize(t, ctx.agentId(), PathSupport.verbKey(verb),
                                prompt, List.of(), List.of());
                    }
                }
            }
            // ② 越界已存在路径(引号内外都扫;命令的工作目录 = 工作区根)。
            // 系统目录引用不再忽略(原 isDeniedPath 分支已删):同样走授权决议链。
            for (String candidate : candidates) {
                Path norm = resolveCandidate(ws.path(), candidate);
                if (norm == null || !Files.exists(norm)) {
                    continue;
                }
                Path real = PathSupport.grantRootOf(norm).toRealPath();
                if (real.startsWith(ws.realPath())) {
                    continue; // 工作区内放行
                }
                if (OverBroadRootCheck.isOverBroadRoot(real, ws.path(), ws.realPath())) {
                    // §13.3 修复 B L1:真实需求不存在「授权整个盘/覆盖工作区的大根」——
                    // 出现必为解析残渣或模型构造的越权试探,候选丢弃,不弹窗/不进审议
                    log.warn("[gate] L1 拒收过度宽泛授权根(候选丢弃)root={} cmd={}", real,
                            PathSupport.abbreviate(command));
                    continue;
                }
                String prompt = "AI 请求在命令中访问工作区外路径: " + PathSupport.abbreviate(command) + "\n"
                        + "授权范围: " + real + " 及其子目录内的命令访问。";
                grants.authorize(t, ctx.agentId(), PathSupport.pathKey(real, Op.EXEC),
                        prompt, List.of(), List.of(real));
            }
            return PermissionDecision.allow("命令授权检查通过");
        } catch (PermissionDeniedException e) {
            return PermissionDecision.deny(e);
        } catch (IOException e) {
            return PermissionDecision.deny(
                    new PermissionDeniedException("execute: 授权检查失败 " + e.getMessage()));
        }
    }

    // ---- 命令扫描工具(public static,单测契约) ----

    /**
     * 命令串中的路径候选:引号段(内含分隔符者)+ 剥引号后的绝对/UNC/../相对 token。
     * 引号切分是<b>转义感知</b>的(§13.2 修复 A):双引号内 {@code \"}(MSYS/PowerShell)、
     * {@code ""}(cmd/PowerShell/MSYS)与 `` `\" ``(PowerShell)是字面引号,不得切断引号段;
     * 折叠后段内仍含 shell 元字符的一律判非路径丢弃(如 rg 正则串 {@code "a\"|b"} 整体非路径)。
     */
    public static List<String> extractPathCandidates(String command) {
        QuotedScan q = scanQuoted(command);
        List<String> out = new ArrayList<>();
        for (String inner : q.segments()) {
            if (looksLikePath(inner) && !hasShellMetachar(inner)) {
                out.add(inner);
            }
        }
        for (Pattern p : List.of(WIN_ABS, UNC, POSIX_ABS, DOTDOT)) {
            Matcher m = p.matcher(q.stripped());
            while (m.find()) {
                String tok = m.group();
                if (p != DOTDOT && !looksLikePath(tok)) {
                    continue;
                }
                if (!out.contains(tok)) {
                    out.add(tok);
                }
            }
        }
        return out;
    }

    /**
     * 转义感知的引号扫描(§13.2 修复 A):单遍状态机,产出引号段内文(转义已折叠)与
     * 引号段以空格替换后的剩余文本(动词扫描用)。正则 {@code "([^"]*)"} 对 shell 转义
     * 无感知,曾把事故命令的 {@code \"changes\"} 切出「裸反斜杠」伪候选并坍缩成盘根。
     */
    public static QuotedScan scanQuoted(String command) {
        List<String> segments = new ArrayList<>();
        StringBuilder stripped = new StringBuilder(command.length());
        StringBuilder seg = new StringBuilder();
        boolean inDq = false;
        boolean inSq = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            char next = i + 1 < command.length() ? command.charAt(i + 1) : '\0';
            if (inDq) {
                if ((c == '\\' || c == '`') && next == '"') { // \" / `":字面引号,不闭合
                    seg.append('"');
                    i++;
                } else if (c == '"' && next == '"') { // "":字面引号(cmd/PowerShell/MSYS)
                    seg.append('"');
                    i++;
                } else if (c == '"') {
                    segments.add(seg.toString());
                    seg.setLength(0);
                    inDq = false;
                } else {
                    seg.append(c);
                }
            } else if (inSq) {
                if (c == '\'' && next == '\'') { // '':PowerShell 单引号串内字面单引号
                    seg.append('\'');
                    i++;
                } else if (c == '\'') {
                    segments.add(seg.toString());
                    seg.setLength(0);
                    inSq = false;
                } else {
                    seg.append(c);
                }
            } else if (c == '"') {
                inDq = true;
                stripped.append(' ');
            } else if (c == '\'') {
                inSq = true;
                stripped.append(' ');
            } else {
                stripped.append(c);
            }
        }
        if (inDq || inSq) {
            // 未闭合段:PS/bash 本会报错,但 cmd 中 ' 不是引号(后半仍会执行)—
            // 段收录供路径扫描(会经元字符过滤自然丢弃),内容回填 stripped 保持动词扫描保守可见
            segments.add(seg.toString());
            stripped.append(seg);
        }
        return new QuotedScan(segments, stripped.toString());
    }

    /** 转义感知引号扫描结果:segments=引号段内文(转义已折叠);stripped=段以空格替换后的文本。 */
    public record QuotedScan(List<String> segments, String stripped) {
    }

    /** 段内(折叠后)仍含 shell 元字符 → 非单一路径 token,判非路径丢弃(§13.2)。 */
    private static boolean hasShellMetachar(String s) {
        for (int i = 0; i < s.length(); i++) {
            switch (s.charAt(i)) {
                case '"', '\'', '|', '&', '<', '>' -> {
                    return true;
                }
                default -> {
                }
            }
        }
        return false;
    }

    /**
     * 是否像路径 token:须含路径分隔符,且除分隔符/点/冒号/空白外至少一个实际字符
     * (§13.2 修复 A:裸 {@code \}、{@code /}、{@code \\}、{@code \.} 等退化 token 不是路径——
     * 它们 resolve 后只会坍缩成盘根或工作区相对垃圾)。
     */
    public static boolean looksLikePath(String s) {
        if (s == null) {
            return false;
        }
        boolean sawSep = false;
        boolean sawReal = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '/') {
                sawSep = true;
            } else if (c != '.' && c != ':' && !Character.isWhitespace(c)) {
                sawReal = true;
            }
        }
        return sawSep && sawReal; // 仅 \\ / . : 空白组成的退化 token(如裸 \)不是路径
    }

    /** 候选 token → 绝对路径(相对 token 按命令 cwd=工作区根解析);无法解析返回 null。 */
    private static Path resolveCandidate(Path wsRoot, String token) {
        try {
            Path p = Path.of(token);
            if (p.isAbsolute()) {
                return p.normalize();
            }
            if (!looksLikePath(token)) {
                return null;
            }
            return wsRoot.resolve(token).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * 命令串的路径候选是否可能落在工作区外:已存在目标按 realpath 判定(符号链接逃逸同判),
     * 不存在目标按词法判定(相对 .. 逃逸 / 绝对路径越界);系统目录引用也计入(危险动词仍须
     * 授权,防删除类命令波及 C:\Windows 等)。返回 false = 命令只可能落在工作区内,
     * 危险动词直接放行(工作区内增删改查自由)。
     */
    public static boolean referencesOutsideWorkspace(WorkspaceManager.Root ws, List<String> candidates)
            throws IOException {
        Path wsLex = ws.path();
        Path wsReal = ws.realPath();
        for (String candidate : candidates) {
            Path norm = resolveCandidate(wsLex, candidate);
            if (norm == null) {
                continue;
            }
            if (Files.exists(norm)) {
                try {
                    if (!norm.toRealPath().startsWith(wsReal)) {
                        return true;
                    }
                } catch (IOException e) {
                    return true; // 断链/不可解析:保守按越界
                }
            } else if (!norm.startsWith(wsLex)) {
                return true;
            }
        }
        return false;
    }

    /** 主目录/系统环境变量引用(如 %USERPROFILE% / $env:APPDATA / $HOME):命令解析器无法
     *  静态展开成绝对路径,但指向工作区外的概率极高——保守视为越界,危险动词仍须授权,
     *  避免「Remove-Item $HOME\x」这类绕开路径扫描的删除在放开工作区内动词后失去拦截。 */
    public static boolean referencesHomeDirVars(String command) {
        String c = command.toLowerCase(Locale.ROOT);
        for (String marker : HOME_DIR_VARS) {
            if (c.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 危险命令正则(编译缓存;配置不可变,懒编译一次)。 */
    public List<Pattern> dangerousPatterns() {
        List<Pattern> cached = dangerousPatterns;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (dangerousPatterns == null) {
                List<Pattern> out = new ArrayList<>();
                for (String raw : props.getPermissions().getDangerousPatterns()) {
                    try {
                        out.add(Pattern.compile(raw, Pattern.CASE_INSENSITIVE));
                    } catch (RuntimeException e) {
                        log.warn("危险命令正则非法,忽略: {}", raw);
                    }
                }
                dangerousPatterns = out;
            }
            return dangerousPatterns;
        }
    }
}