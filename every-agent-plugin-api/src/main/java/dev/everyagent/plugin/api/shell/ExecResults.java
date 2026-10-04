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
     * PowerShell 脚本预置前缀。三项职责：
     *
     * <p><b>1. UTF-8 编码（best-effort，2026-10-04 修正）</b>：
     * 沙箱账户受 WDAC/AppLocker 策略进入 CLM（Constrained Language Mode）时，
     * {@code [Console]::OutputEncoding=...} 属性 setter 被拒
     *（PropertySetterNotSupportedInConstrainedLanguage）。前缀里的
     * {@code chcp 65001 >$null}（原生命令，CLM 允许）+ {@code try/catch} 包裹的 setter
     * 是 best-effort：FullLanguage 或有真实控制台时能把输出码页切到 UTF-8。
     * <b>注意</b>：stdout 被重定向到管道且无真实控制台时，chcp 不会同步到
     * .NET Console.OutputEncoding，CLM 下又无法改 setter——PowerShell cmdlet 的
     * 中文输出此时仍是系统 ANSI 码页（如 GBK/936）字节。这一情形由读取端
     * {@link #decodeConsoleOutput(byte[])} 按实际字节编码智能解码兜底，二者配合。
     * {@code $OutputEncoding=UTF8} 使管道数据传给原生子进程时也用 UTF-8；
     * {@code $PSDefaultParameterValues} 让 Get-Content / Set-Content / Out-File
     * 不带 {@code -Encoding} 时默认用 UTF-8 读写文件（PS 5.1 默认按系统 ACP 如 GBK 读，
     * UTF-8 中文文件会乱码）。变量/哈希表赋值在 CLM 下允许，无需包裹。前缀先于用户命令执行，
     * 用户显式指定 {@code -Encoding} 则覆盖。
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
            + "$ProgressPreference='SilentlyContinue'; $InformationPreference='SilentlyContinue'; "
            + "$WarningPreference='SilentlyContinue'; $VerbosePreference='SilentlyContinue'; "
            + "$DebugPreference='SilentlyContinue'; ";

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
