package dev.everyagent.plugin.api.shell;

import dev.everyagent.plugin.api.spi.ExecResult;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 命令执行结果的公共静态逻辑：格式化（format）、审计截断（truncate）、
 * PowerShell 前缀常量与输出上限常量。
 *
 * <p>从 worker {@code CommandExecutor} 与各沙箱插件执行器中逐字对齐下沉，
 * 取代三处重复实现（worker / codex / wsl-ubuntu），执行机制差异仍留各后端。
 */
public final class ExecResults {

    /** 单流输出字符上限（原 codex / wsl-ubuntu 各存一份，现统一于此）。 */
    public static final int MAX_OUTPUT_CHARS = 1_000_000;

    /**
     * {@link #buildPowerShellScript} 中 Out-String 的行宽。4096 给宽表格(git status、
     * 多列 Format-Table)足够余量;重定向下 PS 默认宽度本就只有 120,不存在「更宽被截」的
     * 语义回退。public:后端(如 codex)的形态断言测试需引用。
     *
     * <p><b>必须声明在 {@link #POWERSHELL_PREFIX} 之前</b>:前缀的 {@code Out-String:Width}
     * 免疫项以本常量为唯一值来源;字段初始化器里用简单名前向引用后置字段属 JLS 8.3.3 的非法
     * 前向引用(javac 实测报「非法前向引用」——constant variable 的例外只覆盖方法/构造器内
     * 引用,不覆盖字段初始化器),故以声明顺序体现依赖,不用限定名 {@code ExecResults.X} 规避。
     */
    public static final int OUT_STRING_WIDTH = 4096;

    /**
     * PowerShell 脚本预置前缀。三项职责：
     *
     * <p><b>1. UTF-8 编码（best-effort，2026-10-05 修正定性）</b>：
     * <b>本前缀无法解决中文乱码，真正生效的是「stdout/stderr 用文件承载」</b>
     *（见 worker {@code OsSandbox#spawnToFileRedirected}）。原因:PowerShell 5.1 的
     * stdout 被重定向到<b>管道</b>时,{@code [Console]::OutputEncoding} 取系统 OEM 码页
     *（中文 Windows=936/GBK）而非控制台码页——{@code chcp 65001} 改的是控制台,
     * 不同步到它;而直接 spawn 的 powershell(无控制台、句柄为文件/管道)实测进入
     * CLM(Constrained Language Mode)——本机经查无任何 WDAC/AppLocker/SAC 策略,
     * 触发源是启动形态本身;经 codex 后端的 cmd-chcp 包装链(cmd 建控制台后 PS 同
     * 控制台启动)实测恢复 FullLanguage。但无论 FullLanguage 与否,属性 setter 都
     * 不该被依赖——编码正解见 ARCHITECTURE「cmd-chcp 包装」条(PS 启动前设 CP)。于是原生子进程
     *（rg/git 等）的 UTF-8 字节先被 PS 按 GBK 解码(非法序列成 U+FFFD,信息当场丢失),
     * 再按 GBK 编码写回管道——<b>读取端无论怎么智能解码都无法还原</b>。
     * 改为文件承载后,PS 的原生子进程直接继承该文件句柄写原始字节,PS 完全不参与转码,
     * cmdlet 输出也按 UTF-8 落文件(实测两条流均纯 UTF-8)。
     * 保留本前缀里的 {@code chcp}/{@code try-catch setter} 作 FullLanguage 或有真实
     * 控制台场景的加成;{@code $OutputEncoding=UTF8} 影响的是「PS 管道数据写入原生子进程
     * <b>stdin</b>」的编码,与上面的 stdout 问题无关,仍有价值;
     * {@code $PSDefaultParameterValues} 让 Get-Content / Set-Content / Out-File
     * 不带 {@code -Encoding} 时默认用 UTF-8 读写文件（PS 5.1 默认按系统 ACP 如 GBK 读，
     * UTF-8 中文文件会乱码）——这一项是纯收益,必须保留。变量/哈希表赋值在 CLM 下允许。
     * 前缀先于用户命令执行,用户显式指定 {@code -Encoding} 则覆盖。
     *
     * <p><b>{@code Out-String:Width} 是给「模型自加 {@code | Out-String}」兜底的免疫项
     * （2026-12,配合 §7.10 PS-003「二次修正」）</b>：{@link #buildPowerShellScript} 外层已按
     * {@link #OUT_STRING_WIDTH} 收口,但模型从日常经验里学到的是「管道输出要加
     * {@code | Out-String}」,一旦它自己加,<b>内层</b>那次 {@code Out-String} 会按无控制台的
     * 默认 <b>120 列</b>折行,外层拿到的是<b>已折好的字符串</b>、宽度再大也救不回来（实测同一
     * 200 字符属性：不加 guard 内层 maxLineLen=120、加 guard=200）。本项把默认宽直接对齐
     * {@link #OUT_STRING_WIDTH},使「加不加都完整」,于是描述<b>无需再提醒模型别自加</b>——
     * 提示语与框架机制打架时,正解是在框架侧免疫而非往描述里加禁令(该决策已把提示删掉,见
     * {@code ShellTool.POWERSHELL_BASELINE} javadoc)。值由 {@link #OUT_STRING_WIDTH} 派生
     * (编译期常量内联,不存在两处硬编码漂移);用户显式 {@code -Width} 仍优先,不覆盖其意图。
     * 位置在 PREFIX 独立行内,<b>不占用户命令行号</b>,故不影响 PositionMessage 定位质量。
     *
     * <p><b>2. 非成功流静默化</b>：静默 progress/information/warning/verbose/debug 流，
     * 避免个别 cmdlet / 模块显式 Write-Progress 等刷屏（不影响真实 stdout 数据与真实 stderr 错误）。
     * 不改变、也不要求改变 AI 的命令写法。
     */
    public static final String POWERSHELL_PREFIX =
            "chcp 65001 >$null; "
            + "try{ [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 }catch{ }; "
            + "$OutputEncoding=[System.Text.Encoding]::UTF8; "
            + "$PSDefaultParameterValues['Get-Content:Encoding']='UTF8'; "
            + "$PSDefaultParameterValues['Set-Content:Encoding']='UTF8'; "
            + "$PSDefaultParameterValues['Out-File:Encoding']='UTF8'; "
            + "$PSDefaultParameterValues['Out-String:Width']=" + OUT_STRING_WIDTH + "; "
            + "$ProgressPreference='SilentlyContinue'; $InformationPreference='SilentlyContinue'; "
            + "$WarningPreference='SilentlyContinue'; $VerbosePreference='SilentlyContinue'; "
            + "$DebugPreference='SilentlyContinue'; ";

