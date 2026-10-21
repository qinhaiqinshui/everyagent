package dev.everyagent.plugin.ripgrepTool;

import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.api.spi.ToolExecutionChain;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * shell 裸 rg 兜底提示拦截器(per-call 工具执行拦截链<b>上行段</b>,结果侧最后防线)。
 *
 * <p><b>问题</b>：本环境 shell 工具（powershell / bash）经沙箱执行，stdin 非 tty——
 * 模型若在 shell 命令里写裸 {@code rg pattern}（不带路径参数），rg 检测到 stdin
 * 非 tty 会改读 stdin（而非搜当前目录），拿到<b>空 stdin → 零匹配 exit 1</b>，
 * 表现为「看似搜了但什么都没有」。schema 化 rg 工具（{@link RgTools}）已根治此问题，
 * 但模型可能仍习惯在 shell 里敲 rg——本拦截器做<b>结果侧兜底</b>：识别该形态时在
 * 工具结果尾部追加一行 hint，引导下一轮改用 rg 工具。
 *
 * <p><b>判定条件（全部满足才命中，逐条保守）</b>：
 * <ol>
 *   <li>当前 tool call 名为 powershell / bash（{@code ctx.toolCall()},per-call 直配）；</li>
 *   <li>结果文本 strip 后恰为 {@code [exit code: 1]}——即 format 的「stdout 数据段
 *       为空 + exit 1 尾注」形态（{@link ExecResults#format} 里占位符
 *       {@code [命令执行完成(无输出)]} 仅在完全空串时出现，exit 尾注非空故二者不叠加；
 *       stdout 有匹配行 / stderr 有 CLIXML 还原文本 / exit 2 正则错误都会让文本变长，
 *       恰好全被本条件排除）；</li>
 *   <li>命令串（toolCall arguments JSON 的 command 字段）含独立的 rg token
 *       （{@code \brg\b}，大小写不敏感——PowerShell 命令名不区分大小写；词边界保证
 *       {@code arg} / {@code cargo} 等不误命中，{@code rg.exe} 命中是正确行为）。</li>
 * </ol>
 *
 * <p><b>抗误报</b>：显式带路径的 {@code rg pattern .} 真·无匹配时同样命中——hint 措辞
 * 条件化（「若本次确实是想搜内容」），此时出现也只是信息性提示，无害。
 *
 * <p><b>改写方式</b>：per-call 结果串改写——{@code next.proceed} 返回当前调用的结果串,
 * 命中则经 {@link ExecResults#appendNote} 追加 hint 后返回(落盘/推送/回灌三条路共享
 * 同一串,改写一次全覆盖;形态对照 secret-redaction 的脱敏改写)。
 *
 * <p><b>order=0(链上顺序语义,架构 §7.14.3)</b>：链最外层(order 最小 = 下行最先 /
 * 上行最后)——上行段在所有内层结果改写型节点（如 secret-redaction=900 的脱敏）之后
 * 执行，既看到它们处理后的最终文本（判定基于真实回灌内容），追加的 hint 也不会被它们
 * 覆盖或再加工。
 */
public class RgHintInterceptor implements ToolExecutionInterceptor {

    private static final System.Logger LOG = System.getLogger(RgHintInterceptor.class.getName());

    /** 命中判定形态：format 在「stdout 空 + 无 stderr + exit 1」时的全部输出。 */
    static final String EXIT1_EMPTY_FORM = "[exit code: 1]";

    /** shell 工具名（worker 的两个 shell 工具，其余工具一律不检查）。 */
    private static final String SHELL_POWERSHELL = "powershell";
    private static final String SHELL_BASH = "bash";

    /**
     * 独立 rg token：词边界 + 大小写不敏感（PowerShell 命令名不区分大小写，
     * {@code RG} / {@code Rg} 同样会跑 ripgrep；bash 侧放宽只是多一条信息性提示）。
     */
    private static final Pattern RG_TOKEN = Pattern.compile("(?i)\\brg\\b");

    /**
     * arguments JSON 里的 command 字段（简易提取，非完整 JSON 解析）：shell 工具
     * schema 的 command 是字符串字段且值为单行命令，转义形态有限，正则抓取 +
     * 常见反转义足够；抓不到（字段缺失 / JSON 形态意外）按未命中处理，绝不抛异常。
     */
    private static final Pattern COMMAND_FIELD = Pattern.compile("\"command\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    /** 追加到工具结果尾部的 hint（条件化措辞，真·无匹配时出现也只是信息性）。 */
    static final String HINT =
            "[hint] shell 里裸 rg 不带路径在本环境会读空 stdin 得到零匹配(exit 1)"
                    + ";若本次确实是想搜内容,请改用 rg 工具(不传 path 即默认搜整个工作区根)"
                    + ",或在 shell 里显式带路径(rg <pattern> .)。";

    @Override
    public String id() {
        return "rg-hint";
    }

    @Override
    public float order() {
        return 0f;
    }

    @Override
    public String invoke(ToolExecutionContext ctx, ToolExecutionChain next)
            throws Exception {
        String data = next.proceed(ctx);
        String command = shellCommandOf(ctx);
        if (command == null || !shouldHint(data, command)) {
            return data;
        }
        LOG.log(System.Logger.Level.INFO,
                "[ripgrep-tool] 检测到 shell 裸 rg 形态(exit 1 + 空输出),已追加改用 rg 工具的 hint: subject={0}",
                ctx.subjectId());
        return ExecResults.appendNote(data, HINT);
    }

    /**
     * 当前调用若是 powershell / bash 则取其命令串（command 字段），否则返回 null
     * （非 shell 工具/字段缺失 → 未命中,直接放行）。
     */
    private static String shellCommandOf(ToolExecutionContext ctx) {
        if (ctx.toolCall() == null || ctx.toolCall().name() == null) {
            return null;
        }
        String name = ctx.toolCall().name().toLowerCase(Locale.ROOT);
        if (!SHELL_POWERSHELL.equals(name) && !SHELL_BASH.equals(name)) {
            return null;
        }
        return extractCommand(ctx.arguments());
    }

    /**
     * 从 toolCall arguments JSON 里取 command 字段值（简易解析：正则抓取 + 常见转义
     * 反转义；缺失 / 形态意外返回 null，调用方按未命中处理）。
     */
    static String extractCommand(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = COMMAND_FIELD.matcher(argumentsJson);
        if (!m.find()) {
            return null;
        }
        return unescapeJsonString(m.group(1));
    }

    /** JSON 字符串字面量的常见转义反转义（够用即可：引号/反斜杠/控制符/四位十六进制 BMP 转义）。 */
    private static String unescapeJsonString(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i == s.length() - 1) {
                sb.append(c);
                continue;
            }
            char e = s.charAt(++i);
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException ignored) {
                            sb.append('u'); // 形态意外：按字面量保底，不影响判定
                        }
                    } else {
                        sb.append('u');
                    }
                }
                default -> sb.append(e);
            }
        }
        return sb.toString();
    }

    /**
     * 命中判定：exit 1 + 空输出形态（strip 后恰为 {@link #EXIT1_EMPTY_FORM}）
     * 且命令串含独立 rg token。见类注释的逐条保守性说明。
     */
    static boolean shouldHint(String responseData, String command) {
        if (responseData == null || command == null) {
            return false;
        }
        if (!EXIT1_EMPTY_FORM.equals(responseData.strip())) {
            return false;
        }
        return RG_TOKEN.matcher(command).find();
    }
}
