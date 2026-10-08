package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.modules.FsSearchService;
import dev.everyagent.worker.modules.FsService;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.worker.plugin.registry.SearchProviderInvoker;
import dev.everyagent.worker.plugin.registry.SearchProviderRegistry;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.tools.RipgrepBinary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 任务内容搜索(task.search):在任务数据目录内复用内置 ripgrep({@link RipgrepBinary})
 * 搜索轮次索引文件 rounds.jsonl,再由 worker 做「JSON 转义消除」后处理返回前端。
 *
 * <p><b>数据源</b>:任务按工作区归类落盘 workspaces/&lt;workspaceId&gt;/tasks/&lt;taskId&gt;/
 * (架构 §5.9);本次只搜索 rounds 轮文件(每行一轮,含 user/finalReply 正文),不搜
 * &lt;agentId&gt;.jsonl 事件日志(内容与轮次重复且含大量过程性工具调用噪音)。
 *
 * <p><b>为什么直接 rg 而不再逐行解析全量</b>:rg 只扫 rounds.jsonl 单文件、命中行再解析,
 * 天然轻量;但 rg 命中的是 JSON 原始行(字段名/引号/转义符都会被搜到,如搜 {@code finalReply}
 * 会命中每一行),故 worker 对每条命中行:
 * <ol>
 *   <li>{@link TaskStore#parseRoundLine} 解析出行内轮次,抽取干净文本 user/finalReply;</li>
 *   <li>用同一 pattern 在干净文本上二次匹配,得到准确 matchIndex/matchText(前端高亮定位);
 *       干净文本不命中(纯命中 JSON 字段名/语法)则整条丢弃——消除转义噪音。</li>
 * </ol>
 *
 * <p><b>匹配语义</b>:与 fs.search 共用 {@link FsSearchService#buildMatchArgs}
 * (固定串原样 --fixed-strings / 全字 \b 包裹 / ignore-case),保证两种搜索 pattern 行为一致。
 *
 * <p><b>应答</b>:与 fs.search 同构 {matchCount, truncated, files},files 项为任务级
 * {taskId,title,workspace,workspaceId,status,matches:[{roundIndex,field,line,matchIndex,matchText}]};
 * 大结果复用 rpc.data 分批 + 末帧 ok 汇总。
 *
 * <p><b>SearchProvider 增补聚合(§8.5)</b>:插件经 {@code ctx.registerSearchProvider}
 * 注册的搜索后端不替换内置 rg——本方法在内置 rg 结果之后按 order() 升序
 * (同 order 保持注册先后)追加各 provider 的
 * {@code searchTasks} 结果(按 {@code taskId+roundIndex+field+matchIndex} 去重、仍受
 * maxResults 触顶约束,见 {@link #mergeProviderResults});provider 抛异常/超出超时预算
 * 仅 WARN 跳过(超时预算 {@code worker.search.provider-timeout-ms},默认 0 不限时,见
 * {@link #PROVIDER_TIMEOUT_MS});注册表为空时零额外行为;rg 不可用但注册了 provider 时
 * 跳过内置 rg、仅聚合 provider 结果。
 */
@Component
public class TaskSearchService {

    private static final Logger log = LoggerFactory.getLogger(TaskSearchService.class);

    /** rg 进程超时(ms):超时强杀,返回已完成部分并置 truncated=true(与 fs.search 同款)。 */
    private static final long TIMEOUT_MS = 60_000;

    /** 触顶截断的默认命中上限(maxResults 缺省值;任务内容搜索默认比 fs.search 小)。 */
    private static final int DEFAULT_MAX_RESULTS = 500;

    /** rg 正常退出码:0 = 无匹配、1 = 有匹配;其余(2 等)为错误。 */
    private static final int EXIT_NO_MATCH = 0;
    private static final int EXIT_MATCH = 1;

    /** 子进程 stdin 的 null 设备(命令 stdin 契约 §7.10,与 FsSearchService 同款)。 */
    private static final java.io.File NULL_INPUT = new java.io.File(
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                    ? "NUL" : "/dev/null");

    private final TaskStore store;
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

    public TaskSearchService(RpcDispatcher dispatcher, TaskStore store, RipgrepBinary rg,
            SearchProviderRegistry searchProviders) {
        this.store = store;
        this.rg = rg;
        this.searchProviders = searchProviders;
        dispatcher.register(RpcMethods.TASK_SEARCH, this::search);
    }

    // ---- RPC 入口 ----

    private void search(RpcContext ctx) throws IOException, InterruptedException {
        // 参数先校验(不依赖 rg 可用性,非法请求稳定拒绝):
        // workspaceId 必填(前端恒传当前选中工作区稳定 id;拒绝缺省全量扫描)且必须是注册表
        // 稳定 id 形态(defaultworkspace / w_xxxxx)——RPC 参数可控,直接拼路径前先拦穿越形态
        // (.. / 路径分隔符 / 绝对路径),目录层另有 TaskStore.scanWorkspace 兜底防御。
        String workspaceId = ctx.strParam("workspaceId");
        if (!isValidWorkspaceId(workspaceId)) {
            throw new BadParamsException("workspaceId 非法: " + workspaceId);
        }
        String pattern = ctx.strParam("pattern");
        if (pattern.isBlank()) {
            throw new BadParamsException("pattern 不能为空");
        }
        if (!rg.available() && searchProviders.getProviders().isEmpty()) {
            throw new IOException("rg 不可用: 未找到内置 ripgrep(<程序根>/runtime/bin/ 或"
                    + " worker.tools.rg-path),无法执行任务搜索");
        }
        boolean isRegex = boolParam(ctx, "isRegex");
        boolean caseSensitive = boolParam(ctx, "caseSensitive");
        boolean wholeWord = boolParam(ctx, "wholeWord");
        long rawMax = ctx.optLongParam("maxResults", DEFAULT_MAX_RESULTS);
        if (rawMax < 1) {
            throw new BadParamsException("maxResults 必须 ≥ 1: " + rawMax);
        }
        int maxResults = (int) Math.min(rawMax, Integer.MAX_VALUE);
        // 正则模式先本地试编译:非法正则(未闭合括号等)在入口即拦成可读的 rpc.err。
        Pattern pat;
        try {
            pat = compileSearchPattern(pattern, isRegex, caseSensitive, wholeWord);
        } catch (PatternSyntaxException e) {
            throw new BadParamsException("正则表达式非法: " + e.getMessage());
        }
        // 枚举任务:按 workspaceId 直接定位该工作区任务根(rg 不可用但已注册 SearchProvider
        // 时跳过内置搜索,仅聚合 provider 结果,§8.5)。
        SearchOutcome out;
        if (rg.available()) {
            List<TaskStore.StoredTask> tasks = store.scanWorkspace(workspaceId);
            out = searchTasks(tasks, pattern, isRegex, caseSensitive, wholeWord, pat, maxResults);
        } else {
            out = new SearchOutcome(new LinkedHashMap<>(), 0, false);
        }
        out = mergeProviderResults(out, searchProviders, new SearchProvider.TaskSearchRequest(
                workspaceId, pattern, isRegex, caseSensitive, wholeWord, maxResults), maxResults,
                providerTimeoutMs);
        reply(ctx, out);
    }

    // ---- 聚合 ---- 

    /** 聚合完成态搜索结果(files 按任务聚合、保持枚举顺序;matchCount = 实际聚合条数)。 */
    record SearchOutcome(LinkedHashMap<String, ObjectNode> files, int matchCount, boolean truncated) {
    }

    /** 单任务 rg 结果。 */
    record TaskRunOutcome(List<ObjectNode> matches, boolean truncated) {
    }

    /**
     * 逐任务搜索 rounds.jsonl:跳过无 rounds 文件的任务;每任务命中达到全局剩余上限即触顶。
     * rg 输出按行解析 → worker 后处理(解析 JSON + 干净文本二次匹配) → 任务级聚合。
     */
    private SearchOutcome searchTasks(List<TaskStore.StoredTask> tasks, String pattern,
            boolean isRegex, boolean caseSensitive, boolean wholeWord, Pattern pat, int maxResults)
            throws IOException, InterruptedException {
        List<String> matchArgs = FsSearchService.buildMatchArgs(
                pattern, isRegex, caseSensitive, wholeWord);
        LinkedHashMap<String, ObjectNode> files = new LinkedHashMap<>();
        int count = 0;
        boolean truncated = false;
        for (TaskStore.StoredTask st : tasks) {
            if (count >= maxResults) {
                truncated = true;
                break;
            }
            if (!Files.isRegularFile(st.dir().resolve("rounds.jsonl"))) {
                continue; // 极老任务无轮次索引:不参与搜索
            }
            List<ObjectNode> matches = new ArrayList<>();
            TaskRunOutcome out = runRg(st.dir(), matchArgs, pat, maxResults - count, matches);
            if (out.truncated()) {
                truncated = true;
            }
            if (!matches.isEmpty()) {
                ObjectNode taskNode = Json.obj()
                        .put("taskId", st.taskId())
                        .put("title", st.summary().path("title").asString("任务 " + st.taskId()))
                        .put("workspace", st.summary().path("workspace").asString(""))
                        .put("workspaceId", st.workspaceId() == null ? "" : st.workspaceId())
                        .put("status", st.summary().path("status").asString(""));
                ArrayNode arr = Json.arr();
                for (ObjectNode m : matches) {
                    arr.add(m);
                }
                taskNode.set("matches", arr);
                files.put(st.taskId(), taskNode);
                count += matches.size();
            }
            if (out.truncated()) {
                break; // 本任务已触顶,不再枚举后续任务
            }
        }
        if (count > maxResults) {
            count = maxResults;
        }
        return new SearchOutcome(files, count, truncated);
    }

    // ---- SearchProvider 增补聚合 ----

    /**
     * 把插件 SearchProvider 的任务搜索结果增补聚合进内置 rg 结果(§8.5):注册表为空或
     * 内置结果已触顶(maxResults)时原样返回——零行为变化;provider 结果按 order() 升序
     * (同 order 保持注册先后)追加在
     * 内置结果之后,按位置键 {@code taskId+roundIndex+field+matchIndex} 去重(多引擎命中
     * 同一轮同一字段同一位置只计一条);合并后仍受 maxResults 触顶约束(触顶置 truncated
     * 并终止 provider 循环);单个 provider 抛异常/超出超时预算({@code providerTimeoutMs},
     * 0 不限时)仅 WARN 跳过(经 {@link SearchProviderInvoker} 护栏),不影响其余结果与
     * 应答;provider 返回 null/空列表(或全部命中被去重)不加任务项。
     */
    static SearchOutcome mergeProviderResults(SearchOutcome builtIn, SearchProviderRegistry registry,
            SearchProvider.TaskSearchRequest req, int maxResults, long providerTimeoutMs) {
        List<SearchProvider> providers = registry.getProviders();
        if (providers.isEmpty() || builtIn.matchCount() >= maxResults) {
            return builtIn;
        }
        LinkedHashMap<String, ObjectNode> files = new LinkedHashMap<>(builtIn.files());
        Set<String> seen = new HashSet<>();
        for (ObjectNode task : files.values()) {
            String taskId = task.path("taskId").asString();
            for (JsonNode m : task.path("matches")) {
                seen.add(taskId + "\u0000" + m.path("roundIndex").asInt() + "\u0000"
                        + m.path("field").asString() + "\u0000" + m.path("matchIndex").asInt());
            }
        }
        int count = builtIn.matchCount();
        boolean truncated = builtIn.truncated();
        // 护栏(§8.5):单个 provider 抛异常/超出超时预算仅 WARN 跳过,不影响其余结果
        SearchProviderInvoker invoker = new SearchProviderInvoker("task.search", providerTimeoutMs);
        outer:
        for (SearchProvider provider : providers) {
            List<SearchProvider.TaskSearchResult> hits = invoker.invoke(provider,
                    () -> provider.searchTasks(req));
            if (hits == null) {
                continue; // 护栏跳过(异常/超时)或 provider 合法返回 null
            }
            for (SearchProvider.TaskSearchResult hit : hits) {
                if (hit.matches() == null || hit.matches().isEmpty()) {
                    continue;
                }
                // 先筛本任务可追加的命中(全被去重的任务不产出空任务项),再惰性建任务项
                List<SearchProvider.TaskSearchResult.Match> fresh = new ArrayList<>();
                boolean capped = false;
                for (SearchProvider.TaskSearchResult.Match m : hit.matches()) {
                    if (count + fresh.size() >= maxResults) {
                        capped = true; // 全局预算耗尽,本任务命中未全量消费
                        break;
                    }
                    if (seen.add(hit.taskId() + "\u0000" + m.roundIndex() + "\u0000" + m.field()
                            + "\u0000" + m.matchIndex())) {
                        fresh.add(m);
                    }
                }
                if (!fresh.isEmpty()) {
                    appendProviderTaskHits(files, hit, fresh);
                    count += fresh.size();
                }
                if (capped) {
                    truncated = true;
                    break outer;
                }
            }
        }
        return new SearchOutcome(files, Math.min(count, maxResults), truncated);
    }

    /** 把一个 provider 任务结果的(去重后)命中追加进 files 聚合,任务项不存在则按元数据建。 */
    private static void appendProviderTaskHits(LinkedHashMap<String, ObjectNode> files,
            SearchProvider.TaskSearchResult hit, List<SearchProvider.TaskSearchResult.Match> fresh) {
        ObjectNode task = files.get(hit.taskId());
        if (task == null) {
            task = Json.obj()
                    .put("taskId", hit.taskId())
                    .put("title", hit.title() == null ? "" : hit.title())
                    .put("workspace", hit.workspace() == null ? "" : hit.workspace())
                    .put("workspaceId", hit.workspaceId() == null ? "" : hit.workspaceId())
                    .put("status", hit.status() == null ? "" : hit.status());
            task.set("matches", Json.arr());
            files.put(hit.taskId(), task);
        }
        ArrayNode arr = (ArrayNode) task.get("matches");
        for (SearchProvider.TaskSearchResult.Match m : fresh) {
            arr.add(Json.obj()
                    .put("roundIndex", m.roundIndex())
                    .put("field", m.field())
                    .put("line", m.line())
                    .put("matchIndex", m.matchIndex())
                    .put("matchText", m.matchText()));
        }
    }

    /**
     * 对单个任务目录跑 rg(搜索路径恒 rounds.jsonl,cwd = 任务目录,jailed 于任务数据目录):
     * 与 FsSearchService.run 同款进程管理(stderr 抽干 / 超时 watchdog 强杀 / 触顶即 kill /
     * 退出码校验);差异在行处理:命中行解析为 Round 后对 user/finalReply 干净文本二次匹配。
     */
    private TaskRunOutcome runRg(Path taskDir, List<String> matchArgs, Pattern pat,
            int remaining, List<ObjectNode> matches) throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>();
        argv.add(rg.path().toString());
        argv.addAll(matchArgs);
        argv.add("rounds.jsonl");
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(taskDir.toFile());
        pb.redirectInput(ProcessBuilder.Redirect.from(NULL_INPUT));

        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("rg 启动失败: " + e.getMessage(), e);
        }

        // stderr 并发抽干(rg 只写不读会写满管道缓冲卡死进程,与 FsSearchService 同款教训)。
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
        // 超时看门狗:readLine 阻塞期间无法检查 deadline,由独立线程超时后强杀进程 → EOF 收尾。
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
                FsSearchService.RawHit hit = FsSearchService.parseMatchLine(raw);
                if (hit == null) {
                    continue; // begin/end/summary 等非 match 记录
                }
                // worker 后处理:解析 JSON 行 → 轮次;抽取 user/finalReply 干净文本二次匹配,
                // 命中字段名/JSON 语法(干净文本不命中)的噪音条目在此被丢弃。
                RoundIndex.Round round = TaskStore.parseRoundLine(hit.line());
                if (round == null) {
                    continue; // 撕行/坏行:静默跳过
                }
                appendFieldHits(matches, round, pat, hit.lineNumber());
                if (matches.size() >= remaining) {
                    truncated = true;
                    // 一行 user+finalReply 可能一次加 2 条:截断到 remaining,
                    // 保证单任务命中数不越界(否则 matchCount 与 files 内条数不一致)。
                    if (matches.size() > remaining) {
                        matches.subList(remaining, matches.size()).clear();
                    }
                    break; // 触顶:不再消费输出,交 finally kill
                }
            }
        } finally {
            // 已退出的进程是 no-op;触顶/超时/异常路径绝不留孤儿 rg
            p.destroyForcibly();
        }
        watchdog.interrupt();

        if (!truncated && readerEof && !timedOut.get()) {
            // 正常读完:校验退出码(0 无匹配 / 1 有匹配均正常)
            int exit = p.waitFor();
            if (exit != EXIT_NO_MATCH && exit != EXIT_MATCH) {
                errDrain.join(2_000);
                String errText = errBuf.toString(StandardCharsets.UTF_8).trim();
                if (matches.isEmpty()) {
                    throw new IOException("rg 搜索失败(exit=" + exit + "): " + errText);
                }
                log.warn("[task.search] rg 异常退出(exit={}),返回已聚合结果并标记截断: {}",
                        exit, errText);
                truncated = true;
            }
        } else if (timedOut.get()) {
            truncated = true;
            log.warn("[task.search] rg 超时(>{}ms)已强杀,返回已完成部分({} 项)", TIMEOUT_MS, matches.size());
        } else {
            // 触顶 kill:稍候进程收尾即可(退出码无意义)
            p.waitFor(2, TimeUnit.SECONDS);
        }
        return new TaskRunOutcome(matches, truncated);
    }

    /**
     * 对一轮的 user / finalReply 分别做干净文本二次匹配;roundIndex 优先取轮内 index 字段,
     * 缺失/异常时回退 rg 物理行号(rounds 每行一轮,物理行号 ≈ 轮序号)。
     */
    private static void appendFieldHits(List<ObjectNode> matches, RoundIndex.Round round,
            Pattern pat, long fallbackRoundIndex) {
        appendFieldHit(matches, round, pat, "user", round.user(), fallbackRoundIndex);
        appendFieldHit(matches, round, pat, "finalReply", round.finalReply(), fallbackRoundIndex);
    }

    /** 单字段二次匹配:干净文本命中才产出命中条目(消除 JSON 字段名/转义噪音)。 */
    private static void appendFieldHit(List<ObjectNode> matches, RoundIndex.Round round,
            Pattern pat, String field, String text, long fallbackRoundIndex) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher m = pat.matcher(text);
        if (!m.find()) {
            return;
        }
        matches.add(Json.obj()
                .put("roundIndex", round.index() > 0 ? round.index() : fallbackRoundIndex)
                .put("field", field)
                .put("line", text)
                .put("matchIndex", m.start())
                .put("matchText", m.group()));
    }

    /**
     * 编译二次匹配用的 Java Pattern(与 rg 的 pattern 语义对齐:固定串转义 / 全字包裹 /
     * 大小写敏感标志)。不加 DOTALL:rg 单行搜索不跨行,Java Pattern 默认 '.' 也不匹配
     * 换行,两者行为一致(JSON 转义还原后的真换行不会被 '.' 跨过)。
     */
    static Pattern compileSearchPattern(String pattern, boolean isRegex, boolean caseSensitive,
            boolean wholeWord) {
        String source = isRegex ? pattern : FsSearchService.escapeRegex(pattern);
        if (wholeWord) {
            source = "\\b(?:" + source + ")\\b";
        }
        return Pattern.compile(source, (caseSensitive ? 0 : Pattern.CASE_INSENSITIVE));
    }

    // ---- 参数帮助 ----

    /** 布尔参数(缺省 false;兼容 JSON 布尔与字符串,同 fs.search)。 */
    private static boolean boolParam(RpcContext ctx, String name) {
        return "true".equalsIgnoreCase(ctx.optStrParam(name, "false").trim());
    }

    /**
     * workspaceId 合法性:必须是注册表稳定 id 形态(defaultworkspace / w_xxxxx)。
     * 拒绝空串、路径分隔符、`.`/`..`/绝对路径等穿越形态——RPC 参数可控,拼路径前必须先验。
     */
    private static boolean isValidWorkspaceId(String id) {
        return id != null && !id.isBlank() && id.matches("[A-Za-z0-9][A-Za-z0-9_-]*");
    }

    // ---- 应答 ----

    /**
     * 应答:结果序列化后 ≤单帧内联上限(同 fs.search)直接内联 ok;超限按任务边界切批
     * rpc.data 回传(批项 = 完整任务项,不撕裂),末帧 ok 只带汇总。
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
