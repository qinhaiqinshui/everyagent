package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.BadParamsException;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.tools.RipgrepBinary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 工作区文本内容搜索(fs.search,架构 §7 契约表):内置 ripgrep({@link RipgrepBinary},
 * §5.10)在工作区根做搜索,逐行解析 {@code rg --json} 的 JSON lines 为结构化结果。
 *
 * <p><b>jailed</b>:workspace 必填,经 {@link WorkspaceManager#resolve}(绝对路径 +
 * realpath + 非系统目录,与 fs.read 同源)落定工作区根;rg 进程 cwd 恒为工作区根、
 * 搜索路径参数恒为 {@code .},入参没有任何用户可控路径 → rg 只可能触碰工作区根之下
 * (include/exclude glob 只影响 rg 自身剪枝,不引入越界路径)。不经 OsSandbox 重沙箱
 * (与 NativeGit 同类的平台受控操作,前端显式触发),由「cwd 锁定 + 路径恒 .」兜底。
 *
 * <p><b>结果形状</b>:与前端 workspaceContentSearch 的
 * {@code {matchCount, truncated, files:[{path, matches:[...]}]}} 对齐;单项命中
 * {@code {lineNumber, line, matchIndex, matchText}}(matchIndex/matchText 为本次新增的
 * 增强字段,老形状 must-ignore)。小结果(≤单帧内联上限,同 {@link FsService})直接
 * 内联 ok 返回;超限复用 fs.read 的 rpc.data 分批 + 末帧 ok 汇总(架构 §5.4)。
 *
 * <p><b>流式性(一期)</b>:不边读边推 rpc.data,先把 JSON lines 聚合完再按序列化大小
 * 分批(代码简单;maxResults 默认 1000 兜住聚合内存)。
 */
@Component
public class FsSearchService {

    private static final Logger log = LoggerFactory.getLogger(FsSearchService.class);

    /** rg 进程超时(ms):超时强杀,返回已完成部分并置 truncated=true。 */
    private static final long TIMEOUT_MS = 60_000;

    /** rg 正常退出码:0 = 无匹配、1 = 有匹配;其余(2 等)为错误。 */
    private static final int EXIT_NO_MATCH = 0;
    private static final int EXIT_MATCH = 1;

    /** 触顶截断的默认命中上限(maxResults 缺省值;触顶即 kill rg,置 truncated)。 */
    private static final int DEFAULT_MAX_RESULTS = 1000;

    /** 子进程 stdin 的 null 设备(命令 stdin 契约 §7.10,与 OsSandbox 同款):
     * 保持默认管道会让 rg 在无路径参数时误读 stdin;这里路径恒 {@code .} 不受影响,
     * 但保持同契约防回归。 */
    private static final java.io.File NULL_INPUT = new java.io.File(
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                    ? "NUL" : "/dev/null");

    private final WorkspaceManager workspaces;
    private final RipgrepBinary rg;

    public FsSearchService(RpcDispatcher dispatcher, WorkspaceManager workspaces, RipgrepBinary rg) {
        this.workspaces = workspaces;
        this.rg = rg;
        dispatcher.register(RpcMethods.FS_SEARCH, this::search);
    }

    // ---- RPC 入口 ----