    /**
     * PowerShell 脚本退出码传导尾部（拼在用户命令之后）。
     *
     * <p><b>问题</b>:{@code powershell.exe -File x.ps1} 的进程退出码只反映「脚本是否抛出
     * 终止性错误」,脚本里最后一条<b>原生命令</b>（rg / git / npm …）的退出码不会传导出来。
     * 于是工具尾注 {@code [exit code: N]} 永远是 0——而 rg 恰恰用 1 表示「无匹配」、2 表示
     * 「用法/正则错误」,AI 依赖该信号判读结果,静默 0 会把「没搜到」误读成「搜到了但无关」。
     *
     * <p><b>做法</b>:取 {@code $LASTEXITCODE}(PS 为最后一个原生子进程设置),为空(纯 cmdlet
     * 命令)则 0,显式 {@code exit} 出去。{@code if} 作表达式赋值与变量赋值在 CLM 下均允许。
     * 用户命令自带 {@code exit}(脚本当场终止)时本尾部不执行,进程码即用户所给值,同样正确。
     */
    public static final String POWERSHELL_EXIT_TAIL =
            "; $__EAExitCode = if ($null -ne $LASTEXITCODE) { $LASTEXITCODE } else { 0 }; "
            + "exit $__EAExitCode";

    /**
     * 组装完整 PowerShell 执行脚本：prefix + <b>脚本块包裹的用户命令</b> + Out-String
     * 文本化 + 退出码尾部（PS-003 顶层对象输出断流修复）。
     *
     * <p><b>问题</b>：PS 5.1 以 {@code -File} 运行且 stdout/stderr 被重定向（文件<b>或</b>
     * 管道——worker DIRECT 文件承载与 codex runner 管道承载实测同病）时,脚本<b>顶层裸对象</b>
     * 输出（{@code Get-Location} 的 PathInfo、{@code [pscustomobject]}、{@code Select-Object}
     * 产物等）一旦触发格式化引擎渲染,该对象的输出与<b>其后全部输出</b>静默丢失——rc=0、
     * stderr 空、连 CLIXML 都不产生（2026-12 ProcessBuilder 直 spawn 实测 100% 复现;
     * {@code Get-Date}/{@code Get-Item}/{@code Int32} 顶层输出不触发,机理未明,疑与 host
     * 格式化管线在无窗口控制台下的初始化路径有关;try-catch、预访问
     * {@code $Host.UI.RawUI} 均不能救）。结果就是 AI 敲 {@code Get-Location} 得到空输出、
     * 且无从分辨「命令失败」与「没有输出」。
     *
     * <p><b>修复</b>：用户命令包进 {@code & { … } | Out-String -Width 4096}——对象文本化在
     * <b>管道内</b>完成,而管道内的 {@code Format-Table}/{@code Out-String} 路径实测健康,
     * 坏的只是「顶层隐式 Out-Default」路径。
     *
     * <p><b>形态</b>（四行,行边界各自承担职责）：
     * <pre>
     * &lt;POWERSHELL_PREFIX&gt;
     * &amp; {
     * &lt;用户命令&gt;
     * } | Out-String -Width 4096&lt;POWERSHELL_EXIT_TAIL&gt;
     * </pre>
     * 用户命令独占一行既保持 PS 报错 PositionMessage 精确引用用户代码（codex 2026-10
     * 实测的定位质量),又保护用户命令末尾的 {@code # 注释}不被 {@code }} 吞掉——单行式
     * {@code & { cmd # comment }} 的闭合括号会落进注释里导致语法错误。
     *
     * <p><b>语义实测不变</b>：块内 {@code exit N} 终止整个进程且码 N(尾部不执行,与原
     * 行为一致);{@code $LASTEXITCODE} 传导({@code cmd /c exit 3} → rc=3);空输出块仅多
     * 一个空行;{@code Write-Error} 等错误流照旧走 stderr + CLIXML 还原;赋值/控制流语句在
     * 块内自洽(块作用域仅隔离变量导出,单命令内无影响)。
     *
     * @param command 用户命令原文（不转写、不拆分,整段嵌入）
     * @return 完整 .ps1 脚本内容（调用方负责 UTF-8 BOM 落盘）
     */
    public static String buildPowerShellScript(String command) {
        return POWERSHELL_PREFIX + "\n& {\n" + command + "\n} | Out-String -Width "
                + OUT_STRING_WIDTH + POWERSHELL_EXIT_TAIL;
    }

