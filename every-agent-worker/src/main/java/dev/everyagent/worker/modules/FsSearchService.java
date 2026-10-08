package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.worker.plugin.registry.SearchProviderInvoker;
import dev.everyagent.worker.plugin.registry.SearchProviderRegistry;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 工作区搜索模块(架构 §7 契约表):fs.search 文本内容搜索 + fs.find 文件名搜索,
 * 内置 ripgrep({@link RipgrepBinary},§5.10)在工作区根(或其子目录范围)执行。
 * fs.search 逐行解析 {@code rg --json} 的 JSON lines 为结构化结果;fs.find 用
 * {@code rg --files} 枚举文件路径 + 本侧 basename 正则匹配。
 *
 * <p><b>jailed</b>:workspace 必填,经 {@link WorkspaceManager#resolve}(绝对路径 +
 * realpath + 非系统目录,与 fs.read 同源)落定工作区根;rg 进程 cwd 恒为工作区根,
 * 可选 {@code path}(子目录范围)经 {@link Sandbox#resolveExisting} 校验(realpath +
 * 前缀校验防符号链接逃逸)后作为 rg 的搜索路径参数,缺省恒 {@code .} → rg 只可能
 * 触碰工作区根之下(include/exclude glob 只影响 rg 自身剪枝,不引入越界路径)。
 * 不经 OsSandbox 重沙箱(与 NativeGit 同类的平台受控操作,前端显式触发),由
 * 「cwd 锁定 + 搜索路径 jailed」兜底。
 *
 * <p><b>fs.find</b>(文件名搜索):{@code rg --hidden --files --no-messages} 枚举
 * (不配 --json——--files 下 --json 只吐 summary 不吐路径),basename 匹配在本侧用
 * Java {@link Pattern} 完成(全字包裹/固定串转义与 {@link #buildMatchArgs} 同族
 * 语义);matchCount = 命中文件数,结果项 {path}(无 matches 字段);分批/截断/
 * 超时管道与 fs.search 共用。替代前端逐目录 fs.list 递归 walk(中型仓库数千次
 * 串行 RPC,且单条目不可读即整树报错);rg 遵循 .gitignore、原生跳过不可读条目,
 * 与 VSCode 默认搜索范围对齐。
 *
 * <p><b>结果形状</b>:与前端 workspaceContentSearch 的
 * {@code {matchCount, truncated, files:[{path, matches:[...]}]}} 对齐;单项命中
 * {@code {lineNumber, line, matchIndex, matchText}}(matchIndex/matchText 为本次新增的
 * 增强字段,老形状 must-ignore)。小结果(≤单帧内联上限,同 {@link FsService})直接
 * 内联 ok 返回;超限复用 fs.read 的 rpc.data 分批 + 末帧 ok 汇总(架构 §5.4)。
 *
 * <p><b>流式性(一期)</b>:不边读边推 rpc.data,先把 JSON lines 聚合完再按序列化大小
 * 分批(代码简单;maxResults 默认 1000 兜住聚合内存)。
 *
 * <p><b>SearchProvider 增补聚合(§8.5)</b>:插件经 {@code ctx.registerSearchProvider}
 * 注册的搜索后端不替换内置 rg——fs.search 在内置 rg 结果之后按 order() 升序
 * (同 order 保持注册先后)追加各 provider
 * 结果(按 {@code path+lineNumber+matchIndex} 去重、仍受 maxResults 触顶约束,见
 * {@link #mergeProviderResults});provider 抛异常/超出超时预算仅 WARN 跳过(超时预算
 * {@code worker.search.provider-timeout-ms},默认 0 不限时,见
 * {@link #PROVIDER_TIMEOUT_MS});注册表为空时零额外行为;rg 不可用但注册了 provider
 * 时跳过内置 rg、仅聚合 provider 结果。
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
    private final SearchProviderRegistry searchProviders;

    /**
     * SearchProvider 单 provider 超时预算缺省(ms):0 = 不限时(仅异常护栏,与机制引入
     * 前的行为一致)。配置键 {@code worker.search.provider-timeout-ms} 由后续配置装配
     * 步骤接入 yml,本步以内部常量 + 可注入字段承载(见 {@link #providerTimeoutMs})。
     */
    static final long PROVIDER_TIMEOUT_MS = 0;

    /** 当前生效的 provider 超时预算(ms);缺省 {@link #PROVIDER_TIMEOUT_MS}。 */
    private long providerTimeoutMs = PROVIDER_TIMEOUT_MS;

    /**
     * 包级可见:注入 provider 超时预算(单测设小值验证预算机制;后续配置装配步骤接线)。
     * ≤ 0 恢复不限时(同步直调,仅异常护栏)。
     */
    void setProviderTimeoutMs(long providerTimeoutMs) {
        this.providerTimeoutMs = providerTimeoutMs;
    }

    public FsSearchService(RpcDispatcher dispatcher, WorkspaceManager workspaces, RipgrepBinary rg,
            SearchProviderRegistry searchProviders) {
        this.workspaces = workspaces;
        this.rg = rg;
        this.searchProviders = searchProviders;
        dispatcher.register(RpcMethods.FS_SEARCH, this::search);
        dispatcher.register(RpcMethods.FS_FIND, this::find);
    }

    // ---- RPC 入口 ----

    private void search(RpcContext ctx) throws IOException, InterruptedException {
        boolean providersRegistered = !searchProviders.getProviders().isEmpty();
        if (!rg.available() && !providersRegistered) {
            throw new IOException("rg 不可用: 未找到内置 ripgrep(<程序根>/runtime/bin/ 或"
                    + " worker.tools.rg-path),无法执行工作区搜索");
        }
        // jailed:workspace 必填,resolve 内完成绝对路径 + realpath + 非系统目录校验(与 fs.read 同源)
        Sandbox sb = new Sandbox(workspaces.resolve(ctx.strParam("workspace")));
        Path root = sb.root();
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
        // 内置 rg 搜索(rg 不可用但已注册 SearchProvider 时跳过,仅聚合 provider 结果,§8.5)
        SearchOutcome out = rg.available()
                ? run(root, buildArgs(pattern, isRegex, caseSensitive, wholeWord, include, exclude,
                        resolveScope(ctx, sb)), maxResults)
                : new SearchOutcome(new LinkedHashMap<>(), 0, false);
        out = mergeProviderResults(out, searchProviders, new SearchProvider.SearchRequest(
                workspaces.idOfRoot(root.toString()), root, pattern, isRegex, caseSensitive,
                wholeWord, include, exclude, maxResults), maxResults, providerTimeoutMs);
        reply(ctx, out);
    }

    /**
     * fs.find:文件名搜索(架构 §7 契约表)。参数与 fs.search 同族;basename 匹配在
     * worker 侧完成(Java Pattern,非 rg),pattern 非法在入口即拦成可读的 rpc.err。
     */
    private void find(RpcContext ctx) throws IOException, InterruptedException {
        if (!rg.available()) {
            throw new IOException("rg 不可用: 未找到内置 ripgrep(<程序根>/runtime/bin/ 或"
                    + " worker.tools.rg-path),无法执行工作区搜索");
        }
        Sandbox sb = new Sandbox(workspaces.resolve(ctx.strParam("workspace")));
        Path root = sb.root();
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
        Pattern nameRegex;
        try {
            nameRegex = compileNamePattern(pattern, isRegex, caseSensitive, wholeWord);
        } catch (PatternSyntaxException e) {
            throw new BadParamsException("正则表达式非法: " + e.getMessage());
        }
        SearchOutcome out = runFind(root,
                buildFileArgs(include, exclude, resolveScope(ctx, sb)), nameRegex, maxResults);
        reply(ctx, out);
    }

    // ---- rg 执行与聚合 ----

    /** 聚合完成态搜索结果(files 按文件聚合、保持 rg 输出顺序;matchCount = 实际聚合条数)。 */
    record SearchOutcome(LinkedHashMap<String, ObjectNode> files, int matchCount, boolean truncated) {
    }

    /**
     * fs.search 的 rg 执行与聚合:逐行解析 JSON lines 的 match 记录(非 match 记录跳过),
     * 按文件聚合、保持 rg 输出顺序;matchCount = 实际聚合命中条数。kill 时机
     * (触顶/超时/异常)由 {@link #execRg} 统一承担,退出码收尾见 {@link #finishTruncated}。
     */
    private SearchOutcome run(Path root, List<String> args, int maxResults)
            throws IOException, InterruptedException {
        LinkedHashMap<String, ObjectNode> files = new LinkedHashMap<>();
        int[] count = {0};
        StreamOutcome r = execRg(root, args, line -> {
            RawHit hit = parseMatchLine(line);
            if (hit == null) {
                return true; // begin/end/summary 等非 match 记录
            }
            append(files, relPath(root, hit.rawPath()), hit);
            return ++count[0] < maxResults; // 计数触顶即停止消费(不用 --max-count)
        });
        boolean truncated = finishTruncated(r, count[0], "fs.search");
        return new SearchOutcome(files, count[0], truncated);
    }

    /**
     * fs.find 的 rg 执行与聚合:逐行消费 {@code rg --files} 的路径输出(相对 cwd 的
     * 原始形态,经 {@link #relPath} 归一为工作区相对 posix),basename 命中即记一个
     * 文件项({@code {path}},无 matches 字段);matchCount = 命中文件数。退出码语义与
     * fs.search 同款(--files 下 0 = 有文件、1 = 零文件,均正常;2 = 部分不可读)。
     */
    private SearchOutcome runFind(Path root, List<String> args, Pattern nameRegex, int maxResults)
            throws IOException, InterruptedException {
        LinkedHashMap<String, ObjectNode> files = new LinkedHashMap<>();
        StreamOutcome r = execRg(root, args, line -> {
            String rel = relPath(root, line);
            String base = rel.substring(rel.lastIndexOf('/') + 1);
            if (!nameRegex.matcher(base).find()) {
                return true;
            }
            files.put(rel, Json.obj().put("path", rel));
            return files.size() < maxResults;
        });
        boolean truncated = finishTruncated(r, files.size(), "fs.find");
        return new SearchOutcome(files, files.size(), truncated);
    }

    // ---- SearchProvider 增补聚合 ----

    /**
     * 把插件 SearchProvider 的结果增补聚合进内置 rg 结果(§8.5):注册表为空或内置结果已
     * 触顶(maxResults)时原样返回——零行为变化;provider 结果按 order() 升序
     * (同 order 保持注册先后)追加在内置结果之后,
     * 按位置键 {@code path+lineNumber+matchIndex} 去重(多引擎命中同一位置只计一条);
     * 合并后仍受 maxResults 触顶约束(触顶置 truncated 并终止 provider 循环);单个
     * provider 抛异常/超出超时预算({@code providerTimeoutMs},0 不限时)仅 WARN 跳过
     * (经 {@link SearchProviderInvoker} 护栏),不影响其余结果与应答;provider 返回
     * null/空列表不加项。
     */
    static SearchOutcome mergeProviderResults(SearchOutcome builtIn, SearchProviderRegistry registry,
            SearchProvider.SearchRequest req, int maxResults, long providerTimeoutMs) {
        List<SearchProvider> providers = registry.getProviders();
        if (providers.isEmpty() || builtIn.matchCount() >= maxResults) {
            return builtIn;
        }
        LinkedHashMap<String, ObjectNode> files = new LinkedHashMap<>(builtIn.files());
        Set<String> seen = new HashSet<>();
        for (ObjectNode file : files.values()) {
            for (JsonNode m : file.path("matches")) {
                seen.add(file.path("path").asString() + "\u0000" + m.path("lineNumber").asInt()
                        + "\u0000" + m.path("matchIndex").asInt());
            }
        }
        int count = builtIn.matchCount();
        boolean truncated = builtIn.truncated();
        // 护栏(§8.5):单个 provider 抛异常/超出超时预算仅 WARN 跳过,不影响其余结果
        SearchProviderInvoker invoker = new SearchProviderInvoker("fs.search", providerTimeoutMs);
        outer:
        for (SearchProvider provider : providers) {
            List<SearchProvider.SearchResult> hits = invoker.invoke(provider,
                    () -> provider.searchFiles(req));
            if (hits == null) {
                continue; // 护栏跳过(异常/超时)或 provider 合法返回 null
            }
            for (SearchProvider.SearchResult hit : hits) {
                if (count >= maxResults) {
                    truncated = true;
                    break outer;
                }
                if (!seen.add(hit.path() + "\u0000" + hit.lineNumber() + "\u0000" + hit.matchIndex())) {
                    continue;
                }
                appendProviderHit(files, hit);
                count++;
            }
        }
        return new SearchOutcome(files, count, truncated);
    }

    /** provider 命中追加进 files 聚合(同文件命中并入同一文件项,形状与 {@link #append} 一致)。 */
    private static void appendProviderHit(LinkedHashMap<String, ObjectNode> files,
            SearchProvider.SearchResult hit) {
        ObjectNode file = files.get(hit.path());
        if (file == null) {
            file = Json.obj().put("path", hit.path());
            file.set("matches", Json.arr());
            files.put(hit.path(), file);
        }
        ((ArrayNode) file.get("matches")).add(Json.obj()
                .put("lineNumber", hit.lineNumber())
                .put("line", hit.line())
                .put("matchIndex", hit.matchIndex())
                .put("matchText", hit.matchText()));
    }

    /** execRg 的逐行消费者:返回 true 继续消费,false = 触顶停止(外层 kill rg)。 */
    @FunctionalInterface
    interface LineHandler {
        boolean onLine(String line);
    }

    /** execRg 的执行侧收尾态(exit = -1 表示触顶/超时被杀,退出码无意义)。 */
    record StreamOutcome(boolean truncated, boolean timedOut, int exit, String errText) {
    }

    /**
     * 执行 rg 并逐行消费 stdout(fs.search 与 fs.find 共用的进程管理管道):
     * <ul>
     *   <li>stderr 并发抽干:rg 只写不读会写满管道缓冲卡死进程(与 OsSandbox 同款教训),
     *       单写线程 + join 后读取,无并发写;</li>
     *   <li>超时({@link #TIMEOUT_MS})看门狗线程强杀 → stdout 管道 EOF → 消费循环自然收尾,
     *       返回已完成部分并置 truncated(readLine 阻塞期间无法检查 deadline,故须独立线程);</li>
     *   <li>消费者返回 false(解析端计数触顶)立即停止消费、强杀 rg(rg 不再扫盘,
     *       不用 --max-count,由本侧计数控制);</li>
     *   <li>finally 强杀:已退出的进程是 no-op;触顶/超时/异常(含 rpc.cancel 打断)路径
     *       绝不留孤儿 rg。</li>
     * </ul>
     * 正常读完时阻塞等待退出码;被杀路径 exit 记 -1。errText 为抽干的 stderr 文本。
     */
    private StreamOutcome execRg(Path root, List<String> args, LineHandler handler)
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
                if (!p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
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
     * 退出码/超时收尾(fs.search 与 fs.find 共用):超时被杀或触顶 → truncated;
     * rg 异常退出码(非 0/1)时无结果抛 IOException(rpc.err),已有结果告警并置
     * truncated(返回已完成部分,对标 rg 对不可读条目继续输出的行为)。
     */
    private boolean finishTruncated(StreamOutcome r, int count, String tag) throws IOException {
        if (r.timedOut()) {
            log.warn("[{}] rg 超时(>{}ms)已强杀,返回已完成部分({} 项)", tag, TIMEOUT_MS, count);
            return true;
        }
        if (r.truncated()) {
            return true; // 触顶 kill:退出码无意义
        }
        int exit = r.exit();
        if (exit != EXIT_NO_MATCH && exit != EXIT_MATCH) {
            if (count == 0) {
                throw new IOException("rg 搜索失败(exit=" + exit + "): " + r.errText());
            }
            log.warn("[{}] rg 异常退出(exit={}),返回已聚合结果并标记截断: {}", tag, exit, r.errText());
            return true;
        }
        return false;
    }

    /** rg --json 单条 match 记录的解析结果(路径保持 rg 原始形态,聚合时归一)。 */
    public record RawHit(String rawPath, int lineNumber, String line, int matchIndex, String matchText) {
    }

    /**
     * 解析 rg --json 的一行:{@code type=match} 记录取出 data.path.text /
     * line_number / lines.text(剥尾部换行,--crlf 下 CRLF 文件可能残留 \r)/
     * submatches[0] 的 start(行内偏移,0-based)与 match.text;非 match 记录
     * (begin/end/summary)返回 null。
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
     *   <li>基础:{@code --hidden --json --crlf --no-config},搜索路径参数由调用方传入
     *       (缺省 {@code .} = 整根;fs.search 的可选 {@code path} 范围,cwd 恒为工作区根,
     *       jailed 的执行半边);</li>
     *   <li>大小写:不敏感(缺省)加 {@code --ignore-case},敏感加 {@code --case-sensitive};</li>
     *   <li>固定串(isRegex=false 且非全字):pattern <b>原样</b> + {@code --fixed-strings}
     *       ——rg -F 是纯字节字面量匹配、不做反转义,转义与 -F 并用会让含元字符的搜索词
     *       (如 {@code C++}、{@code foo.bar})失效;</li>
     *   <li>全字:不用 -w,由本侧包 {@code \b(?:...)\b} 后按正则传(包裹后必为正则模式,
     *       不能再加 --fixed-strings,否则 \b 会被当字面量);固定串先转义正则元字符再包裹,
     *       保持字面量语义;</li>
     *   <li>include:非空时先 {@code -g !*} 全拒再逐 glob 放行(VSCode 拼法,rg 剪枝最
     *       有效);exclude:逐 {@code -g !<glob>};</li>
     *   <li>不用 --max-count:由解析端计数触顶 kill(见 {@link #execRg})。</li>
     * </ul>
     */
    static List<String> buildArgs(String pattern, boolean isRegex, boolean caseSensitive,
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
     * fs.search 与 task.search 共用,保证两类搜索的 pattern 语义一致;不含 {@code --hidden}/
     * glob/搜索路径(由调用方按各自目标补齐)。
     * <ul>
     *   <li>大小写:不敏感(缺省)加 {@code --ignore-case},敏感加 {@code --case-sensitive};</li>
     *   <li>固定串(isRegex=false 且非全字):pattern <b>原样</b> + {@code --fixed-strings}
     *       ——rg -F 是纯字节字面量匹配、不做反转义,转义与 -F 并用会让含元字符的搜索词
     *       (如 {@code C++}、{@code foo.bar})失效;</li>
     *   <li>全字:不用 -w,由本侧包 {@code \b(?:...)\b} 后按正则传(包裹后必为正则模式,
     *       不能再加 --fixed-strings,否则 \b 会被当字面量);固定串先转义正则元字符再包裹,
     *       保持字面量语义。</li>
     * </ul>
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
     * fs.find 的 rg argv:{@code --hidden --files --no-messages} 枚举文件路径。
     * <b>不配 {@code --json}</b>——{@code --files} 下 {@code --json} 只吐 summary 不吐
     * 路径(实测 ripgrep 行为);pattern 不进 argv(basename 匹配在本侧 Java 正则完成,
     * 见 {@link #compileNamePattern});include/exclude glob 拼法与 {@link #buildArgs}
     * 共用;搜索路径参数语义同 fs.search(缺省 {@code .})。
     */
    static List<String> buildFileArgs(List<String> includeGlobs, List<String> excludeGlobs, String searchPath) {
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
     * fs.find 的 basename 匹配正则:语义与 {@link #buildMatchArgs} 同族——全字包
     * {@code \b(?:...)\b}、固定串先转义正则元字符({@link #escapeRegex} 同集);方言为
     * Java {@link Pattern}(basename 在本侧匹配、不过 rg),大小写用
     * {@link Pattern#CASE_INSENSITIVE} 等价 rg 的 {@code --ignore-case} 缺省语义。
     * 调用方用 {@code find()}(非锚定搜索)匹配,对齐前端 {@code RegExp.test} 语义。
     */
    static Pattern compileNamePattern(String pattern, boolean isRegex, boolean caseSensitive,
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

    /**
     * 解析可选搜索范围参数 {@code path}(fs.search/fs.find 共用):缺省/空/{@code .} =
     * 整根;否则经 {@link Sandbox#resolveExisting}(存在性 + realpath + 前缀校验)jailed
     * 在工作区根内,返回工作区相对 posix 路径作为 rg 的搜索路径参数(根本身归一为
     * {@code .})。非目录拒收。
     */
    private static String resolveScope(RpcContext ctx, Sandbox sb) throws IOException {
        String raw = ctx.optStrParam("path", "");
        if (raw == null || raw.isBlank() || ".".equals(raw.trim())) {
            return ".";
        }
        Path resolved = sb.resolveExisting(raw.trim());
        if (!Files.isDirectory(resolved)) {
            throw new BadParamsException("搜索范围不是目录: " + raw);
        }
        return sb.display(resolved);
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