    private void search(RpcContext ctx) throws IOException, InterruptedException {
        if (!rg.available()) {
            throw new IOException("rg 不可用: 未找到内置 ripgrep(<程序根>/runtime/bin/ 或"
                    + " worker.tools.rg-path),无法执行工作区搜索");
        }
        // jailed:workspace 必填,resolve 内完成绝对路径 + realpath + 非系统目录校验(与 fs.read 同源)
        Path root = new Sandbox(workspaces.resolve(ctx.strParam("workspace"))).root();
        String pattern = ctx.strParam("pattern");
        boolean isRegex = boolParam(ctx, "isRegex");
        boolean caseSensitive = boolParam(ctx, "caseSensitive");
        boolean wholeWord = boolParam(ctx, "wholeWord");
        List<String> include = splitGlobs(ctx.optStrParam("includeGlobs", ""));
        List<String> exclude = splitGlobs(ctx.optStrParam("excludeGlobs", ""));
        long rawMax = ctx.optLongParam("maxResults", DEFAULT_MAX_RESULTS);
        if (rawMax < 1) {
            throw new BadParamsException("maxResults 必须 ≥ 1: " + rawMax);
        }
        int maxResults = (int) Math.min(rawMax, Integer.MAX_VALUE);
        // 正则模式先本地试编译:非法正则(未闭合括号等)在入口即拦成可读的 rpc.err。
        // Java 与 rg 的 regex 方言近似但不全同,此处只拦两边都非法的形态。
        if (isRegex) {
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new BadParamsException("正则表达式非法: " + e.getMessage());
            }
        }
        SearchOutcome out = run(root,
                buildArgs(pattern, isRegex, caseSensitive, wholeWord, include, exclude), maxResults);
        reply(ctx, out);
    }

    // ---- rg 执行与聚合 ----

    /** 聚合完成态搜索结果(files 按文件聚合、保持 rg 输出顺序;matchCount = 实际聚合条数)。 */
    record SearchOutcome(LinkedHashMap<String, ObjectNode> files, int matchCount, boolean truncated) {
    }

    /**
     * 执行 rg 并逐行聚合 JSON lines。kill 时机:
     * <ul>
     *   <li>解析端计数达 maxResults 触顶 → 立即 {@code destroyForcibly()}(rg 不再扫盘,
     *       不用 --max-count,由本侧计数控制);</li>
     *   <li>超时({@link #TIMEOUT_MS})watchdog 线程强杀 → 管道 EOF 收尾,返回已完成部分
     *       并置 truncated;</li>
     *   <li>rg 异常退出码(非 0/1):无结果抛 IOException(rpc.err),已有结果返回并置 truncated。</li>
     * </ul>
     */
    private SearchOutcome run(Path root, List<String> args, int maxResults)
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

        // stderr 并发抽干:rg 只写不读会写满管道缓冲卡死进程(与 OsSandbox 同款教训);
        // 单写线程 + join 后读取,无并发写。
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
        // 超时看门狗:readLine 阻塞期间无法检查 deadline,由独立线程在超时后强杀进程
        // → stdout 管道 EOF → 主循环自然收尾。
        AtomicBoolean timedOut = new AtomicBoolean(false);
        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                if (!p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    timedOut.set(true);
                    p.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                // 正常收尾/触顶后被主线程打断:无事可做
            }
        });

        LinkedHashMap<String, ObjectNode> files = new LinkedHashMap<>();
        int count = 0;
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
                RawHit hit = parseMatchLine(raw);
                if (hit == null) {
                    continue; // begin/end/summary 等非 match 记录
                }
                append(files, relPath(root, hit.rawPath()), hit);
                if (++count >= maxResults) {
                    truncated = true;
                    break; // 触顶:不再消费输出,交 finally kill
                }
            }
        } finally {
            // 已退出的进程是 no-op;触顶/超时/异常(含 rpc.cancel 打断)路径绝不留孤儿 rg
            p.destroyForcibly();
        }
        watchdog.interrupt();

        if (!truncated && readerEof && !timedOut.get()) {
            // 正常读完:校验退出码(0 无匹配 / 1 有匹配均正常)
            int exit = p.waitFor();
            if (exit != EXIT_NO_MATCH && exit != EXIT_MATCH) {
                errDrain.join(2_000);
                String errText = errBuf.toString(StandardCharsets.UTF_8).trim();
                if (count == 0) {
                    throw new IOException("rg 搜索失败(exit=" + exit + "): " + errText);
                }
                log.warn("[fs.search] rg 异常退出(exit={}),返回已聚合结果并标记截断: {}", exit, errText);
                truncated = true;
            }
        } else if (timedOut.get()) {
            truncated = true;
            log.warn("[fs.search] rg 超时(>{}ms)已强杀,返回已完成部分({} 项)", TIMEOUT_MS, count);
        } else {
            // 触顶 kill:稍候进程收尾即可(退出码无意义)
            p.waitFor(2, TimeUnit.SECONDS);
        }
        return new SearchOutcome(files, count, truncated);
    }

    /** rg --json 单条 match 记录的解析结果(路径保持 rg 原始形态,聚合时归一)。 */
    record RawHit(String rawPath, int lineNumber, String line, int matchIndex, String matchText) {
    }

    /**
     * 解析 rg --json 的一行:{@code type=match} 记录取出 data.path.text /
     * line_number / lines.text(剥尾部换行,--crlf 下 CRLF 文件可能残留 \r)/
     * submatches[0] 的 start(行内偏移,0-based)与 match.text;非 match 记录
     * (begin/end/summary)返回 null。
     */
    static RawHit parseMatchLine(String json) {
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
    static String relPath(Path root, String raw) {
        Path resolved = root.resolve(raw).normalize();
        if (resolved.startsWith(root)) {
            String rel = root.relativize(resolved).toString().replace('\\', '/');
            return rel.isEmpty() ? "." : rel;
        }
        // 防御:形态意外(绝对路径等)时原样 posix 化,不因此失败
        return raw.replace('\\', '/');
    }

    /** 同一文件的多个命中聚合进同一 files 项(LinkedHashMap 保持 rg 输出顺序)。 */
    private static void append(LinkedHashMap<String, ObjectNode> files, String path, RawHit hit) {
        ObjectNode file = files.get(path);
        if (file == null) {
            file = Json.obj().put("path", path);
            file.set("matches", Json.arr());
            files.put(path, file);
        }
        ((ArrayNode) file.get("matches")).add(Json.obj()
                .put("lineNumber", hit.lineNumber())
                .put("line", hit.line())
                .put("matchIndex", hit.matchIndex())
                .put("matchText", hit.matchText()));
    }

    // ---- argv 拼接(纯函数,单测钉住契约)----

    /**
     * 拼 rg argv(基础参数与大小写/glob 拼法对齐 VSCode ripgrepTextSearchEngine):
     * <ul>
     *   <li>基础:{@code --hidden --json --crlf --no-config},搜索路径恒 {@code .}
     *       (cwd = 工作区根,jailed 的执行半边);</li>
     *   <li>大小写:不敏感(缺省)加 {@code --ignore-case},敏感加 {@code --case-sensitive};</li>
     *   <li>固定串(isRegex=false 且非全字):pattern <b>原样</b> + {@code --fixed-strings}
     *       ——rg -F 是纯字节字面量匹配、不做反转义,转义与 -F 并用会让含元字符的搜索词
     *       (如 {@code C++}、{@code foo.bar})失效;</li>
     *   <li>全字:不用 -w,由本侧包 {@code \b(?:...)\b} 后按正则传(包裹后必为正则模式,
     *       不能再加 --fixed-strings,否则 \b 会被当字面量);固定串先转义正则元字符再包裹,
     *       保持字面量语义;</li>
     *   <li>include:非空时先 {@code -g !*} 全拒再逐 glob 放行(VSCode 拼法,rg 剪枝最
     *       有效);exclude:逐 {@code -g !<glob>};</li>
     *   <li>不用 --max-count:由解析端计数触顶 kill(见 {@link #run})。</li>
     * </ul>
     */
    static List<String> buildArgs(String pattern, boolean isRegex, boolean caseSensitive,
            boolean wholeWord, List<String> includeGlobs, List<String> excludeGlobs) {
        String effective = pattern;
        if (wholeWord) {
            String body = isRegex ? pattern : escapeRegex(pattern);
            effective = "\\b(?:" + body + ")\\b";
        }
        List<String> args = new ArrayList<>();
        args.add("--hidden");
        args.add("--json");
        args.add("--crlf");
        args.add("--no-config");
        args.add(caseSensitive ? "--case-sensitive" : "--ignore-case");
        if (!isRegex && !wholeWord) {
            args.add("--fixed-strings");
        }
        args.add("-e");
        args.add(effective);
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
        args.add(".");
        return args;
    }

    /**
     * 转义正则元字符(VSCode escapeRegExpCharacters 同集:{@code \ { } * + ? | ^ $ . [ ] ( )};
     * rg 用 Rust regex、不支持 {@code \Q..\E},必须逐字符转义)。
     */
    static String escapeRegex(String s) {
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

    // ---- 参数帮助 ----

    /** 逗号分隔 glob 列表 → 去空白剔空(入参可空/缺省)。 */
    private static List<String> splitGlobs(String raw) {
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

    /** 布尔参数(缺省 false;兼容 JSON 布尔与字符串,同 fs.browse 的 includeFiles 模式)。 */
    private static boolean boolParam(RpcContext ctx, String name) {
        return "true".equalsIgnoreCase(ctx.optStrParam(name, "false").trim());
    }

    // ---- 应答 ----

    /**
     * 应答:结果序列化后 ≤单帧内联上限(同 fs.read)直接内联 ok;超限按文件边界切批
     * rpc.data 回传(批项 = 完整文件项,不撕裂;单文件项超 CHUNK 时独占一批),末帧
     * ok 只带汇总(matchCount/truncated/fileCount,同 fs.read 末帧只带 path/size)。
     */
    private void reply(RpcContext ctx, SearchOutcome out) {
        ArrayNode filesArr = Json.arr();
        out.files().values().forEach(filesArr::add);
        ObjectNode full = Json.obj()
                .put("matchCount", out.matchCount())
                .put("truncated", out.truncated());
        full.set("files", filesArr);
        if (Json.write(full).getBytes(StandardCharsets.UTF_8).length <= FsService.INLINE_MAX) {
            ctx.ok(full);
            return;
        }
        List<JsonNode> batch = new ArrayList<>();
        int bytes = 0;
        for (JsonNode f : filesArr) {
            int size = Json.write(f).getBytes(StandardCharsets.UTF_8).length;
            if (!batch.isEmpty() && bytes + size > FsService.CHUNK) {
                ctx.data(List.copyOf(batch), true);
                batch = new ArrayList<>();
                bytes = 0;
            }
            batch.add(f);
            bytes += size;
        }
        ctx.data(List.copyOf(batch), false);
        ctx.ok(Json.obj()
                .put("matchCount", out.matchCount())
                .put("truncated", out.truncated())
                .put("fileCount", filesArr.size()));
    }
}