    /**
     * 文件承载模式下 stdout / stderr 的读取字节上限（单流）。
     *
     * <p>字符级上限见 {@link #MAX_OUTPUT_CHARS}；此处先把「读进内存」的字节量封顶，
     * 防止子进程输出数百 MB 时把整份文件读爆堆。UTF-8 中文 3 字节/字，取 4 倍宽裕。
     */
    public static final int MAX_OUTPUT_BYTES = MAX_OUTPUT_CHARS * 4;

    // ---- 承载降级 / 协议告警提示（ISSUES「输出静默丢失」：失败必须对模型可见）----
    //
    // 判定原则：「命令成功且确无输出」与「输出被吞」必须在结果文本上分得开。以下常量
    // 供 worker 侧执行器与 codex runner 侧共同引用——后者经编译期常量内联使用
    //（JLS 4.12.4 constant variable），runner 物化 classpath 不因此新增 jar 依赖；
    // 一律经 {@link #appendNote} 落位为 stderr 末尾的独立行，不改 wire 语义。

    /** 文件承载 tail 读线程异常：前缀 + 异常描述 + {@code " 已读 N 字节]"}。 */
    public static final String CARRIER_TAIL_FAILURE_PREFIX = "[输出承载异常: ";

    /** 输出读线程未在宽限内排空——该流尾部字节可能缺失。 */
    public static final String DRAIN_TIMEOUT_NOTE = "[降级:输出未在宽限内排空,尾部可能缺失]";

    /** 文件承载创建失败、已换轨管道承载（编码路径与断流风险面随之改变）。 */
    public static final String CARRIER_FALLBACK_NOTE = "[降级:文件承载不可用,改用管道承载]";

    /** 收到并丢弃了未知类型帧（IPC 协议演进 / runner-worker 版本不一致信号）。 */
    public static final String UNKNOWN_FRAME_NOTE_PREFIX = "[协议告警:丢弃 ";

