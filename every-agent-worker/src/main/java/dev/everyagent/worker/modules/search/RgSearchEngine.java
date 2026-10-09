package dev.everyagent.worker.modules.search;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.Sandbox;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.tools.RipgrepBinary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 内置 ripgrep 搜索执行引擎(架构 §5.10):内置 file-content / file-name / task 三个
 * {@link dev.everyagent.plugin.api.spi.SearchProvider} 共用的 rg 进程管理管道与纯函数。
 *
 * <p>本类只承载「怎么跑 rg、怎么解析 rg 输出」的通用能力(进程启动、stderr 抽干、超时看门狗、
 * 逐行消费、退出码收尾、argv 拼接、JSON lines 解析、路径归一),<b>不含</b>任何具体搜索类型的
 * 语义(上限、默认排除、过滤字段解释),这些职责归各 provider。
 */
@Component
public final class RgSearchEngine {

    private static final Logger log = LoggerFactory.getLogger(RgSearchEngine.class);

    /** rg 正常退出码:0 = 无匹配、1 = 有匹配;其余(2 等)为错误。 */
    private static final int EXIT_NO_MATCH = 0;
    private static final int EXIT_MATCH = 1;

    /** 子进程 stdin 的 null 设备(命令 stdin 契约 §7.10,与 OsSandbox 同款)。 */
    private static final java.io.File NULL_INPUT = new java.io.File(
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                    ? "NUL" : "/dev/null");

    private final RipgrepBinary rg;
    private final long rgTimeoutMs;

    public RgSearchEngine(RipgrepBinary rg, WorkerProperties props) {
        this.rg = rg;
        this.rgTimeoutMs = props == null ? 60_000 : props.getSearch().getRgTimeoutMs();
    }

    /** rg 是否可用(缺失时上层按可读错误处理)。 */
    public boolean available() {
        return rg.available();
    }

    /** execRg 的逐行消费者:返回 true 继续消费,false = 触顶停止(外层 kill rg)。 */
    @FunctionalInterface
    public interface LineHandler {
        boolean onLine(String line);
    }

    /** execRg 的执行侧收尾态(exit = -1 表示触顶/超时被杀,退出码无意义)。 */
    public record StreamOutcome(boolean truncated, boolean timedOut, int exit, String errText) {
    }

    /** rg --json 单条 match 记录的解析结果(路径保持 rg 原始形态,聚合时归一)。 */
    public record RawHit(String rawPath, int lineNumber, String line, int matchIndex, String matchText) {
    }

