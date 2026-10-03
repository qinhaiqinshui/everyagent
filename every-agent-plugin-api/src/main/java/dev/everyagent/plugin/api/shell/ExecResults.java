package dev.everyagent.plugin.api.shell;

import dev.everyagent.plugin.api.spi.ExecResult;

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
     * <p><b>1. UTF-8 编码（Constrained Language 兼容，2026-10-04 修正）</b>：
     * 沙箱账户受 WDAC/AppLocker 策略进入 CLM 时，
     * {@code [Console]::OutputEncoding=...} 属性 setter 被拒
     *（PropertySetterNotSupportedInConstrainedLanguage）。改用双保险：
     * {@code chcp 65001 >$null}（原生命令，CLM 允许）先把控制台输出码页切到
     * UTF-8——.NET Console.OutputEncoding 动态反映控制台码页，后续管道输出即
     * UTF-8；setter 再以 {@code try/catch} 包裹（FullLanguage 下直设，CLM 下
     * 静默跳过，chcp 已兜底）。{@code $OutputEncoding=UTF8} 使管道数据传给
     * 原生子进程时也用 UTF-8；{@code $PSDefaultParameterValues} 让
     * Get-Content / Set-Content / Out-File 不带 {@code -Encoding} 时默认用
     * UTF-8 读写文件（PS 5.1 默认按系统 ACP 如 GBK 读，UTF-8 中文文件会乱码）。
     * 变量/哈希表赋值在 CLM 下允许，无需包裹。前缀先于用户命令执行，
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
