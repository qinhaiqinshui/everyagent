package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.BadParamsException;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.task.RoundIndex;
import dev.everyagent.worker.task.TaskStore;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    public TaskSearchService(RpcDispatcher dispatcher, TaskStore store, RipgrepBinary rg) {
        this.store = store;
        this.rg = rg;
        dispatcher.register(RpcMethods.TASK_SEARCH, this::search);
    }

    // ---- RPC 入口 ----

    private void search(RpcContext ctx) throws IOException, InterruptedException {
        if (!rg.available()) {
            throw new IOException("rg 不可用: 未找到内置 ripgrep(<程序根>/runtime/bin/ 或"
                    + " worker.tools.rg-path),无法执行任务搜索");
        }
        String workspaceId = ctx.optStrParam("workspaceId", "");
        String pattern = ctx.strParam("pattern");
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
        // 枚举任务:按 workspaceId 直接定位该工作区任务根;缺省 = 全部工作区(防呆,前端恒传)。
        List<TaskStore.StoredTask> tasks = workspaceId.isBlank()
                ? store.scan()
                : store.scanWorkspace(workspaceId);
        SearchOutcome out = searchTasks(tasks, pattern, isRegex, caseSensitive, wholeWord,
                pat, maxResults);
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
     * 大小写敏感标志)。DOTALL 让 '.' 通配换行,与 rg 对 JSON 行内的字符通配行为近似。
     */
    static Pattern compileSearchPattern(String pattern, boolean isRegex, boolean caseSensitive,
            boolean wholeWord) {
        String source = isRegex ? pattern : FsSearchService.escapeRegex(pattern);
        if (wholeWord) {
            source = "\\b(?:" + source + ")\\b";
        }
        return Pattern.compile(source, (caseSensitive ? 0 : Pattern.CASE_INSENSITIVE)
                | Pattern.DOTALL);
    }

    // ---- 参数帮助 ----

    /** 布尔参数(缺省 false;兼容 JSON 布尔与字符串,同 fs.search)。 */
    private static boolean boolParam(RpcContext ctx, String name) {
        return "true".equalsIgnoreCase(ctx.optStrParam(name, "false").trim());
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