    /** PowerShell 命令脚本文件承载失败、回退 -EncodedCommand（报错定位质量回退）。 */
    public static final String SCRIPT_FALLBACK_NOTE = "[降级:脚本承载失败,报错行号可能不准]";

    /**
     * 把单行提示追加到结果文本尾部：原文末尾缺换行先补、提示独立成行并以换行收尾，
     * 与既有输出在文本上保持清晰分界。text 为 null 按空串；note 为 null/空白原样返回。
     */
    public static String appendNote(String text, String note) {
        String base = text == null ? "" : text;
        if (note == null || note.isBlank()) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base);
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        return sb.append(note).append('\n').toString();
    }

    // ---- 命令脚本承载路径掩蔽（ISSUES 四.9）----

    /**
     * 命令脚本承载路径在输出文本中的掩蔽替身。codex 后端以
     * {@code pwsh -File <工作区>/.everyagent/tmp/ea-cmd-*.ps1} 投递命令,PS 报错头
     * （ConciseView）会打印该脚本的<b>绝对路径</b>——脚本在会话结束即删,路径纯属
     * 实现细节泄漏;掩蔽为 {@code <script>} 后 {@code :行号} 保留,定位质量不丢:
     * {@code Get-ChildItem: <script>:3}。
     */
    public static final String SCRIPT_PATH_MASK = "<script>";

    /**
     * 把输出文本中<b>本次命令脚本</b>的路径形态（绝对 / 相对 / 裸文件名,由执行器在
     * 运行时精确提供）替换为 {@link #SCRIPT_PATH_MASK}。大小写不敏感（盘符/目录大小写
     * 与 pwsh 解析形态可能不一致）;空形态跳过,无匹配原样返回。只掩蔽本次脚本的
     * 形态,不按通配扫别的 {@code ea-cmd-*} 名（避免误伤并列出其它脚本的合法输出）。
     */
    public static String maskScriptPath(String text, String... pathForms) {
        String out = text == null ? "" : text;
        if (out.isEmpty() || pathForms == null) {
            return out;
        }
        for (String form : pathForms) {
            if (form == null || form.isEmpty()) {
                continue;
            }
            out = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(form),
                            java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(out).replaceAll(SCRIPT_PATH_MASK);
        }
        return out;
    }

    private ExecResults() {
    }


    /**
     * 智能解码子进程输出字节：先严格 UTF-8，失败则回退系统 ANSI 码页。
     *
     * <p><b>背景（中文乱码根因）</b>：PowerShell 5.1 在 stdout 被重定向到管道时用
     * {@code [Console]::OutputEncoding} 编码文本输出，该属性进程启动时取系统 ANSI
     * 码页（中文 Windows = GBK/936）；CLM 下禁止运行时修改 setter，
     * 无真实控制台时 chcp 65001 也不会同步到它。故 PowerShell cmdlet 的中文输出
     * 实为 GBK 字节，而外部程序（git / rg 等直接写管道的原生命令）输出为 UTF-8，
     * 单一编码无法同时正确还原两者，统一按 UTF-8 解码导致中文乱码。
     *
     * <p><b>策略</b>：GBK 等 ANSI 中文的字节序列不构成合法 UTF-8，严格 UTF-8 解码
     * 会在 malformed/unmappable 上失败，据此回退系统 ANSI 码页
     *（{@code native.encoding}，JDK 18+ 由 Windows GetACP 提供，中文 = GBK）。
     * 纯 ASCII 在两种编码下结果一致，天然安全；UTF-8 外部命令输出严格解码成功，
     * 不受影响。
     *
     * <p><b>截断保护</b>：输出在字节上限处被截断时，末尾可能剩半个 UTF-8 字符，
     * 严格解码失败会导致整条 UTF-8 输出被误判为 ANSI。若唯一错误位于缓冲末尾
     * 3 字节内且余下字节构成合法 UTF-8 序列前缀，按 UTF-8 宽容解码（REPLACE）。
     *
     * <p><b>已知边界</b>：同一字节流内 UTF-8 与 ANSI 中文混排时只能整体择一
     *（出现首个非法 UTF-8 字节即整体判为 ANSI）。属罕见场景，可接受。
     * （2026-10 起 codex 后端经 cmd-chcp 包装让 PowerShell 自身输出也走 UTF-8,
     * 混排场景在上游消除——本回退自此只剩兜底真正 ANSI 工具的价值,见 ARCHITECTURE
     * 「cmd-chcp 包装」条。）
     *
     * @param bytes 子进程 stdout/stderr 原始字节（null / 空返回空串）
     * @return 解码后的文本
     */
    public static String decodeConsoleOutput(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bb)
                    .toString();
        } catch (CharacterCodingException e) {
            if (isIncompleteUtf8Tail(bytes, bb.position())) {
                // 截断残尾：整段本是 UTF-8,宽容解码(末尾半个字符以 U+FFFD 占位)
                return new String(bytes, StandardCharsets.UTF_8);
            }
            return new String(bytes, ansiCharset());
        }
    }

    /**
     * 严格 UTF-8 解码失败的错误位置起、到缓冲末尾,是否仅为一个被截断的
     * UTF-8 序列前缀(lead 字节合法 + 后续均为 continuation + 长度不足)。
     */
    private static boolean isIncompleteUtf8Tail(byte[] bytes, int errorPos) {
        int remaining = bytes.length - errorPos;
        if (remaining <= 0 || remaining > 3) {
            return false; // 错误不在末尾 3 字节内 → 是真实的非 UTF-8 内容
        }
        int lead = bytes[errorPos] & 0xFF;
        int expected;
        if (lead >= 0xC2 && lead <= 0xDF) {
            expected = 2;
        } else if (lead >= 0xE0 && lead <= 0xEF) {
            expected = 3;
        } else if (lead >= 0xF0 && lead <= 0xF4) {
            expected = 4;
        } else {
            return false; // 非法 lead / 孤立 continuation → 非 UTF-8
        }
        if (remaining >= expected) {
            return false; // 字节齐全仍报错 → 序列本身非法 → 非 UTF-8
        }
        for (int i = 1; i < remaining; i++) {
            int b = bytes[errorPos + i] & 0xFF;
            if (b < 0x80 || b > 0xBF) {
                return false; // 后续字节非 continuation → 非 UTF-8
            }
        }
        return true;
    }

    /** 系统 ANSI 码页字符集（Windows = GetACP，如中文 GBK）；取不到回退 JVM 默认。 */
    private static Charset ansiCharset() {
        String nativeEnc = System.getProperty("native.encoding");
        if (nativeEnc != null && !nativeEnc.isBlank()) {
            try {
                return Charset.forName(nativeEnc);
            } catch (Exception ignored) {
                // fall through to default
            }
        }
        return Charset.defaultCharset();
    }

    // ---- ANSI/VT 转义序列剥离（ISSUES 四.8：stderr 直出着色残留） ----

    /**
     * ANSI/VT 转义序列：CSI（着色/光标，pwsh 7 错误流直出主要形态）、OSC（终端标题等，
     * BEL 或 {@code ESC\} 终止，容忍串尾截断）、其余两字符 ESC 序列（{@code ESC M} 等）。
     * 正文（含中文）不属于任何分支,不受影响。
     */
    private static final java.util.regex.Pattern VT_ESCAPE = java.util.regex.Pattern.compile(
            "\u001B(?:\\[[0-9;:<=>?]*[ -/]*[@-~]"
                    + "|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\|$)"
                    + "|[@-Z\\-_])");

    /**
     * 剥离文本中的 ANSI/VT 转义序列。pwsh 7.x 错误流<b>直出</b>（不经 {@code 2>&1} 合并）
     * 时 stderr 携带 VT 着色码，解码链不剥则模型收到原始 {@code ESC[31;1m} 噪声——
     * 不吞信息但污染输出。仅动控制序列，不动正文；无 ESC 字节时零开销直返。
     */
    public static String stripAnsi(String s) {
        if (s == null || s.isEmpty() || s.indexOf('\u001B') < 0) {
            return s == null ? "" : s;
        }
        return VT_ESCAPE.matcher(s).replaceAll("");
    }

    // ---- CLIXML 流记录还原（BUG-2：错误文本不得静默丢失） ----

    /** CLIXML 整段：{@code #< CLIXML} 头 + {@code <Objs …>…</Objs>}（DOTALL，非贪婪）。 */
    private static final java.util.regex.Pattern CLIXML_BLOCK =
            java.util.regex.Pattern.compile("(?s)#< CLIXML.*?</Objs>");

    /** 未闭合的 CLIXML 段（输出被字节上限拦腰截断时的残块）。 */
    private static final java.util.regex.Pattern CLIXML_BLOCK_OPEN =
            java.util.regex.Pattern.compile("(?s)#< CLIXML.*");

    /** CLIXML 内的流记录文本载荷（Error/Warning/Information/Verbose/Debug）。 */
    private static final java.util.regex.Pattern CLIXML_TEXT =
            java.util.regex.Pattern.compile(
                    "(?s)<S S=\"(?:Error|Warning|Information|Verbose|Debug)\">(.*?)</S>");

    /** 未闭合的文本载荷（输出被字节上限拦腰截断时,残块里没有 {@code </S>}）。 */
    private static final java.util.regex.Pattern CLIXML_TEXT_OPEN =
            java.util.regex.Pattern.compile(
                    "(?s)<S S=\"(?:Error|Warning|Information|Verbose|Debug)\">(.*?)$");

    /**
     * 把 PowerShell 在非交互重定向下写进 stderr 的 CLIXML 流记录**还原成错误文本**。
     *
     * <p><b>问题</b>：PowerShell 5.1 在 stderr 被重定向(管道/无控制台)时,把 error/warning
     * 等流序列化成 CLIXML（{@code #< CLIXML} + {@code <Objs>…</Objs>}）。此前实现是
     * <b>整段删除</b>,于是 {@code rg '(' file} 的「regex parse error」、命令不存在、
     * 路径不可读等真实错误信息<b>全部静默消失</b>,工具结果里只剩一个 {@code [exit code: 2]}
     * 或干脆什么都没有——AI 会把「用错了」误读成「没匹配」,这是致命的判断污染。
     *
     * <p><b>做法</b>：逐段用 {@link #messagesFromClixml} 抽出 {@code <S S="Error">} 等
     * 文本载荷替换原段;段外的原生命令纯文本 stderr 保持原样。<b>CLIXML 与承载形态无关</b>
     * ——powershell.exe 只看 stderr 是否控制台,文件承载下实测同样产生(2026-10 codex 后端
     * Write-Error 实测),故 codex 后端执行器聚合处与 worker DIRECT 路径都已接入本方法。
     * 抽不到任何文本时返回空段(纯 progress 噪声)。
     *
     * <p><b>VT 剥离（ISSUES 四.8）</b>：本方法是 stderr 解码链的统一出口（codex 聚合处
     * 与 worker DIRECT 路径都汇入此处），故所有返回路径一律过 {@link #stripAnsi}——
     * pwsh 7.x 错误流直出携带的 {@code ESC[31;1m} 等着色残留在此剥净；stdout 不经过
     * 本方法,不受影响。
     *
     * @param s stderr 原文（可为 null / 空 / 不含 CLIXML）
     * @return 还原后的 stderr 文本（VT 序列已剥）；无 CLIXML 时除剥离外原样返回
     */
    public static String decodeClixml(String s) {
        if (s == null || s.isEmpty() || s.indexOf("CLIXML") < 0) {
            return stripAnsi(s == null ? "" : s);
        }
        StringBuilder out = new StringBuilder();
        java.util.regex.Matcher m = CLIXML_BLOCK.matcher(s);
        int last = 0;
        boolean matched = false;
        while (m.find()) {
            matched = true;
            out.append(s, last, m.start()).append(messagesFromClixml(m.group()));
            last = m.end();
        }
        if (!matched) {
            // 截断的残块(无 </Objs>):按开放块处理,同样抽文本,避免整段噪声留在结果里
            java.util.regex.Matcher open = CLIXML_BLOCK_OPEN.matcher(s);
            while (open.find()) {
                out.append(s, last, open.start()).append(messagesFromClixml(open.group()));
                last = open.end();
            }
        }
        out.append(s, last, s.length());
        return stripAnsi(out.toString().replaceAll("\n{3,}", "\n\n").strip());
    }

    /** 从一段 CLIXML 中抽出全部流记录文本（去内嵌标签、还原 XML 实体与换行转义）。 */
    private static String messagesFromClixml(String block) {
        String closed = collectMessages(CLIXML_TEXT, block);
        if (!closed.isEmpty()) {
            return closed;
        }
        // 残块(被输出上限拦腰截断)没有 </S>,退而求其次取开放标签到段尾
        return collectMessages(CLIXML_TEXT_OPEN, block);
    }

    /** 按给定模式抽取文本载荷并串成多行。 */
    private static String collectMessages(java.util.regex.Pattern p, String block) {
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher t = p.matcher(block);
        while (t.find()) {
            String text = unescapeClixml(t.group(1));
            if (text.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(text);
        }
        return sb.toString();
    }

    /** CLIXML 文本清洗：剥内嵌 XML 标记 → 还原换行转义 → 解 XML 实体 → 收空白。 */
    private static String unescapeClixml(String raw) {
        String s = raw.replaceAll("(?s)<[^>]+>", "");
        s = s.replace("_x000D__x000A_", "\n").replace("_x000D_", "\r").replace("_x000A_", "\n");
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
        return s.strip();
    }

    /**
     * stdout / [stderr] / 超时 / exit code 尾注的共享格式化（尾注仅非零时附加）。
     *
     * <p>各段间以 {@code \n} 连接，空输出不产生前导换行：
     * stdout 在前；stderr 非空时以 {@code [stderr]\n} 独立成段附后；
     * aborted 时追加 {@code [命令被沙箱超时中止]}；exitCode 非零追加 {@code [exit code: N]}。
     *
     * @param r 沙箱命令执行结果
     * @return 格式化文本
     */
    public static String format(ExecResult r) {
        StringBuilder sb = new StringBuilder();
        if (r.stdout() != null && !r.stdout().isEmpty()) {
            sb.append(r.stdout());
        }
        if (r.stderr() != null && !r.stderr().isEmpty()) {
            // stderr 独立成段带标记：stdout 段永远是命令的干净数据，
            // PowerShell 的 CLIXML 流记录等噪音只出现在 [stderr] 段
            sb.append(sb.isEmpty() ? "" : "\n").append("[stderr]\n").append(r.stderr());
        }
        if (r.aborted()) {
            sb.append(sb.isEmpty() ? "" : "\n").append("[命令被沙箱超时中止]");
        }
        // exit code 尾注仅在非零时附加：0（成功）是噪音；非零（尤其 rg 无匹配=exit 1）
        // 是 AI 判读语义的关键信号，必须保留
        if (r.exitCode() != 0) {
            sb.append(sb.isEmpty() ? "" : "\n").append("[exit code: ").append(r.exitCode()).append("]");
        }
        return sb.toString();
    }

    /**
     * 带标志位的格式化重载（codex runner 会话款）：在基础格式上增加
     * {@code [runner 管道在 exit 帧前关闭]}（interrupted 时）与
     * {@code [输出已截断至 N 字符]}（truncated 时，N={@link #MAX_OUTPUT_CHARS}）。
     *
     * @param stdout      stdout 文本（不可为 null）
     * @param stderr      stderr 文本（不可为 null）
     * @param exitCode    退出码
     * @param timedOut    是否被沙箱超时中止
     * @param interrupted runner 管道是否在 exit 帧前关闭
     * @param truncated   输出是否已被截断
     * @return 格式化文本
     */
    public static String format(String stdout, String stderr, int exitCode,
            boolean timedOut, boolean interrupted, boolean truncated) {
        StringBuilder sb = new StringBuilder();
        if (!stdout.isEmpty()) {
            sb.append(stdout);
        }
        if (!stderr.isEmpty()) {
            sb.append(sb.isEmpty() ? "" : "\n").append("[stderr]\n").append(stderr);
        }
        if (timedOut) {
            sb.append(sb.isEmpty() ? "" : "\n").append("[命令被沙箱超时中止]");
        }
        if (interrupted) {
            sb.append(sb.isEmpty() ? "" : "\n").append("[runner 管道在 exit 帧前关闭]");
        }
        if (truncated) {
            sb.append(sb.isEmpty() ? "" : "\n")
                    .append("[输出已截断至 ").append(MAX_OUTPUT_CHARS).append(" 字符]");
        }
        if (exitCode != 0) {
            sb.append(sb.isEmpty() ? "" : "\n")
                    .append("[exit code: ").append(exitCode).append("]");
        }
        return sb.toString();
    }

    /** 审计日志截断：null 归一为 ""，超长截断加 "..."。 */
    public static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "...");
    }
}
