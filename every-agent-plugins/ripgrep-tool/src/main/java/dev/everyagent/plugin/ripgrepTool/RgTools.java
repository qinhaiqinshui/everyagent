package dev.everyagent.plugin.ripgrepTool;

import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.api.spi.CommandGate;
import dev.everyagent.plugin.api.spi.ExecResult;
import dev.everyagent.plugin.api.spi.NativeExec;
import dev.everyagent.plugin.api.spi.ToolOutcomeLog;
import dev.everyagent.plugin.api.tool.PlainTextToolCallResultConverter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * rg AI 工具（@Tool 注解形态，注册见 {@link RgToolProvider}；形态对照 ask-user 的
 * {@code AskUserTool}）。
 *
 * <p><b>调用契约层的根治设计</b>：把「模型按训练习惯忘传路径 → 裸 rg 读空 stdin →
 * 零结果被误读为无匹配」这颗雷消灭在 schema 层——{@code pattern} 必填，
 * {@code path} 可选且<b>省略即默认搜索工作区根</b>（{@link #normalizePath} 在
 * 调用侧把省略/空白的 path 归一化为工作区根<b>绝对路径</b>，argv 恒以显式 path
 * 参数终结、与 stdin 完全无关），模型不传路径也得到正确行为。
 *
 * <p><b>执行链</b>（每步均为窄契约、零手搓等价物）：
 * 拼 argv（纯函数 {@link #buildArgv}，便于单测）→
 * {@link CommandGate#authorize}（§7.8 红线：path 落在工作区外必须走授权链，
 * 拒绝抛异常回灌模型、命令不执行，绝不绕过 gate 放行越界 IO）→
 * {@link NativeExec#spawnNative}（argv 直传、超时、每流输出上限全部内建于宿主实现，
 * 本插件不自建 ProcessBuilder）→ 结果格式化（纯函数 {@link #formatResult}）。
 *
 * <p><b>退出码语义</b>：rg 0=有匹配、1=无匹配、2=错误。exit 1 是搜索的正常结局，
 * 结果文本以「[rg] 无匹配」显式标注（空串会让模型分不清「没搜到」与「执行坏了」）；
 * exit 2 输出 stderr；超时中止加尾注。
 */
public class RgTools {

    /** 工具名（= @Tool 方法名），记账 {@code toolName} 键的组成部分。 */
    public static final String TOOL_NAME = "rg";

    /** 无匹配标注行（rg exit 1 的显式语义，模型据此区分「无匹配」与「出错」）。 */
    public static final String NO_MATCH_NOTE = "[rg] 无匹配";

    /** 单次搜索超时：常规仓库 rg 毫秒级返回，30s 已是宽裕上限。 */
    static final long TIMEOUT_MS = 30_000;

    private final Path rgBinary;
    private final Path workspaceRoot;
    private final CommandGate gate;
    private final ToolOutcomeLog outcomes;
    private final NativeExec exec;

    public RgTools(Path rgBinary, Path workspaceRoot, CommandGate gate,
            ToolOutcomeLog outcomes, NativeExec exec) {
        this.rgBinary = rgBinary;
        this.workspaceRoot = workspaceRoot;
        this.gate = gate;
        this.outcomes = outcomes;
        this.exec = exec;
    }

    /**
     * 用 ripgrep(rg) 做快速正则内容搜索。
     *
     * <p>描述全量自报（用途 / path 默认 / flags / 无匹配形态 / 优先级建议），
     * 不提 stdin——argv 直跑无 stdin 语义。
     */
    @Tool(description = "用 ripgrep(rg) 在文件内容中做正则搜索,远快于逐文件读取和 shell 管道;内容搜索优先用本工具。"
            + "path 省略时默认搜索整个工作区根目录(从工作区根递归,遵循 gitignore),按习惯不传路径即可;"
            + "glob 按文件名过滤(如 \"*.java\");case_insensitive 忽略大小写;"
            + "files_with_matches 只列出含匹配的文件名、不输出匹配行;max_results 限制每个文件的匹配行数。"
            + "有匹配时逐行返回匹配结果;无匹配时返回「[rg] 无匹配」一行;出错时返回 stderr。",
            resultConverter = PlainTextToolCallResultConverter.class)
    public String rg(
            @ToolParam(description = "搜索模式,ripgrep 正则表达式(必填)", required = true) String pattern,
            @ToolParam(description = "要搜索的文件或目录路径;省略时默认搜索整个工作区根目录", required = false) String path,
            @ToolParam(description = "文件名 glob 过滤,如 \"*.java\";省略不过滤", required = false) String glob,
            @ToolParam(description = "true 时忽略大小写(rg -i)", required = false) Boolean case_insensitive,
            @ToolParam(description = "true 时只列出含匹配的文件路径、不输出匹配行(rg -l)", required = false) Boolean files_with_matches,
            @ToolParam(description = "每个文件最多输出的匹配行数(rg -m N)", required = false) Integer max_results) {
        if (pattern == null || pattern.isBlank()) {
            // schema 已标必填；此处兜住空串/空白，避免拼出「rg -- ""」误搜空模式
            throw new IllegalArgumentException("pattern 必填:rg 工具需要搜索模式(正则表达式)");
        }
        String[] argv = buildArgv(rgBinary, pattern, normalizePath(path, workspaceRoot), glob,
                case_insensitive, files_with_matches, max_results);
        String command = commandForm(argv);
        // §7.8 红线:先过授权门禁再执行。path 引用工作区外路径时由 worker 授权决议链
        // (弹窗/AI 审议)处置,拒绝抛 RuntimeException 回灌模型、命令不执行。
        gate.authorize(command);
        // argv 直传 + cwd=工作区根 + 空 env:超时/每流输出上限由 NativeExec 宿主实现内建
        ExecResult r = exec.spawnNative(argv, workspaceRoot, Map.of(), TIMEOUT_MS);
        recordOutcome(command, r);
        return formatResult(r);
    }

    // ── 纯函数(便于单测) ───────────────────────────────────────────────

    /**
     * path 归一化（纯函数）：null/空白 → 工作区根<b>绝对路径</b>
     * （{@code toAbsolutePath().normalize()}），非空 path 原样透传（相对 path 仍由
     * spawn 的 cwd=工作区根解析）。
     *
     * <p>这是「path 省略默认工作区根」的落地点：argv 恒以显式非空 path 参数终结，
     * rg 收到的是路径参数、与 stdin 完全无关——<b>不依赖</b>「rg 无 path 参数时
     * 递归搜 cwd」的语义：该前提在 argv 直跑下不成立（stdin 非 tty 时 rg 转而搜
     * stdin；生产 NativeExec 已把 stdin 重定向到 NUL 设备 → 读到 EOF → exit 1
     * 零匹配，正是被根治的坑）。
     */
    static String normalizePath(String path, Path workspaceRoot) {
        if (path == null || path.isBlank()) {
            return workspaceRoot.toAbsolutePath().normalize().toString();
        }
        return path;
    }

    /**
     * 拼 rg argv（纯函数）：{@code <rg> [flags] -- <pattern> <path>}。
     *
     * <p>flags 固定序：{@code -i}（忽略大小写）、{@code -l}（只列文件名）、
     * {@code -m N}（每文件匹配行上限）、{@code --glob G}（文件名过滤）；
     * 可选项为 null/blank/非正数时该 flag 整体省略。
     *
     * <p>{@code --} 分隔符把 pattern/path 与 flags 切开——模式以 {@code -} 开头
     * （如搜 {@code "-m"}）也不会被误读为 flag。
     *
     * <p><b>path 契约：必非空</b>（调用侧已先经 {@link #normalizePath} 归一化）。
     * null/blank 在此防御性拒绝而非省略——rg 无 path 参数的真实语义是「stdin 非
     * tty 则搜 stdin」，省略 path 等于把「忘传路径读空 stdin」的雷原样留在 argv
     * 里；宁可立即失败也不产出无 path 的 argv。
     */
    static String[] buildArgv(Path rgBinary, String pattern, String path, String glob,
            Boolean caseInsensitive, Boolean filesWithMatches, Integer maxResults) {
        if (path == null || path.isBlank()) {
            // 防御性断言（正常流程不可达：rg() 已归一化）——见方法 javadoc
            throw new IllegalArgumentException(
                    "path 必非空:调用侧应先经 normalizePath 归一化,杜绝无 path 的 argv");
        }
        List<String> args = new ArrayList<>();
        args.add(rgBinary.toString());
        if (Boolean.TRUE.equals(caseInsensitive)) {
            args.add("-i");
        }
        if (Boolean.TRUE.equals(filesWithMatches)) {
            args.add("-l");
        }
        if (maxResults != null && maxResults > 0) {
            args.add("-m");
            args.add(String.valueOf(maxResults));
        }
        if (glob != null && !glob.isBlank()) {
            args.add("--glob");
            args.add(glob);
        }
        args.add("--");
        args.add(pattern);
        args.add(path);
        return args.toArray(String[]::new);
    }

    /**
     * argv 的命令串形态（纯函数）——授权 {@code gate.authorize} 与记账
     * {@code recordShellExit} 共用同一字符串（授权链按「命令串里的越界路径引用」
     * 判定，账本按「工具名+载荷」取键，两侧确定性复现）。含空白/引号/空的参数
     * 加双引号包裹（内部 {@code "} 转义为 {@code \"}），其余原样。
     */
    static String commandForm(String[] argv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < argv.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            String a = argv[i];
            if (a.isEmpty() || a.indexOf(' ') >= 0 || a.indexOf('\t') >= 0 || a.indexOf('"') >= 0) {
                sb.append('"').append(a.replace("\"", "\\\"")).append('"');
            } else {
                sb.append(a);
            }
        }
        return sb.toString();
    }

    /**
     * 结果文本格式化（纯函数，纯文本透传见 PlainTextToolCallResultConverter）：
     * <ul>
     *   <li>exit 0：stdout 匹配行照出（空输出落占位，复用 {@link ExecResults#format}）；</li>
     *   <li>exit 1（未中止）：stdout（通常为空）+ 显式「[rg] 无匹配」行，stderr
     *       非空时以 [stderr] 段附后；</li>
     *   <li>exit 2 / 其它 / 超时中止：复用仓库既有 {@link ExecResults#format} 统一形态
     *       （stdout 数据段 + [stderr] 段 + 超时尾注 + 非零 exit code 尾注）。</li>
     * </ul>
     */
    static String formatResult(ExecResult r) {
        if (r.exitCode() == 1 && !r.aborted()) {
            String s = ExecResults.appendNote(r.stdout(), NO_MATCH_NOTE);
            if (r.stderr() != null && !r.stderr().isBlank()) {
                s = ExecResults.appendNote(s, "[stderr]\n" + r.stderr());
            }
            return s;
        }
        return ExecResults.format(r);
    }

    // ── 记账 ──────────────────────────────────────────────────────────

    /**
     * per-run 工具结局记账（toolOutcomes 未接入时直接跳过）。
     *
     * <p><b>口径与理由</b>：rg 的 exit 1 = 无匹配，是搜索的正常结局而非失败——
     * {@link ToolOutcomeLog.ShellExit} 以「非零退出码即失败」判红，照记 exit 1 会把
     * 每次正常的无匹配搜索在前端标红、与「无匹配是搜索的正常落幕」语义相悖。故与账本
     * 自身「正常返回不记账——查不到即视为成功」的哲学对齐：<b>exit 0 / exit 1（未中止）
     * 都不记账</b>（消费侧查不到 → 判成功）；仅 exit 2（用法/正则/IO 错误）与
     * aborted（超时中止）按失败记账。键的载荷用与 authorize 同一份命令串形态。
     */
    private void recordOutcome(String command, ExecResult r) {
        if (outcomes == null) {
            return;
        }
        if (!r.aborted() && (r.exitCode() == 0 || r.exitCode() == 1)) {
            return;
        }
        outcomes.recordShellExit(TOOL_NAME, command, r.exitCode(), r.aborted());
    }
}