    /**
     * 执行 rg 并逐行消费 stdout(三个内置引擎共用的进程管理管道):
     * <ul>
     *   <li>stderr 并发抽干:rg 只写不读会写满管道缓冲卡死进程(与 OsSandbox 同款教训);</li>
     *   <li>超时看门狗线程强杀 → stdout 管道 EOF → 消费循环自然收尾,返回已完成部分并置
     *       truncated(readLine 阻塞期间无法检查 deadline,故须独立线程);</li>
     *   <li>消费者返回 false(解析端计数触顶)立即停止消费、强杀 rg;</li>
     *   <li>finally 强杀:已退出的进程是 no-op;触顶/超时/异常路径绝不留孤儿 rg。</li>
     * </ul>
     */
    public StreamOutcome exec(Path root, List<String> args, LineHandler handler)
            throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>();
        argv.add(rg.path().toString());
        argv.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(root.toFile());
        pb.redirectInput(ProcessBuilder.Redirect.from(NULL_INPUT));

        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("rg 启动失败: " + e.getMessage(), e);
        }

        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Thread errDrain = Thread.ofVirtual().start(() -> {
            try (InputStream es = p.getErrorStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = es.read(buf)) != -1) {
                    errBuf.write(buf, 0, n);
                }
            } catch (IOException ignored) {
                // 进程被杀时管道异常属预期
            }
        });
        AtomicBoolean timedOut = new AtomicBoolean(false);
        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                if (!p.waitFor(rgTimeoutMs, TimeUnit.MILLISECONDS)) {
                    timedOut.set(true);
                    p.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                // 正常收尾/触顶后被主线程打断:无事可做
            }
        });

        boolean truncated = false;
        boolean readerEof = false;
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8), 64 * 1024)) {
            String raw;
            while (true) {
                raw = in.readLine();
                if (raw == null) {
                    readerEof = true;
                    break;
                }
                if (!handler.onLine(raw)) {
                    truncated = true;
                    break; // 触顶:不再消费输出,交 finally kill
                }
            }
        } finally {
            p.destroyForcibly();
        }
        watchdog.interrupt();

        int exit = -1;
        if (!truncated && readerEof && !timedOut.get()) {
            exit = p.waitFor(); // 正常读完:阻塞等待退出码
        } else {
            p.waitFor(2, TimeUnit.SECONDS); // 触顶/超时被杀:稍候进程收尾即可
        }
        errDrain.join(2_000);
        return new StreamOutcome(truncated, timedOut.get(), exit,
                errBuf.toString(StandardCharsets.UTF_8).trim());
    }

    /**
     * 退出码/超时收尾(文件类引擎共用):超时被杀或触顶 → truncated;rg 异常退出码(非 0/1)时
     * 无结果抛 {@link SearchEngineException}(rpc.err),已有结果告警并置 truncated。
     */
    public boolean finishTruncated(StreamOutcome r, int count, String tag) {
        if (r.timedOut()) {
            log.warn("[{}] rg 超时(>{}ms)已强杀,返回已完成部分({} 项)", tag, rgTimeoutMs, count);
            return true;
        }
        if (r.truncated()) {
            return true; // 触顶 kill:退出码无意义
        }
        int exit = r.exit();
        if (exit != EXIT_NO_MATCH && exit != EXIT_MATCH) {
            if (count == 0) {
                throw new SearchEngineException("rg 搜索失败(exit=" + exit + "): " + r.errText());
            }
            log.warn("[{}] rg 异常退出(exit={}),返回已聚合结果并标记截断: {}", tag, exit, r.errText());
            return true;
        }
        return false;
    }

    // ---- 纯函数(argv 拼接 / 解析 / 路径归一;单测钉住契约)----

    /**
     * 拼 rg argv(基础参数与大小写/glob 拼法对齐 VSCode ripgrepTextSearchEngine):
     * {@code --hidden} + 匹配语义段 + include/exclude glob + 搜索路径。
     */
    public static List<String> buildArgs(String pattern, boolean isRegex, boolean caseSensitive,
            boolean wholeWord, List<String> includeGlobs, List<String> excludeGlobs, String searchPath) {
        List<String> args = new ArrayList<>();
        args.add("--hidden");
        args.addAll(buildMatchArgs(pattern, isRegex, caseSensitive, wholeWord));
        if (!includeGlobs.isEmpty()) {
            args.add("-g");
            args.add("!*");
            for (String g : includeGlobs) {
                args.add("-g");
                args.add(g);
            }
        }
        for (String g : excludeGlobs) {
            args.add("-g");
            args.add("!" + g);
        }
        args.add(searchPath);
        return args;
    }

    /**
     * 匹配语义段({@code --json --crlf --no-config} + 大小写/固定串/全字 + {@code -e pattern}):
     * 文件内容与任务搜索共用,保证 pattern 语义一致;不含 {@code --hidden}/glob/搜索路径。
     */
    public static List<String> buildMatchArgs(String pattern, boolean isRegex, boolean caseSensitive,
            boolean wholeWord) {
        String effective = pattern;
        if (wholeWord) {
            String body = isRegex ? pattern : escapeRegex(pattern);
            effective = "\\b(?:" + body + ")\\b";
        }
        List<String> args = new ArrayList<>();
        args.add("--json");
        args.add("--crlf");
        args.add("--no-config");
        args.add(caseSensitive ? "--case-sensitive" : "--ignore-case");
        if (!isRegex && !wholeWord) {
            args.add("--fixed-strings");
        }
        args.add("-e");
        args.add(effective);
        return args;
    }

    /**
     * 文件名引擎的 rg argv:{@code --hidden --files --no-messages} 枚举文件路径。
     * <b>不配 {@code --json}</b>——{@code --files} 下 {@code --json} 只吐 summary 不吐路径;
     * pattern 不进 argv(basename 匹配在本侧 Java 正则完成)。
     */
    public static List<String> buildFileArgs(List<String> includeGlobs, List<String> excludeGlobs,
            String searchPath) {
        List<String> args = new ArrayList<>();
        args.add("--hidden");
        args.add("--files");
        args.add("--no-config");
        args.add("--no-messages");
        if (!includeGlobs.isEmpty()) {
            args.add("-g");
            args.add("!*");
            for (String g : includeGlobs) {
                args.add("-g");
                args.add(g);
            }
        }
        for (String g : excludeGlobs) {
            args.add("-g");
            args.add("!" + g);
        }
        args.add(searchPath);
        return args;
    }

    /**
     * 文件名引擎的 basename 匹配正则:语义与 {@link #buildMatchArgs} 同族——全字包
     * {@code \b(?:...)\b}、固定串先转义正则元字符;方言为 Java {@link Pattern}。
     * 调用方用 {@code find()}(非锚定搜索)匹配。
     */
    public static Pattern compileNamePattern(String pattern, boolean isRegex, boolean caseSensitive,
            boolean wholeWord) {
        String body = isRegex ? pattern : escapeRegex(pattern);
        String effective = wholeWord ? "\\b(?:" + body + ")\\b" : body;
        return Pattern.compile(effective, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
    }

    /**
     * 转义正则元字符(VSCode escapeRegExpCharacters 同集:{@code \ { } * + ? | ^ $ . [ ] ( )};
     * rg 用 Rust regex、不支持 {@code \Q..\E},必须逐字符转义)。
     */
    public static String escapeRegex(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("\\{}*+?|^$.[]()".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 解析 rg --json 的一行:{@code type=match} 记录取出 data.path.text / line_number /
     * lines.text(剥尾部换行,--crlf 下 CRLF 文件可能残留 \r)/ submatches[0] 的 start 与
     * match.text;非 match 记录返回 null。
     */
    public static RawHit parseMatchLine(String json) {
        JsonNode n = Json.parse(json);
        if (!"match".equals(n.path("type").asString(""))) {
            return null;
        }
        JsonNode d = n.path("data");
        String line = d.path("lines").path("text").asString("");
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\n' || line.charAt(end - 1) == '\r')) {
            end--;
        }
        JsonNode sm = d.path("submatches").path(0);
        return new RawHit(d.path("path").path("text").asString(""),
                d.path("line_number").asInt(0),
                line.substring(0, end),
                sm.path("start").asInt(0),
                sm.path("match").path("text").asString(""));
    }

    /** rg 输出路径(相对 cwd 的 {@code ./x} 形态)→ 工作区相对 posix 路径。 */
    public static String relPath(Path root, String raw) {
        Path resolved = root.resolve(raw).normalize();
        if (resolved.startsWith(root)) {
            String rel = root.relativize(resolved).toString().replace('\\', '/');
            return rel.isEmpty() ? "." : rel;
        }
        // 防御:形态意外(绝对路径等)时原样 posix 化,不因此失败
        return raw.replace('\\', '/');
    }

    /** 逗号分隔 glob 列表 → 去空白剔空(过滤袋值可空/缺省)。 */
    public static List<String> splitGlobs(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String g : raw.split(",")) {
            String t = g.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 解析文件类 provider 的「范围」字段(过滤袋 {@code ${kind}.scope})为 rg 的搜索路径参数:
     * 缺省/空/{@code .} = 整根;否则经沙箱 {@link Sandbox#resolveExisting}(realpath + 前缀校验)
     * jailed 在工作区根内,返回工作区相对 posix 路径(根本身归一 {@code .});非目录拒收。
     * 越界/不存在分别抛 {@code SandboxViolationException}/{@code NotFoundException}(客户端可见错误)。
     */
    public static String resolveScope(Path root, String raw) {
        if (raw == null || raw.isBlank() || ".".equals(raw.trim())) {
            return ".";
        }
        try {
            Sandbox sb = new Sandbox(new WorkspaceManager.Root(root, root.toRealPath()));
            Path resolved = sb.resolveExisting(raw.trim());
            if (!Files.isDirectory(resolved)) {
                throw new BadParamsException("搜索范围不是目录: " + raw);
            }
            return sb.display(resolved);
        } catch (IOException e) {
            throw new SearchEngineException("解析搜索范围失败: " + e.getMessage(), e);
        }
    }
}