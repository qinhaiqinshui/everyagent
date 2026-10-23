package dev.everyagent.worker.tools.permission;

import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationRequest;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.tools.PermissionDeniedException;
import dev.everyagent.worker.tools.PermissionGate.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 责任链节点(命令):bash/powershell 命令执行前的授权门禁(原 PermissionGate.requireCommand)。
 * 流程:提取路径候选 → 判定是否触及工作区外(含主目录/系统环境变量引用)→ 危险动词逐个
 * 授权(verb key)→ 越界已存在路径逐个授权(EXEC key)。任一授权被拒 → DENY(命令不执行);
 * 全部通过 → ALLOW。
 *
 * <p><b>工作区外部授权根放行</b>(§7.17,与文件路径链 {@link ExternalRootAllowCheck} 同源):
 * 候选(已存在取 realpath;待建取最深已存在祖先)落在该工作区 {@code externalRoots} 内
 * → 判为「不越界」——该根由用户经 {@code @} 弹窗显式选择注册,语义 = 完全读写
 * READ+WRITE+EXEC,故其内引用既不触发危险动词授权,也不触发 EXEC 路径授权
 * (否则「@ 引用过的目录」文件工具读得进、命令却仍弹窗,与沙箱侧 EXEC 根口径相矛盾)。
 *
 * <p>系统目录引用不再「忽略不弹」(原 isDeniedPath 防误报分支已删):命令访问系统目录
 * 与访问普通工作区外路径同权,一律走 {@link GrantRegistry} 授权决议链(弹窗/AI 审议)。
 * 授权用用户原始命令(保留引号,搜索模式里的 delete 类关键词不误判)。
 *
 * <p><b>审计 M-2 加固</b>:① 主目录变量清单补 {@code ~} 形态与 {@code homedrive/homepath};
 * ② 编码/间接执行特征({@code -enc}/{@code -EncodedCommand}、{@code invoke-expression}/{@code iex}、
 * {@code start-process})命中即视为 touchesOutside,并以「伪动词」走 verb 授权单元(payload
 * 对静态扫描不可见,特征本身即授权对象);③ 解释器({@code bash -c}/{@code -Command}/{@code cmd /c})
 * payload 引号段内的危险动词参与扫描(段内是待执行代码,不是 echo 文案);④ 沙箱感知兜底:
 * 当前生效后端无 OS 级写隔离({@code windows-mic}/{@code none}/DIRECT/未知插件)时,
 * 本门禁是唯一越界写防线——危险动词无视 touchesOutside 前置,无条件要求授权;
 * codex/wsl-ubuntu 有 OS 级兜底,行为不变(该弹才弹,不过度打扰)。
 *
 * <p>扫描工具(extractPathCandidates/scanQuoted/QuotedScan/looksLikePath/
 * referencesOutsideWorkspace/referencesHomeDirVars/referencesBypassExecution)为 public
 * static,供单测直接钉住契约。{@code referencesOutsideWorkspace} 收该工作区的
 * {@code externalRoots}(§7.17):落在外部授权根内的候选不算越界(已授权 = 完全读写
 * READ+WRITE+EXEC)。
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
    /** 指向工作区外高概率的主目录/系统环境变量标记(小写;cmd %VAR% / PowerShell $env:VAR / bash $VAR)。
     *  审计 M-2:补 %homedrive%/$env:homedrive/$env:homepath(homedrive+homepath 组合即主目录)
     *  与 ${home}/${userprofile}(bash 花括号形态,裸 contains 对其失效)。 */
    private static final List<String> HOME_DIR_VARS = List.of(
            "%userprofile%", "%homepath%", "%homedrive%", "%appdata%", "%localappdata%", "%temp%", "%tmp%",
            "%systemroot%", "%windir%", "%programdata%", "%programfiles%", "%programfiles(x86)%",
            "$env:userprofile", "$env:appdata", "$env:localappdata", "$env:temp", "$env:tmp",
            "$env:systemroot", "$env:windir", "$env:programdata", "$env:programfiles",
            "$env:homedrive", "$env:homepath",
            "$home", "$userprofile", "${home}", "${userprofile}");
    /**
     * 波浪号主目录引用({@code ~} / {@code ~/x} / {@code ~\x}):token 边界匹配而非裸
     * contains "~",避免 base64/普通文案里的波浪号字符误报(审计 M-2:
     * 清单曾不含该形态,{@code Remove-Item ~\Documents\*} 绕开检测)。
     */
    private static final Pattern TILDE_HOME = Pattern.compile(
            "(?:^|[\\s\"'`=(;,&|])~(?:$|[\\s/\\\\\"')|;&,>])");
    /**
     * 编码/间接执行特征(审计 M-2):payload 对静态命令扫描不可见,命中即视为 touchesOutside,
     * 并以伪动词走 verb 授权单元。覆盖 PowerShell 编码执行(-enc/-EncodedCommand)、
     * 字符串执行(invoke-expression/iex)、任意进程拉起(start-process)。
     */
    private static final List<BypassFeature> BYPASS_FEATURES = List.of(
            new BypassFeature("encodedcommand", Pattern.compile("(?i)-(?:enc|encodedcommand)(?![\\w-])")),
            new BypassFeature("invoke-expression", Pattern.compile("(?i)\\b(?:invoke-expression|iex)\\b")),
            new BypassFeature("start-process", Pattern.compile("(?i)\\bstart-process\\b")));
    /**
     * 解释器 payload 上下文:命令在开引号处的剥除文本以 {@code -c}/{@code -Command}/{@code /c}
     * 结尾。直接位于其后的引号段是<b>待执行代码</b>而非文案(否则 {@code bash -c 'p=$HOME; rm -rf $p/x'}
     * 的 rm 被引号剥除挡住,门禁失明);是否放行段内动词由 {@link #payloadContext} 按 argv0
     * 精确化(防 {@code grep -c "rm"} 计数模式误报)。
     */
    private static final Pattern SHELL_PAYLOAD = Pattern.compile(
            "(?i)(?:^|[\\s;|&])(-{1,2}c(?:ommand)?|/c)\\s*$");
    /** 接受 {@code -c} payload 的解释器 argv0(basename,小写;PowerShell 短型 {@code -c} 同认)。 */
    private static final Set<String> SHELL_C_INTERPRETERS = Set.of(
            "bash", "sh", "zsh", "ksh", "dash", "fish", "csh", "tcsh", "env", "su",
            "pwsh", "pwsh.exe", "powershell", "powershell.exe");
    /** 有 OS 级写隔离兜底的已知后端(§7.10):codex(WRITE_RESTRICTED 受限令牌)与
     *  wsl-ubuntu(挂载白名单);其余(windows-mic/none/direct/未知插件)一律按无兜底处理。 */
    private static final Set<String> OS_WRITE_ISOLATED_BACKENDS = Set.of("codex", "wsl-ubuntu");

    private final WorkerProperties props;
    private final WorkspaceManager workspaces;
    private final GrantRegistry grants;
    /** 沙箱门面(感知生效后端;null = 非 Spring 装配的单测/手工构造,无从感知,维持旧行为)。 */
    private final OsSandbox osSandbox;

    private volatile List<Pattern> dangerousPatterns;

    @Autowired
    public CommandCheck(WorkerProperties props, WorkspaceManager workspaces, GrantRegistry grants,
            OsSandbox osSandbox) {
        this.props = props;
        this.workspaces = workspaces;
        this.grants = grants;
        this.osSandbox = osSandbox;
    }

    /** 兼容构造(单测/手工装配):无沙箱感知,维持「按有 OS 级兜底」的旧行为。 */
    public CommandCheck(WorkerProperties props, WorkspaceManager workspaces, GrantRegistry grants) {
        this(props, workspaces, grants, null);
    }

    @Override
    public PermissionDecision invoke(PermissionContext ctx, PermissionChain next) {
        String command = ctx.command();
        if (command == null || command.isBlank()) {
            return PermissionDecision.allow("空命令无需授权");
        }
        try {
            WorkspaceManager.Root ws = workspaces.resolve(ctx.workspaceRoot());
            // 该工作区的外部授权根(用户经 @ 显式选择 = 已授权,§7.17):命令串中引用的
            // 路径落在其内即「不越界」,不弹窗、不进审议(语义 = 完全读写 READ+WRITE+EXEC)。
            List<Path> externalRoots = workspaces.externalRootsOf(ctx.workspaceRoot());
            List<String> candidates = extractPathCandidates(command);
            // 编码/间接执行特征(-enc/iex/start-process 等):payload 对静态扫描不可见 → 按越界处理(M-2)
            List<String> bypass = bypassFeaturesIn(command);
            // 命令可能影响工作区外(越界路径候选 / 主目录环境变量引用 / 绕过特征)→ 危险动词才需授权;
            // 仅工作区内或已授权外部根内(或无可识别外部引用)→ 危险动词直接放行(工作区内增删改查自由)。
            // 例外(M-2 沙箱感知兜底):当前生效后端无 OS 级写隔离(windows-mic/none/DIRECT/未知插件)时,
            // 本门禁是唯一越界写防线 → 危险动词无视 touchesOutside 前置,无条件要求授权。
            boolean touchesOutside = referencesOutsideWorkspace(ws, candidates, externalRoots)
                    || referencesHomeDirVars(command)
                    || !bypass.isEmpty();
            if (touchesOutside || sandboxLacksOsWriteIsolation()) {
                // ① 危险动词(引号内文案不参与,防 echo "rm 很危险" 误报;例外:解释器
                //    -c/-Command//c 直接 payload 的引号段是待执行代码,动词保守可见)
                String verbText = verbScanText(scanQuoted(command));
                for (Pattern p : dangerousPatterns()) {
                    Matcher m = p.matcher(verbText);
                    if (m.find()) {
                        String verb = m.group().trim().toLowerCase();
                        String prompt = "AI 请求执行危险命令: " + PathSupport.abbreviate(command) + "\n"
                                + "命令类别: " + verb + "(删除/破坏类动词)。"
                                + "授权后同类命令(" + verb + ")在所选范围内不再询问。";
                        grants.authorize(ctx.authRequest(PathSupport.verbKey(verb), prompt),
                                List.of(), List.of(), List.of());
                    }
                }
                // ①' 绕过/间接执行特征(伪动词,审计 M-2):编码执行/字符串执行/进程拉起的
                // payload 无法静态检视 → 特征本身即授权单元,命中即询问(同类不再询问)
                for (String feature : bypass) {
                    String prompt = "AI 请求执行含绕过特征的命令: " + PathSupport.abbreviate(command) + "\n"
                            + "命令特征: " + feature + "(编码/间接执行,可绕过命令扫描)。"
                            + "授权后同类特征(" + feature + ")在所选范围内不再询问。";
                    grants.authorize(ctx.authRequest(PathSupport.verbKey(feature), prompt),
                            List.of(), List.of(), List.of());
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
                if (ExternalRootAllowCheck.grantingRoot(externalRoots, real) != null) {
                    continue; // §7.17 外部授权根放行环(用户 @ 显式选择 = 已授权):不弹窗/不进审议
                }
                if (OverBroadRootCheck.isOverBroadRoot(real, ws.path(), ws.realPath())) {
                    // §7.8 过宽根拒收:真实需求不存在「授权整个盘/覆盖工作区的大根」——
                    // 出现必为解析残渣或模型构造的越权试探,候选丢弃,不弹窗/不进审议
                    log.warn("[gate] L1 拒收过度宽泛授权根(候选丢弃)root={} cmd={}", real,
                            PathSupport.abbreviate(command));
                    continue;
                }
                String prompt = "AI 请求在命令中访问工作区外路径: " + PathSupport.abbreviate(command) + "\n"
                        + "授权范围: " + real + " 目录(命令中引用该目录下的路径不再询问;"
                        + "引用其子目录中的路径需另行授权)。";
                grants.authorize(ctx.authRequest(PathSupport.pathKey(real, Op.EXEC), prompt),
                        List.of(), List.of(real), List.of(real));
            }
            return PermissionDecision.allow("命令授权检查通过");
        } catch (PermissionDeniedException e) {
            return PermissionDecision.deny(e);
        } catch (java.nio.file.AccessDeniedException e) {
            // toRealPath 拒绝的裸路径 message 不可读(§7.8 可读化):给出原因与排查方向,
            // 不再把裸路径当「不存在/工具损坏」误导
            return PermissionDecision.deny(new PermissionDeniedException(
                    "execute: " + PathSupport.accessDeniedHint(e.getFile())));
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
        List<String> payloadSegments = new ArrayList<>();
        StringBuilder stripped = new StringBuilder(command.length());
        StringBuilder seg = new StringBuilder();
        boolean inDq = false;
        boolean inSq = false;
        boolean payloadHere = false; // 当前引号段是否位于解释器 payload 位置(M-2)
        String argv0 = argv0Of(command);
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
                    closeSegment(segments, payloadSegments, seg, payloadHere);
                    inDq = false;
                } else {
                    seg.append(c);
                }
            } else if (inSq) {
                if (c == '\'' && next == '\'') { // '':PowerShell 单引号串内字面单引号
                    seg.append('\'');
                    i++;
                } else if (c == '\'') {
                    closeSegment(segments, payloadSegments, seg, payloadHere);
                    inSq = false;
                } else {
                    seg.append(c);
                }
            } else if (c == '"') {
                inDq = true;
                payloadHere = payloadContext(stripped, argv0);
                stripped.append(' ');
            } else if (c == '\'') {
                inSq = true;
                payloadHere = payloadContext(stripped, argv0);
                stripped.append(' ');
            } else {
                stripped.append(c);
            }
        }
        if (inDq || inSq) {
            // 未闭合段:PS/bash 本会报错,但 cmd 中 ' 不是引号(后半仍会执行)—
            // 段收录供路径扫描(会经元字符过滤自然丢弃),内容回填 stripped 保持动词扫描保守可见
            String tail = seg.toString();
            closeSegment(segments, payloadSegments, seg, payloadHere);
            stripped.append(tail);
        }
        return new QuotedScan(segments, payloadSegments, stripped.toString());
    }

    /** 收口一个引号段:录入 segments;位于解释器 payload 位置的另录 payloadSegments。 */
    private static void closeSegment(List<String> segments, List<String> payloadSegments,
            StringBuilder seg, boolean payloadHere) {
        segments.add(seg.toString());
        if (payloadHere) {
            payloadSegments.add(seg.toString());
        }
        seg.setLength(0);
    }

    /** 命令 argv0(basename,小写;含引号包裹与路径前缀剥离),供解释器判定。 */
    private static String argv0Of(String command) {
        String t = command.strip();
        if (t.isEmpty()) {
            return "";
        }
        String tok;
        if (t.charAt(0) == '"') {
            int close = t.indexOf('"', 1);
            tok = t.substring(1, close > 0 ? close : t.length());
        } else if (t.charAt(0) == '\'') {
            int close = t.indexOf('\'', 1);
            tok = t.substring(1, close > 0 ? close : t.length());
        } else {
            int end = 0;
            while (end < t.length() && !Character.isWhitespace(t.charAt(end))) {
                end++;
            }
            tok = t.substring(0, end);
        }
        int slash = Math.max(tok.lastIndexOf('/'), tok.lastIndexOf('\\'));
        return (slash >= 0 ? tok.substring(slash + 1) : tok).toLowerCase(Locale.ROOT);
    }

    /**
     * 开引号处的剥除文本是否构成解释器 payload 上下文:须以 {@code -c}/{@code -Command}/
     * {@code --command}/{@code /c} 结尾,且 argv0 是对应解释器(防 {@code grep -c "rm"} 这类
     * 计数/文案开关误判为 payload)。
     */
    private static boolean payloadContext(CharSequence prefixBeforeQuote, String argv0) {
        Matcher m = SHELL_PAYLOAD.matcher(prefixBeforeQuote);
        if (!m.find()) {
            return false;
        }
        String flag = m.group(1).toLowerCase(Locale.ROOT);
        return switch (flag) {
            case "/c" -> argv0.equals("cmd") || argv0.equals("cmd.exe");
            case "-c", "--command" -> SHELL_C_INTERPRETERS.contains(argv0);
            default -> true; // -command:PowerShell 专属长型开关,直接认
        };
    }

    /** 转义感知引号扫描结果:segments=引号段内文(转义已折叠);payloadSegments=直接位于
     *  解释器 -c/-Command//c payload 位置的引号段(段内是待执行代码,非文案);
     *  stripped=段以空格替换后的文本。 */
    public record QuotedScan(List<String> segments, List<String> payloadSegments, String stripped) {
    }

    /** 绕过/间接执行特征:name=伪动词(verb 授权单元 key),pattern=扫描正则。 */
    private record BypassFeature(String name, Pattern pattern) {
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
     * 授权,防删除类命令波及 C:\Windows 等)。<b>外部授权根内的候选不算越界</b>(§7.17:
     * 用户 {@code @} 显式选择 = 已授权,完全读写 READ+WRITE+EXEC)——已存在目标按 realpath、
     * 待建目标按最深已存在祖先判定归属。返回 false = 命令只可能落在工作区内或已授权区域,
     * 危险动词直接放行(工作区内增删改查自由)。
     */
    public static boolean referencesOutsideWorkspace(WorkspaceManager.Root ws, List<String> candidates,
            List<Path> externalRoots) throws IOException {
        Path wsLex = ws.path();
        Path wsReal = ws.realPath();
        for (String candidate : candidates) {
            Path norm = resolveCandidate(wsLex, candidate);
            if (norm == null) {
                continue;
            }
            if (Files.exists(norm)) {
                Path real;
                try {
                    real = norm.toRealPath();
                } catch (IOException e) {
                    return true; // 断链/不可解析:保守按越界
                }
                if (real.startsWith(wsReal)) {
                    continue;
                }
                if (ExternalRootAllowCheck.grantingRoot(externalRoots, real) != null) {
                    continue; // 外部授权根内:已授权区域
                }
                return true;
            } else if (!norm.startsWith(wsLex)) {
                if (externalRootCovers(externalRoots, PathSupport.deepestExisting(norm))) {
                    continue; // 待建目标落在外部授权根内(按最深已存在祖先归属判定)
                }
                return true;
            }
        }
        return false;
    }

    /** 待建目标的归属判定:最深已存在祖先的 realpath 落在外部授权根内 → 已授权(§7.17)。 */
    private static boolean externalRootCovers(List<Path> externalRoots, Path existingAncestor) {
        if (existingAncestor == null) {
            return false;
        }
        try {
            return ExternalRootAllowCheck.grantingRoot(externalRoots, existingAncestor.toRealPath()) != null;
        } catch (IOException e) {
            return false; // 不可解析:保守按未授权(仍走既有授权决议链)
        }
    }

    /** 主目录/系统环境变量引用(如 %USERPROFILE% / $env:APPDATA / $HOME / ~/x):命令解析器无法
     *  静态展开成绝对路径,但指向工作区外的概率极高——保守视为越界,危险动词仍须授权,
     *  避免「Remove-Item $HOME\x」这类绕开路径扫描的删除在放开工作区内动词后失去拦截。
     *  变量间接(p=$HOME; ... $p)由裸名匹配覆盖;bash 花括号形态 {@code ${HOME}} 另列标记。 */
    public static boolean referencesHomeDirVars(String command) {
        String c = command.toLowerCase(Locale.ROOT);
        for (String marker : HOME_DIR_VARS) {
            if (c.contains(marker)) {
                return true;
            }
        }
        return TILDE_HOME.matcher(c).find();
    }

    /** 编码/间接执行特征命中(-enc/-EncodedCommand、invoke-expression/iex、start-process;审计 M-2)。 */
    public static boolean referencesBypassExecution(String command) {
        return !bypassFeaturesIn(command).isEmpty();
    }

    /** 命中的绕过特征名(声明顺序去重;public static 供单测直接钉契约)。 */
    public static List<String> bypassFeaturesIn(String command) {
        String text = verbScanText(scanQuoted(command));
        List<String> hits = new ArrayList<>();
        for (BypassFeature f : BYPASS_FEATURES) {
            if (!hits.contains(f.name()) && f.pattern().matcher(text).find()) {
                hits.add(f.name());
            }
        }
        return hits;
    }

    /** 危险动词/绕过特征扫描文本:剥引号正文 + 解释器 payload 引号段(段内是待执行代码,非文案)。 */
    public static String verbScanText(QuotedScan q) {
        if (q.payloadSegments().isEmpty()) {
            return q.stripped();
        }
        return q.stripped() + " " + String.join(" ", q.payloadSegments());
    }

    /**
     * 当前生效沙箱后端是否<b>无 OS 级写隔离兜底</b>(审计 M-2 沙箱感知):windows-mic/none/
     * DIRECT 或未知插件后端 → true(此时本门禁是唯一越界写防线);codex/wsl-ubuntu → false
     * (有 OS 级兜底,行为不变)。osSandbox=null(非 Spring 装配的单测/手工构造)无从感知 →
     * false(维持旧行为;生产装配恒注入该门面,惰性解析已含 enabled=false → DIRECT 判定)。
     */
    boolean sandboxLacksOsWriteIsolation() {
        if (osSandbox == null) {
            return false;
        }
        return !OS_WRITE_ISOLATED_BACKENDS.contains(osSandbox.id());
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