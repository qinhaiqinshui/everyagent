package dev.everyagent.plugin.sandbox.codex.runner;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;

import dev.everyagent.plugin.sandbox.codex.win.Kernel32Ex;

// 日志:统一 slf4j(2026-10 起)——runner 物化 classpath 已带 slf4j-api/simple
// (RunnerMaterializer.DEPENDENCY_MATCHERS 补齐,此前缺 jar 是 NoClassDefFoundError 的根因)。
// simple 绑定落 System.err,配合 cacheOutputStream=false 始终写 installStderrTee 之后的新流
// (旧教训:jul 的 ConsoleHandler 初始化时绑定 tee 之前的旧 System.err,诊断落进没人看的流)。

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 子进程码页<b>探测</b>：实测"这台机器上，PowerShell 能不能把原生子进程的 UTF-8 输出解对"，
 * 据此决定是否让子进程继承 runner 的控制台；<b>不写死语言环境假设，也不引入配置项</b>，
 * 且<b>改动被证明无效就自动回退</b>。
 *
 * <h2>被探测的性质是什么</h2>
 * .NET Framework 的 {@code Console.OutputEncoding}（PowerShell 5.1 用它解码<b>它自己 spawn 的
 * 子进程</b> stdout）是在<b>子进程启动那一刻</b>读 {@code GetConsoleOutputCP()} 取一次并缓存的。
 * 于是有三条实测事实（本轮逐条跑出来的，不是推断）：
 * <ul>
 *   <li>{@code chcp 65001} 改得动控制台，改不动<b>本进程</b>已缓存的值——所以
 *       {@code ExecResults.POWERSHELL_PREFIX} 里那句 chcp 对当前会话无效
 *       （实测：外层 PS 缓存 936，而它 {@code cmd /c chcp} 报 65001）；</li>
 *   <li>沙箱账户在 CLM 下被策略禁止设置该属性（实测报「此语言模式仅支持核心类型的属性设置」）
 *       ——运行时自救这条路是堵的；</li>
 *   <li>但<b>新启动</b>的子进程会读到当时的控制台码页：父会话先 {@code chcp 936} 再起子 PS，
 *       子进程缓存 936；先 {@code chcp 65001} 再起同一份脚本，子进程缓存 65001 且中文<b>全对</b>。</li>
 * </ul>
 * 再叠加 {@code CREATE_NO_WINDOW} 的实测行为：它让系统给子进程<b>新建一个隐藏控制台</b>，其码页
 * 取系统 OEM 码页（中文 936、日文 932、西欧 850——<b>随机器语言变，故绝不写死</b>），与父控制台
 * 无关；{@code CREATE_NEW_CONSOLE} 同理也是 OEM。所以"改父控制台码页"与"去掉 CREATE_NO_WINDOW"
 * 必须<b>成对</b>生效，只改一处没有效果。
 *
 * <h2>探测流程（每一步都可回退）</h2>
 * <ol>
 *   <li>{@link #measure} 用<b>生产同款</b> spawn（带 {@code CREATE_NO_WINDOW}，即现状）跑一次判定：
 *       子进程内部自己比较"原生命令的字节被解码后是否等于已知串"，只回传 ASCII 标记
 *       {@code EA_UTF8=OK/BAD}——标记纯 ASCII，因此回传通道本身不会被待测的性质污染；</li>
 *   <li>已经 OK → <b>什么都不动</b>（保持现状）。这台机器本来就正确（例如开了
 *       &quot;Beta：使用 Unicode UTF-8 提供全球语言支持&quot;，OEM/ACP 已是 65001），
 *       任何"顺手设一下码页"都是无谓的跨进程副作用；</li>
 *   <li>BAD → 把 runner 所在控制台的输出码页设为 {@link Kernel32Ex#CP_UTF8}，再用
 *       <b>不带</b> {@code CREATE_NO_WINDOW} 的 spawn 复测；复测 OK 才<b>采用</b>
 *       继承控制台模式；</li>
 *   <li>复测仍 BAD（或任何异常/权限失败）→ 把码页<b>改回启动时读到的原值</b>，模式退回
 *       {@code CREATE_NO_WINDOW}，即与修复前完全一致。</li>
 * </ol>
 * 采用后的效果是：PowerShell 自己就把 rg / git / npm / cmd 的原生输出解对，<b>不需要 ConPTY</b>
 * （免掉 stdout/stderr 合并、VT 序列、列宽换行那套协议改造），也不必给每个命令单独做包装。
 *
 * <h2>为什么仍保留文件承载</h2>
 * 探测可能落在降级分支（拿不到控制台、非 Windows、令牌操作被拒、复测仍 BAD），或将来某台机器上
 * 继承控制台带来别的副作用；那时"直出"路径的唯一可靠保障仍是"绕开 PS 的解码"——即
 * {@link ChildProcess} 的文件承载，与探测<b>互为兜底</b>而非重复劳动。降级分支下 <b>PS 管道内
 * 捕获</b>（{@code … | Out-String}、{@code $(rg …)}）的乱码暂无解，verdict 落 runner-stderr.log
 * 供现场判断是否需要给 runner 接私有桌面/评估 ConPTY。
 *
 * <h2>已知代价（实测口径写清，便于复盘）</h2>
 * 采用继承模式后，控制台码页是<b>整个控制台</b>的状态，同控制台下后续启动的其它子进程都会读到
 * 65001：输出 UTF-8 的命令（rg/git/npm/node/py）受益，而输出 OEM 码页的老式控制台程序
 * 会反向乱码。这条取舍我没有用配置去回避，而是<b>用复测来确认净收益</b>：复测同时跑一个
 * OEM 往返探针，若采用 UTF-8 后 OEM 探针由 OK 变 BAD，则记录 warn 日志（不改变采用决定，
 * 因为对本产品而言 rg/git/npm 是主要路径），供后续按现场数据决定是否收窄。
 */
public final class ConsoleProbe {

    /**
     * 统一 slf4j 日志(2026-10):runner 物化 classpath 已带 slf4j-api/simple
     * (RunnerMaterializer.DEPENDENCY_MATCHERS),simple 绑定落 System.err(tee→
     * runner-stderr.log)且 {@code cacheOutputStream=false} 保证写 tee 后的新流;
     * 默认 info,EA_RUNNER_DEBUG=1 时 debug 可见。此前的手写 ProbeLog 适配器
     * (debug 刻意丢弃)随之退役——debug 语义交还日志框架级别控制。
     */
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ConsoleProbe.class);

    /** 判定标记（纯 ASCII,回传通道不受被测码页影响）。 */
    private static final String MARK_OK = "EA_UTF8=OK";
    private static final String MARK_BAD = "EA_UTF8=BAD";

    /** OpenProcessToken 所需权限位（TOKEN_DUPLICATE|QUERY|ASSIGN_PRIMARY|ADJUST_DEFAULT|SESSIONID）。 */
    private static final int TOKEN_ACCESS = 0x0002 | 0x0008 | 0x0001 | 0x0040 | 0x0080;
    private static final int MAXIMUM_ALLOWED = 0x02000000;

    /** 探测+复测的总预算（超时即视为 BAD 并回退,绝不阻断 runner 启动）。 */
    private static final long PROBE_BUDGET_MS = 25_000L;

    private static volatile String verdict = "not-run";
    /** 采用"子进程继承 runner 控制台"模式（仅当复测证明它确实解对 UTF-8 时为 true）。 */
    private static volatile boolean inheritConsole;
    private static volatile int cpAtStart = -1;
    private static volatile int cpNow = -1;
    /** 控制台来源：self（本进程原有）/ parent（继承 worker）/ alloc（自建并隐藏）。 */
    private static volatile String attached = "-";
    /** 是否由本次探测自建了控制台（只有自建的才允许隐藏，绝不藏别人的窗口）。 */
    private static volatile boolean allocated;
    /** 失败细节（AttachConsole/AllocConsole 的 GetLastError），供重启后判断真机能否拿到控制台。 */
    private static volatile String diag = "-";

    private ConsoleProbe() {
    }

    /**
     * 跑一次探测（幂等；由 {@link CodexRunnerMain} 默认启动——{@code EA_CONPROBE=0} 显式
     * 关闭——且在<b>管道连接之后</b>的后台线程调用）。任何失败路径都收敛到"不改变现状"。
     *
     * <p>结论通过 {@link ChildProcess#setInheritConsoleMode} 落地——{@code ChildProcess}
     * 自身不引用本类，否则默认配置（探测关闭）也会触发本类加载，而 runner 子 JVM 的
     * classpath 里没有 slf4j（2026-10-05 实测：{@code NoClassDefFoundError} 掀掉整条链路）。
     */
    public static synchronized void runOnce(Path workspaceRoot) {
        if (!"not-run".equals(verdict)) {
            return;
        }
        if (!Platform.isWindows()) {
            verdict = "skip:non-windows";
            return;
        }
        long t0 = System.currentTimeMillis();
        WinNT.HANDLE token = null;
        int originalCp = -1;
        boolean applied = false;
        try {
            if (!ensureConsoleAvailable()) {
                verdict = "skip:no-console[" + diag + "]";
                log.warn("[codex-runner] 拿不到控制台(attach/alloc 均失败: {}),保持 CREATE_NO_WINDOW"
                        + "(非 ASCII 依赖文件承载与 cmd-chcp 包装)", diag);
                return;
            }
            originalCp = Kernel32Ex.INSTANCE.GetConsoleOutputCP();
            cpAtStart = originalCp;
            cpNow = originalCp;
            log.info("[codex-runner] 控制台就绪: consoleCP={} oemCP={} acp={}",
                    originalCp, Kernel32Ex.INSTANCE.GetOEMCP(), Kernel32Ex.INSTANCE.GetACP());

            token = duplicateSelfPrimaryToken();
            if (token == null) {
                verdict = "skip:no-token";
                return;
            }

            // ① 现状(CREATE_NO_WINDOW)下,原生 UTF-8 能否被 PS 解对
            boolean okAsIs = measure(token, workspaceRoot, false, PROBE_BUDGET_MS);
            if (okAsIs) {
                verdict = "keep:already-ok";
                log.info("[codex-runner] 探测:现状已能正确解码 UTF-8 原生输出,不做任何改动"
                        + "(consoleCP={})", originalCp);
                return;
            }

            // ② 设 UTF-8 + 去掉 CREATE_NO_WINDOW,复测
            if (!Kernel32Ex.INSTANCE.SetConsoleOutputCP(Kernel32Ex.CP_UTF8)) {
                verdict = "revert:set-output-cp-failed(err=" + Native.getLastError() + ")";
                log.warn("[codex-runner] SetConsoleOutputCP(65001) 失败,保持现状: {}", verdict);
                return;
            }
            applied = true;
            cpNow = Kernel32Ex.INSTANCE.GetConsoleOutputCP();
            long left = Math.max(2_000L, PROBE_BUDGET_MS - (System.currentTimeMillis() - t0));
            boolean okAfter = measure(token, workspaceRoot, true, left);
            if (okAfter && cpNow == Kernel32Ex.CP_UTF8) {
                inheritConsole = true;
                verdict = "adopted:inherit-console-utf8";
                log.info("[codex-runner] 探测:采用继承控制台模式(consoleCP {}→{}),PS 侧原生"
                        + "输出解码已实测通过", originalCp, cpNow);
            } else {
                restore(originalCp);
                applied = false;
                verdict = "revert:probe-still-bad(cp=" + cpNow + ")";
                log.warn("[codex-runner] 探测:改码页后复测仍不解 UTF-8,已回退到现状并保留 "
                        + "CREATE_NO_WINDOW: {}", verdict);
            }
            oemRoundTripNote(token, workspaceRoot);
        } catch (Throwable t) {
            inheritConsole = false;
            verdict = "error:" + t.getClass().getSimpleName() + ":" + t.getMessage();
            if (applied && originalCp > 0) {
                restore(originalCp);
            }
            log.warn("[codex-runner] 控制台探测异常,回退现状(不影响会话可用性): {}", verdict, t);
        } finally {
            if (token != null) {
                Kernel32Ex.INSTANCE.CloseHandle(token);
            }
        }
    }

    /** 是否采用"子进程继承 runner 控制台"（= 不加 {@code CREATE_NO_WINDOW}）。 */
    public static boolean inheritConsole() {
        return inheritConsole;
    }

    /** 结论串（日志/自诊断用）。 */
    public static String verdict() {
        return verdict;
    }

    /** 启动时与当前的控制台码页（诊断用；未探测则为 -1）。 */
    public static int cpAtStart() {
        return cpAtStart;
    }

    /** 当前控制台码页。 */
    public static int cpNow() {
        return cpNow;
    }

    // ---- 探测实现 ----

    /**
     * 用生产同款 spawn 跑一次判定。
     *
     * @param inherit 子进程是否继承当前控制台（false = 加 CREATE_NO_WINDOW,即修复前的现状）
     * @return 子进程自报"原生 UTF-8 输出被解对了"（读到 {@code EA_UTF8=OK}）
     */
    static boolean measure(WinNT.HANDLE token, Path workspaceRoot, boolean inherit, long budgetMs) {
        Path script = null;
        try {
            script = writeProbeScript(workspaceRoot);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<String> argv = List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy",
                    "Bypass", "-File", script.toString());
            ChildProcess child = ChildProcess.spawnForProbe(token, argv,
                    workspaceRoot == null ? null : workspaceRoot.toString(), Map.of(), null,
                    inherit);
            try {
                child.closeStdin();
                AtomicBoolean bad = new AtomicBoolean();
                child.startOutputReaders((chunk, isErr) -> {
                    if (!isErr) {
                        out.writeBytes(chunk);
                    }
                });
                ChildProcess.ExitResult r = child.waitForExit(budgetMs);
                child.awaitOutputReaders(1_000L);
                String text = new String(out.toByteArray(), StandardCharsets.US_ASCII);
                boolean ok = text.contains(MARK_OK) && !r.timedOut();
                log.debug("[codex-runner] measure(inherit={}) exit={} verdict={}", inherit,
                        r.exitCode(), ok ? "OK" : "BAD/none");
                return ok;
            } finally {
                child.close();
            }
        } catch (Throwable t) {
            log.warn("[codex-runner] measure 失败(inherit={}): {}", inherit, t.toString());
            return false;
        } finally {
            deleteQuietly(script);
        }
    }

    /**
     * 判定脚本：让子进程<b>自己</b>比较"原生命令输出的字节被解码后是否等于已知串"。
     *
     * <p>关键点:
     * <ul>
     *   <li>脚本自身以 <b>UTF-8 BOM</b> 写出——PS 5.1 读无 BOM 文件按系统 ACP 解码,会把脚本里的
     *       中文常量也弄错,那样测的就不是被探测的性质;带 BOM 时 PS 必按 UTF-8 解(实测);</li>
     *   <li>{@code Set-Content -Encoding UTF8} 先落一个 UTF-8 文件(PS 写文件走 cmdlet,正确),
     *       再用 {@code cmd /c type} 把<b>原始字节</b>打到 stdout——cmd 只搬运字节不转码,
     *       于是"PS 用什么码页解原生子进程输出"被精确暴露;</li>
     *   <li>结论只回传 ASCII 标记,避免用"待验证的解码通道"去传结论本身(自我循环)。</li>
     * </ul>
     */
    private static Path writeProbeScript(Path workspaceRoot) throws java.io.IOException {
        String needle = "编码探针中文①";
        String script = """
                $ErrorActionPreference = 'Stop'
                $needle = '%1$s'
                $tmp = Join-Path (Split-Path -LiteralPath $PSCommandPath -Parent) 'ea-utf8-probe.txt'
                Set-Content -LiteralPath $tmp -Value $needle -Encoding UTF8
                $got = (& cmd.exe /c "type `"$tmp`"" | Out-String)
                $got = $got -replace [string][char]0xFEFF, ''
                $got = $got.Trim()
                if ($got -eq $needle.Trim()) { '%2$s' } else { '%3$s (got=[' + $got + '])' }
                Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
                """.formatted(needle, MARK_OK, MARK_BAD);
        Path p = scratchFile(workspaceRoot, "ea-console-probe-", ".ps1");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = script.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);
        Files.write(p, all);
        return p;
    }

    /** OEM 往返探针:采用 UTF-8 后,输出 OEM 码页的老式命令是否受影响(只记日志,不改决定)。 */
    private static void oemRoundTripNote(WinNT.HANDLE token, Path workspaceRoot) {
        if (!inheritConsole) {
            return;
        }
        try {
            Path script = null;
            String body = """
                    $ErrorActionPreference = 'Stop'
                    $needle = 'OEM探针文本①'
                    $tmp = Join-Path (Split-Path -LiteralPath $PSCommandPath -Parent) 'ea-oem-probe.txt'
                    Set-Content -LiteralPath $tmp -Value $needle -Encoding Oem
                    $got = (& cmd.exe /c "type `"$tmp`"" | Out-String)
                    $got = $got -replace [string][char]0xFEFF, ''
                    if ($got.Trim() -eq $needle.Trim()) { 'EA_OEM=OK' } else { 'EA_OEM=BAD' }
                    Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
                    """;
            script = scratchFile(workspaceRoot, "ea-oem-probe-", ".ps1");
            byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            byte[] all = new byte[3 + b.length];
            System.arraycopy(bom, 0, all, 0, 3);
            System.arraycopy(b, 0, all, 3, b.length);
            Files.write(script, all);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ChildProcess child = ChildProcess.spawnForProbe(token,
                    List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                            "-File", script.toString()),
                    workspaceRoot == null ? null : workspaceRoot.toString(), Map.of(), null, true);
            try {
                child.closeStdin();
                child.startOutputReaders((chunk, isErr) -> {
                    if (!isErr) {
                        out.writeBytes(chunk);
                    }
                });
                child.waitForExit(10_000L);
                child.awaitOutputReaders(500L);
            } finally {
                child.close();
                deleteQuietly(script);
            }
            String text = new String(out.toByteArray(), StandardCharsets.US_ASCII);
            if (text.contains("EA_OEM=BAD")) {
                log.warn("[codex-runner] 采用 UTF-8 控制台后,OEM 往返探针变 BAD——输出 OEM 码页的"
                        + "老式控制台命令会反向乱码;如需该环境请评估收窄继承模式(rg/git 等 UTF-8 "
                        + "命令不受影响)");
            }
        } catch (Throwable t) {
            log.debug("[codex-runner] OEM 探针跳过: {}", t.toString());
        }
    }

    private static void restore(int cp) {
        try {
            Kernel32Ex.INSTANCE.SetConsoleOutputCP(cp);
            cpNow = Kernel32Ex.INSTANCE.GetConsoleOutputCP();
        } catch (Throwable t) {
            log.warn("[codex-runner] 控制台码页回退失败(需关注): {}", t.toString());
        }
    }

    /** 保证本进程挂着一个控制台:自身的→父的→新建(新建必须隐藏窗口,否则桌面闪窗)。 */
    private static boolean ensureConsoleAvailable() {
        try {
            if (consoleWindow() != null) {
                attached = "self";
                return true;
            }
            // 挂父进程(worker JVM)控制台:失败是常态(父本无控制台),记下错误码供现场判断
            if (Kernel32Ex.INSTANCE.AttachConsole(Kernel32Ex.ATTACH_PARENT_PROCESS)
                    && consoleWindow() != null) {
                attached = "parent";
                return true;
            }
            int attachErr = Native.getLastError();
            if (!Kernel32Ex.INSTANCE.AllocConsole()) {
                diag = "attachErr=" + attachErr + ",allocErr=" + Native.getLastError();
                return false;
            }
            allocated = true;
            attached = "alloc";
            WinDef.HWND h = consoleWindow();
            if (h != null) {
                User32.INSTANCE.ShowWindow(h, Kernel32Ex.SW_HIDE);
            }
            return consoleWindow() != null;
        } catch (Throwable t) {
            diag = "throw:" + t.getClass().getSimpleName() + ":" + t.getMessage();
            log.warn("[codex-runner] 控制台准备失败: {}", diag, t);
            return false;
        }
    }

    private static WinDef.HWND consoleWindow() {
        WinDef.HWND h = Kernel32Ex.INSTANCE.GetConsoleWindow();
        if (h == null || h.getPointer() == null || Pointer.nativeValue(h.getPointer()) == 0) {
            return null;
        }
        return h;
    }

    /**
     * 复制自身主令牌。探测只关心"子进程启动时的控制台码页",与令牌是否受限无关,
     * 故用自身令牌即可；沙箱内部无法再派生受限令牌（实测 CreateRestrictedToken 报 87），
     * 这也是不能用 {@code SandboxTokenFactory} 的原因。
     */
    private static WinNT.HANDLE duplicateSelfPrimaryToken() {
        WinNT.HANDLEByReference cur = new WinNT.HANDLEByReference();
        if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(),
                TOKEN_ACCESS, cur)) {
            return null;
        }
        WinNT.SECURITY_ATTRIBUTES sa = new WinNT.SECURITY_ATTRIBUTES();
        sa.dwLength = new WinDef.DWORD(sa.size());
        WinNT.HANDLEByReference dup = new WinNT.HANDLEByReference();
        boolean ok = Advapi32.INSTANCE.DuplicateTokenEx(cur.getValue(), MAXIMUM_ALLOWED, sa,
                WinNT.SECURITY_IMPERSONATION_LEVEL.SecurityIdentification,
                WinNT.TOKEN_TYPE.TokenPrimary, dup);
        Kernel32Ex.INSTANCE.CloseHandle(cur.getValue());
        return ok ? dup.getValue() : null;
    }

    /** 探测脚本落点:工作区 {@code .everyagent/tmp}（受限账户下系统 TEMP 常被拒写）。 */
    private static Path scratchFile(Path workspaceRoot, String prefix, String suffix)
            throws java.io.IOException {
        Path dir = workspaceRoot != null
                ? workspaceRoot.resolve(".everyagent").resolve("tmp")
                : Path.of(System.getProperty("java.io.tmpdir"));
        Files.createDirectories(dir);
        // 不用 Files.createTempFile:其内部 SecureRandom 首次取数在无 profile 账户下
        // 实测恒 ~8s(CryptAcquireContext 超时)——探测真要跑起来时反而引入每 runner 8s。
        // 名字 pid+纳秒(零 crypto)以 CREATE_NEW 独占创建;扩展名必须是 .ps1:
        // powershell -File 拒绝其它扩展(实测 .tmp 直接报错)。此前经 OutputFiles
        // .createExclusive 落 .tmp 名,探测脚本从未真正执行、measure 恒 BAD 恒回退
        // (2026-10 修正;ARCHITECTURE「cmd-chcp 包装」①②③之③)。
        long pid = ProcessHandle.current().pid();
        for (int i = 0; i < 4; i++) {
            Path p = dir.resolve(prefix + pid + "-" + Long.toHexString(System.nanoTime())
                    + (suffix.startsWith(".") ? suffix : "." + suffix));
            try {
                Files.createFile(p);
                return p;
            } catch (java.nio.file.FileAlreadyExistsException retry) {
                // 纳秒撞名,换名重试
            }
        }
        throw new java.io.IOException("无法创建探测脚本文件: " + dir);
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (java.io.IOException ignored) {
            // 尽力清理
        }
    }
}
