package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.worker.plugin.registry.SearchProviderRegistry;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.modules.WorkspaceCascadePort;
import dev.everyagent.worker.tools.RipgrepBinary;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * fs.search / fs.find 单元测试(架构 §7 契约表),不依赖 Spring/真实 hub:
 * ① 参数拼接与 JSON lines 解析(纯函数,钉住 VSCode ripgrepTextSearchEngine 语义的
 *    argv 契约:include 的 {@code -g !*} 放行链、exclude 的 {@code !} 前缀、
 *    fixed-strings/ignore-case/全字包裹;fs.find 的 --files argv 与 basename 正则);
 * ② 临时目录上的真实 rg 进程搜索:files 聚合、lineNumber/matchIndex/matchText、
 *    maxResults 触顶 kill 置 truncated、include/exclude 过滤、大小写、全字、
 *    元字符固定串回归、大结果 rpc.data 分批 + 末帧 ok 汇总;path 子目录范围
 *    (fs.search/fs.find 共用,含越界/不存在拒收);fs.find 的 basename 匹配语义。
 * 真实 RpcDispatcher 分发 + mock HubLink 捕获出站帧(FsBrowseTest 同款);
 * 环境无 rg 时进程类用例跳过(assumeTrue),纯函数用例照常。
 */
class FsSearchServiceTest {

    @TempDir
    Path tempDir;

    private RpcDispatcher dispatcher;
    private WorkspaceManager workspaces;
    /** rg 是否可用(不可用则真实进程用例跳过)。 */
    private boolean ready;
    private Path ws;

    @BeforeEach
    void setUp() throws Exception {
        // 环境栅栏:沙箱 TEMP 可能落在某个 git 工作树内,rg 会向上继承其 .gitignore
        // (如 *.log)导致 meta.log 夹具被静默跳过;在临时根放一个空 .git 目录,
        // rg 即以临时根为仓库边界,测试与 TEMP 位置解耦。
        Files.createDirectories(tempDir.resolve(".git"));
        WorkerProperties props = new WorkerProperties();
        props.setHomeDir(tempDir.resolve("home").toString());
        props.setHomeDir(tempDir.resolve("data").toString());
        ws = tempDir.resolve("ws");
        props.setWorkspaceRoot(ws.toString());
        Path rgBin = locateRg();
        if (rgBin != null) {
            props.getTools().setRgPath(rgBin.toString());
        }
        ready = rgBin != null;

        dispatcher = new RpcDispatcher(null, new WorkerProperties());
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkspaceCascadePort> provider = mock(ObjectProvider.class);
        workspaces = new WorkspaceManager(props, mock(RpcDispatcher.class), mock(HubPool.class),
                provider, new dev.everyagent.worker.os.SandboxPathRegistry(new dev.everyagent.worker.os.OsSandbox(props, null)));
        workspaces.init();
        new FsSearchService(dispatcher, workspaces, new RipgrepBinary(props), new SearchProviderRegistry());
    }

    /** 测试环境 rg 定位:程序根 runtime/bin(IDE/打包)→ 模块父目录(maven,user.dir=模块)→ PATH。 */
    private static Path locateRg() {
        boolean win = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        String name = win ? "rg.exe" : "rg";
        for (String base : List.of("runtime/bin", "../runtime/bin")) {
            Path p = Path.of(base, name).toAbsolutePath().normalize();
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p;
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                Path p = Path.of(dir.trim()).resolve(name);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    // ---- 帮助:造数据 / 收发 ----

    /** 标准夹具:a.txt 两行命中、sub/b.txt 一行(大写)、.hidden/h.txt 一行、c.txt 无、meta.log 一行。 */
    private Path seed() throws Exception {
        Files.createDirectories(ws.resolve("sub"));
        Files.createDirectories(ws.resolve(".hidden"));
        Files.writeString(ws.resolve("a.txt"), "needle one\nnope here\nneedle two\n");
        Files.writeString(ws.resolve("sub/b.txt"), "NEEDLE upper\nplain\n");
        Files.writeString(ws.resolve(".hidden/h.txt"), "hidden needle\n");
        Files.writeString(ws.resolve("c.txt"), "nothing here\n");
        Files.writeString(ws.resolve("meta.log"), "needle in log\n");
        return ws;
    }

    /** 发起一次 RPC(经真实分发器,虚拟线程异步执行),返回末帧 {event, payload},data 批次逐项收集进 sink。 */
    private JsonNode call(String method, ObjectNode params, List<JsonNode> sink) throws Exception {
        CountDownLatch replied = new CountDownLatch(1);
        List<Object[]> frames = new ArrayList<>();
        HubLink link = mock(HubLink.class);
        when(link.k()).thenReturn("k");
        when(link.workerId()).thenReturn("w");
        doAnswer(inv -> {
            Object[] args = inv.getArguments();
            synchronized (frames) {
                frames.add(args);
            }
            if ("rpc.data".equals(args[1])) {
                if (sink != null) {
                    ((JsonNode) args[3]).path("batch").forEach(sink::add);
                }
                return null;
            }
            replied.countDown();
            return null;
        }).when(link).pub(any(), any(), any(), any(), any());

        ObjectNode payload = Json.obj()
                .put("reqId", "req-" + System.nanoTime())
                .put("method", method);
        payload.set("params", params);
        ObjectNode frame = Json.obj()
                .put("channel", Channels.workerCmd("k", "w"))
                .put("event", "rpc");
        frame.set("payload", payload);
        dispatcher.onHubMessage(link, frame);

        assertTrue(replied.await(30, TimeUnit.SECONDS), "fs.search 未在 30s 内应答");
        Object[] last;
        synchronized (frames) {
            last = frames.get(frames.size() - 1);
        }
        return Json.obj().put("event", (String) last[1]).set("payload", (JsonNode) last[3]);
    }

    /** 内联(未分批)应答:断言 ok 并取出 result.files(按 path 建索引,跨文件顺序不依赖 rg 遍历序)。 */
    private Map<String, JsonNode> inlineFiles(String method, ObjectNode params) throws Exception {
        JsonNode reply = call(method, params, new ArrayList<>());
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        JsonNode result = reply.path("payload").path("result");
        Map<String, JsonNode> byPath = new HashMap<>();
        result.path("files").forEach(f -> byPath.put(f.path("path").asString(), f));
        return byPath;
    }

    /** fs.search 便捷重载(多数用例)。 */
    private JsonNode call(ObjectNode params, List<JsonNode> sink) throws Exception {
        return call(RpcMethods.FS_SEARCH, params, sink);
    }

    private Map<String, JsonNode> inlineFiles(ObjectNode params) throws Exception {
        return inlineFiles(RpcMethods.FS_SEARCH, params);
    }

    private static ObjectNode params(Path ws, String pattern) {
        return Json.obj()
                .put("workspace", ws.toAbsolutePath().normalize().toString())
                .put("pattern", pattern);
    }

    // ---- ① 参数拼接(纯函数) ----

    @Test
    void buildArgsFixedStringDefaultsToIgnoreCase() {
        // 固定串:pattern 原样 + --fixed-strings(rg -F 纯字面量不反转义,原样才正确)
        assertEquals(List.of("--hidden", "--json", "--crlf", "--no-config", "--ignore-case",
                "--fixed-strings", "-e", "foo.bar", "."),
                FsSearchService.buildArgs("foo.bar", false, false, false, List.of(), List.of(), "."));
    }

    @Test
    void buildArgsCaseSensitiveAndRegex() {
        assertEquals(List.of("--hidden", "--json", "--crlf", "--no-config", "--case-sensitive",
                "-e", "\\d+", "."),
                FsSearchService.buildArgs("\\d+", true, true, false, List.of(), List.of(), "."));
        // 正则模式不加 --fixed-strings,pattern 原样
        assertEquals(List.of("--hidden", "--json", "--crlf", "--no-config", "--ignore-case",
                "-e", "a|b", "."),
                FsSearchService.buildArgs("a|b", true, false, false, List.of(), List.of(), "."));
    }

    @Test
    void buildArgsWholeWordWrapsPattern() {
        // 全字:不用 -w,包裹 \b(?:...)\b 后按正则传(包裹后不能用 --fixed-strings)
        assertEquals(List.of("--hidden", "--json", "--crlf", "--no-config", "--ignore-case",
                "-e", "\\b(?:cat)\\b", "."),
                FsSearchService.buildArgs("cat", true, false, true, List.of(), List.of(), "."));
        // 全字 + 固定串:先转义元字符再包裹,保持字面量语义
        assertEquals(List.of("--hidden", "--json", "--crlf", "--no-config", "--ignore-case",
                "-e", "\\b(?:c\\.t)\\b", "."),
                FsSearchService.buildArgs("c.t", false, false, true, List.of(), List.of(), "."));
    }

    @Test
    void buildArgsIncludeAndExcludeGlobs() {
        // include:先 -g !* 全拒再逐 glob 放行(VSCode 拼法);exclude:逐 -g !<glob>
        assertEquals(List.of("--hidden", "--json", "--crlf", "--no-config", "--ignore-case",
                "--fixed-strings", "-e", "x",
                "-g", "!*", "-g", "*.ts", "-g", "**/*.md",
                "-g", "!dist/**", "-g", "!node_modules/**", "."),
                FsSearchService.buildArgs("x", false, false, false,
                        List.of("*.ts", "**/*.md"), List.of("dist/**", "node_modules/**"), "."));
        // 仅 exclude 时不引入 -g !*(include 链专属)
        assertEquals(List.of("--hidden", "--json", "--crlf", "--no-config", "--ignore-case",
                "--fixed-strings", "-e", "x", "-g", "!*.log", "."),
                FsSearchService.buildArgs("x", false, false, false, List.of(), List.of("*.log"), "."));
    }

    @Test
    void buildArgsScopedSearchPath() {
        // 可选 path 范围:作为 rg 的搜索路径参数(缺省 ".")
        assertEquals(List.of("--hidden", "--json", "--crlf", "--no-config", "--ignore-case",
                "--fixed-strings", "-e", "x", "docs"),
                FsSearchService.buildArgs("x", false, false, false, List.of(), List.of(), "docs"));
    }

    @Test
    void buildFileArgsListsFilesWithoutJson() {
        // --files 下 --json 只吐 summary 不吐路径 → 必须不配 --json;glob 拼法与 buildArgs 共用
        assertEquals(List.of("--hidden", "--files", "--no-config", "--no-messages",
                "-g", "!*", "-g", "*.ts", "-g", "!dist/**", "docs"),
                FsSearchService.buildFileArgs(List.of("*.ts"), List.of("dist/**"), "docs"));
        assertEquals(List.of("--hidden", "--files", "--no-config", "--no-messages", "."),
                FsSearchService.buildFileArgs(List.of(), List.of(), "."));
    }

    @Test
    void compileNamePatternSemantics() {
        // 固定串元字符按字面量(Java 方言本侧匹配):C++ 命中、. 不当通配
        var literal = FsSearchService.compileNamePattern("C++", false, true, false);
        assertTrue(literal.matcher("aC++b").find(), "字面量命中");
        assertFalse(literal.matcher("aCxb").find(), ". 不是通配");
        // 缺省大小写不敏感 / 敏感
        assertTrue(FsSearchService.compileNamePattern("cat", false, false, false).matcher("CAT").find());
        assertFalse(FsSearchService.compileNamePattern("cat", false, true, false).matcher("CAT").find());
        // 全字包裹 \b(?:...)\b:catalog 不命中、独立 cat 命中
        var wholeWord = FsSearchService.compileNamePattern("cat", false, true, true);
        assertFalse(wholeWord.matcher("catalog").find(), "全字:catalog 不命中");
        assertTrue(wholeWord.matcher("cat").find(), "全字:独立 cat 命中");
        // 全字 + 固定串:先转义再包裹,c.t 不匹配 cat
        var wholeWordLiteral = FsSearchService.compileNamePattern("c.t", false, true, true);
        assertFalse(wholeWordLiteral.matcher("cat").find(), "全字+固定串:. 转义后不当通配");
        assertTrue(wholeWordLiteral.matcher("c.t").find());
    }

    // ---- ② JSON lines 解析(纯函数) ----

    @Test
    void parseMatchLineExtractsFieldsAndStripsEol() {
        String json = "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"./src/a.txt\"},"
                + "\"lines\":{\"text\":\"hello world\\r\\n\"},\"line_number\":3,"
                + "\"submatches\":[{\"match\":{\"text\":\"world\"},\"start\":6,\"end\":11}]}}";
        FsSearchService.RawHit hit = FsSearchService.parseMatchLine(json);
        assertEquals("./src/a.txt", hit.rawPath());
        assertEquals(3, hit.lineNumber());
        assertEquals("hello world", hit.line()); // 剥尾部 \r\n
        assertEquals(6, hit.matchIndex());
        assertEquals("world", hit.matchText());
    }

    @Test
    void parseMatchLineSkipsNonMatchRecords() {
        assertNull(FsSearchService.parseMatchLine(
                "{\"type\":\"begin\",\"data\":{\"path\":{\"text\":\"./a.txt\"}}}"));
        assertNull(FsSearchService.parseMatchLine(
                "{\"type\":\"summary\",\"data\":{\"elapsed_total\":{}}}"));
    }

    @Test
    void relPathNormalizesToWorkspaceRelativePosix() {
        Path root = Path.of("/tmp/ws").toAbsolutePath().normalize();
        assertEquals("src/a.txt", FsSearchService.relPath(root, "./src/a.txt"));
        assertEquals("src/a.txt", FsSearchService.relPath(root, "src/a.txt"));
        assertEquals("a.txt", FsSearchService.relPath(root, "./a.txt"));
    }

    // ---- ③ 真实 rg 进程 ----

    @Test
    void realSearchAggregatesByFileWithLineAndColumn() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seed();
        Map<String, JsonNode> files = inlineFiles(params(ws, "needle"));
        // 默认 ignore-case:NEEDLE 也命中 → 4 处;--hidden 生效:.hidden/h.txt 在结果里
        JsonNode a = files.get("a.txt");
        JsonNode b = files.get("sub/b.txt");
        JsonNode h = files.get(".hidden/h.txt");
        assertTrue(a != null && b != null && h != null, "聚合应含 3 个文件: " + files.keySet());
        assertNull(files.get("c.txt"), "无命中文件不应出现");

        assertEquals(2, a.path("matches").size(), "a.txt 两行命中");
        JsonNode m1 = a.path("matches").path(0);
        assertEquals(1, m1.path("lineNumber").asInt());
        assertEquals("needle one", m1.path("line").asString());
        assertEquals(0, m1.path("matchIndex").asInt());
        assertEquals("needle", m1.path("matchText").asString());
        assertEquals(3, a.path("matches").path(1).path("lineNumber").asInt(), "同文件行序保持");

        assertEquals(1, b.path("matches").size());
        JsonNode bm = b.path("matches").path(0);
        assertEquals("NEEDLE upper", bm.path("line").asString());
        assertEquals("NEEDLE", bm.path("matchText").asString(), "matchText 保留实际命中大小写");

        JsonNode hm = h.path("matches").path(0);
        assertEquals(7, hm.path("matchIndex").asInt(), "hidden needle: 列偏移 0-based");
        assertEquals("hidden needle", hm.path("line").asString());
    }

    @Test
    void caseSensitiveExcludesCaseVariants() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seed();
        ObjectNode p = params(ws, "needle").put("caseSensitive", true);
        Map<String, JsonNode> files = inlineFiles(p);
        assertNull(files.get("sub/b.txt"), "大小写敏感时 NEEDLE 不命中: " + files.keySet());
        assertTrue(files.containsKey("a.txt"));
    }

    @Test
    void wholeWordMatchesStandaloneOnly() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("cat.txt"), "cat catalog\nconcat cat\n");
        Map<String, JsonNode> files = inlineFiles(params(ws, "cat").put("wholeWord", true));
        JsonNode matches = files.get("cat.txt").path("matches");
        assertEquals(2, matches.size(), "只命中独立单词 cat(catalog/concat 不算): " + matches);
        assertEquals(0, matches.path(0).path("matchIndex").asInt());
        assertEquals(7, matches.path(1).path("matchIndex").asInt(), "\"concat cat\" 中 cat@7");
    }

    @Test
    void fixedStringWithRegexMetaCharsStillMatches() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        // 回归保护:rg -F 不反转义,固定串模式 pattern 必须原样传递(C++/foo.bar 可命中;
        // 且 . 不当通配,fooXbar 不命中)
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("meta.txt"), "C++ code\nfoo.bar(x)\nfooXbar\n");
        Map<String, JsonNode> files = inlineFiles(params(ws, "C++"));
        assertEquals(1, files.get("meta.txt").path("matches").size(), "字面量 C++ 应命中");
        Map<String, JsonNode> dot = inlineFiles(params(ws, "foo.bar"));
        JsonNode ms = dot.get("meta.txt").path("matches");
        assertEquals(1, ms.size(), "固定串的 . 不是通配: " + ms);
        assertEquals("foo.bar(x)", ms.path(0).path("line").asString());
    }

    @Test
    void regexModeAndInvalidRegexRejected() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("r.txt"), "needle\nnoodle\n");
        Map<String, JsonNode> files = inlineFiles(params(ws, "ne.dle").put("isRegex", true));
        assertEquals(1, files.get("r.txt").path("matches").size(), ". 通配命中 needle");

        JsonNode reply = call(params(ws, "[").put("isRegex", true), new ArrayList<>());
        assertEquals("rpc.err", reply.path("event").asString(), reply.toString());
        assertEquals("BAD_PARAMS", reply.path("payload").path("code").asString());
        assertTrue(reply.path("payload").path("message").asString().contains("正则"),
                reply.toString());
    }

    @Test
    void maxResultsTruncatesAndKills() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seed();
        JsonNode reply = call(params(ws, "needle").put("maxResults", 2), new ArrayList<>());
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        JsonNode result = reply.path("payload").path("result");
        assertTrue(result.path("truncated").asBoolean(), "触顶应标记截断: " + result);
        assertEquals(2, result.path("matchCount").asInt());
        int total = 0;
        for (JsonNode f : result.path("files")) {
            total += f.path("matches").size();
        }
        assertEquals(2, total, "聚合条数恰为 maxResults");
    }

    @Test
    void includeAndExcludeGlobsPruneFiles() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seed();
        // include 只放行 *.txt:meta.log 被剪掉
        Map<String, JsonNode> inc = inlineFiles(params(ws, "needle").put("includeGlobs", "*.txt"));
        assertNull(inc.get("meta.log"), "include 链外文件被剪: " + inc.keySet());
        assertTrue(inc.containsKey("a.txt"));
        // exclude 剪掉 sub/**:sub/b.txt 消失
        Map<String, JsonNode> exc = inlineFiles(params(ws, "needle").put("excludeGlobs", "sub/**"));
        assertNull(exc.get("sub/b.txt"), "exclude 前缀剪枝: " + exc.keySet());
        assertTrue(exc.containsKey("meta.log"));
    }

    @Test
    void searchScopedToSubdirByPath() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seed();
        // path=sub:只搜 sub/ 下,根下 a.txt 不进结果
        Map<String, JsonNode> files = inlineFiles(params(ws, "needle").put("path", "sub"));
        assertTrue(files.containsKey("sub/b.txt"), files.keySet().toString());
        assertFalse(files.containsKey("a.txt"), "范围外文件不命中: " + files.keySet());
        // 越界范围拒收(SandboxViolationException → rpc.err)
        JsonNode denied = call(params(ws, "needle").put("path", "../"), new ArrayList<>());
        assertEquals("rpc.err", denied.path("event").asString(), denied.toString());
        // 不存在范围拒收(NotFoundException → rpc.err)
        JsonNode missing = call(params(ws, "needle").put("path", "no-such-dir"), new ArrayList<>());
        assertEquals("rpc.err", missing.path("event").asString(), missing.toString());
    }

    /** find 夹具:needle.txt、sub/NEEDLE-log.md、sub/other.txt(内容含 needle 但文件名不匹配)、zz.txt。 */
    private void seedNames() throws Exception {
        Files.createDirectories(ws.resolve("sub"));
        Files.writeString(ws.resolve("needle.txt"), "内容无关");
        Files.writeString(ws.resolve("sub/NEEDLE-log.md"), "内容无关");
        Files.writeString(ws.resolve("sub/other.txt"), "needle 在内容里但文件名不匹配");
        Files.writeString(ws.resolve("zz.txt"), "needle");
    }

    @Test
    void findMatchesBasenameNotContent() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seedNames();
        // 文件名搜索只看 basename:内容含 needle 的 other.txt 不命中;缺省不敏感 → NEEDLE-log.md 命中
        Map<String, JsonNode> files = inlineFiles(RpcMethods.FS_FIND, params(ws, "needle"));
        assertEquals(2, files.size(), files.keySet().toString());
        assertTrue(files.containsKey("needle.txt") && files.containsKey("sub/NEEDLE-log.md"),
                files.keySet().toString());
        assertTrue(files.get("needle.txt").path("matches").isMissingNode(), "find 结果项无 matches 字段");
        // 大小写敏感:NEEDLE-log.md 不再命中
        JsonNode reply = call(RpcMethods.FS_FIND, params(ws, "needle").put("caseSensitive", true),
                new ArrayList<>());
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        JsonNode result = reply.path("payload").path("result");
        assertEquals(1, result.path("files").size(), result.toString());
        assertEquals("needle.txt", result.path("files").path(0).path("path").asString());
        assertEquals(1, result.path("matchCount").asInt(), "matchCount = 命中文件数");
    }

    @Test
    void findScopedToSubdirAndExcludes() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seedNames();
        // path 范围:只列 sub/ 下(basename 含 log 的只有 NEEDLE-log.md)
        Map<String, JsonNode> scoped = inlineFiles(RpcMethods.FS_FIND, params(ws, "log").put("path", "sub"));
        assertEquals(1, scoped.size(), scoped.keySet().toString());
        assertTrue(scoped.containsKey("sub/NEEDLE-log.md"), scoped.keySet().toString());
        // exclude 剪枝:sub/** 排除后只剩根下 needle.txt
        Map<String, JsonNode> exc = inlineFiles(RpcMethods.FS_FIND,
                params(ws, "needle").put("excludeGlobs", "sub/**"));
        assertFalse(exc.containsKey("sub/NEEDLE-log.md"), exc.keySet().toString());
        assertTrue(exc.containsKey("needle.txt"));
    }

    @Test
    void findTruncatesAtMaxResults() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seedNames();
        JsonNode reply = call(RpcMethods.FS_FIND, params(ws, "needle").put("maxResults", 1),
                new ArrayList<>());
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        JsonNode result = reply.path("payload").path("result");
        assertTrue(result.path("truncated").asBoolean(), "触顶应标记截断: " + result);
        assertEquals(1, result.path("files").size());
        assertEquals(1, result.path("matchCount").asInt());
    }

    @Test
    void noMatchReturnsEmptyOk() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seed();
        JsonNode reply = call(params(ws, "definitely-not-there-xyz"), new ArrayList<>());
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        JsonNode result = reply.path("payload").path("result");
        assertEquals(0, result.path("matchCount").asInt());
        assertFalse(result.path("truncated").asBoolean());
        assertEquals(0, result.path("files").size());
    }

    @Test
    void bigResultBatchedIntoRpcDataWithSummaryOk() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        // 3 文件 × 2200 行,单文件项 ~260KB(> CHUNK 192KB)→ 每批一个文件项,
        // 总量 ~780KB(> INLINE_MAX 256KB)→ 必走 rpc.data 分批 + 末帧 ok 汇总
        Files.createDirectories(ws);
        for (int f = 1; f <= 3; f++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= 2200; i++) {
                sb.append("needle ").append(String.format("%04d", i))
                        .append(" 0123456789 0123456789 0123456789\n");
            }
            Files.writeString(ws.resolve("big" + f + ".txt"), sb.toString());
        }
        List<JsonNode> parts = new ArrayList<>();
        JsonNode reply = call(params(ws, "needle").put("maxResults", 7000), parts);
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        JsonNode summary = reply.path("payload").path("result");
        assertFalse(summary.has("files"), "分批路径末帧只带汇总,不重复 files: " + summary);
        assertEquals(6600, summary.path("matchCount").asInt());
        assertFalse(summary.path("truncated").asBoolean());
        assertEquals(3, summary.path("fileCount").asInt());
        assertEquals(3, parts.size(), "每文件项超 CHUNK 独占一批: " + parts.size());
        int matches = 0;
        for (JsonNode f : parts) {
            matches += f.path("matches").size();
            assertEquals(2200, f.path("matches").size(), "文件项不被撕裂: " + f.path("path"));
        }
        assertEquals(6600, matches, "批次拼接后与 matchCount 对齐");
    }

    // ---- ④ 参数校验 / rg 缺失 ----

    @Test
    void missingParamsRejected() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seed();
        // 缺 pattern
        JsonNode noPattern = call(Json.obj().put("workspace", ws.toString()), new ArrayList<>());
        assertEquals("BAD_PARAMS", noPattern.path("payload").path("code").asString());
        // 缺 workspace(strParam 缺省 BadParams)
        JsonNode noWs = call(Json.obj().put("pattern", "x"), new ArrayList<>());
        assertEquals("BAD_PARAMS", noWs.path("payload").path("code").asString());
        // workspace 非绝对路径(WorkspaceManager.resolve 拒绝)
        JsonNode rel = call(Json.obj().put("workspace", "relative/dir").put("pattern", "x"),
                new ArrayList<>());
        assertEquals("BAD_PARAMS", rel.path("payload").path("code").asString());
        // maxResults 非法
        JsonNode bad = call(params(ws, "x").put("maxResults", 0), new ArrayList<>());
        assertEquals("BAD_PARAMS", bad.path("payload").path("code").asString());
    }

    @Test
    void rgMissingFailsWithReadableError() throws Exception {
        // rgPath 指向不存在文件 → RipgrepBinary 视为缺失 → rpc.err(INTERNAL)+ 可读信息
        WorkerProperties props = new WorkerProperties();
        props.getTools().setRgPath(tempDir.resolve("no-such-rg").toString());
        RpcDispatcher d = new RpcDispatcher(null, new WorkerProperties());
        new FsSearchService(d, workspaces, new RipgrepBinary(props), new SearchProviderRegistry());
        CountDownLatch replied = new CountDownLatch(1);
        List<Object[]> frames = new ArrayList<>();
        HubLink link = mock(HubLink.class);
        when(link.k()).thenReturn("k");
        when(link.workerId()).thenReturn("w");
        doAnswer(inv -> {
            synchronized (frames) {
                frames.add(inv.getArguments());
            }
            replied.countDown();
            return null;
        }).when(link).pub(any(), any(), any(), any(), any());
        ObjectNode payload = Json.obj().put("reqId", "req-x").put("method", RpcMethods.FS_SEARCH);
        payload.set("params", params(ws, "x"));
        ObjectNode frame = Json.obj()
                .put("channel", Channels.workerCmd("k", "w"))
                .put("event", "rpc");
        frame.set("payload", payload);
        d.onHubMessage(link, frame);
        assertTrue(replied.await(10, TimeUnit.SECONDS));
        Object[] last;
        synchronized (frames) {
            last = frames.get(frames.size() - 1);
        }
        assertEquals("rpc.err", last[1]);
        JsonNode err = (JsonNode) last[3];
        assertEquals("INTERNAL", err.path("code").asString(), err.toString());
        assertTrue(err.path("message").asString().contains("rg 不可用"), err.toString());
    }

    // ---- ⑤ SearchProvider 增补聚合(纯函数,不依赖 rg) ----

    /** 造一条内置 rg 形态的 outcome:单文件若干命中(行号逐条给定,matchIndex 恒 0)。 */
    private static FsSearchService.SearchOutcome outcomeOf(String path, int... lineNumbers) {
        LinkedHashMap<String, ObjectNode> files = new LinkedHashMap<>();
        ObjectNode file = Json.obj().put("path", path);
        ArrayNode arr = Json.arr();
        for (int ln : lineNumbers) {
            arr.add(Json.obj().put("lineNumber", ln).put("line", "l" + ln)
                    .put("matchIndex", 0).put("matchText", "x"));
        }
        file.set("matches", arr);
        files.put(path, file);
        return new FsSearchService.SearchOutcome(files, lineNumbers.length, false);
    }

    private static SearchProvider.SearchResult hit(String path, int lineNumber, int matchIndex) {
        return new SearchProvider.SearchResult(path, lineNumber, "line " + lineNumber, matchIndex, "x");
    }

    /** 桩 provider:固定返回文件命中,或构造时给 error 则每次调用抛出。 */
    private static final class StubProvider implements SearchProvider {
        private final String id;
        private final List<SearchResult> files;
        private final RuntimeException error;

        StubProvider(String id, List<SearchResult> files) {
            this(id, files, null);
        }

        StubProvider(String id, List<SearchResult> files, RuntimeException error) {
            this.id = id;
            this.files = files;
            this.error = error;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public List<SearchResult> searchFiles(SearchRequest req) {
            if (error != null) {
                throw error;
            }
            return files;
        }

        @Override
        public List<TaskSearchResult> searchTasks(TaskSearchRequest req) {
            return List.of();
        }
    }

    private static SearchProvider.SearchRequest anyReq() {
        return new SearchProvider.SearchRequest("ws1", Path.of("."), "x", false, false, false,
                List.of(), List.of(), 1000);
    }

    @Test
    void providerResultsAppendedAfterBuiltinWithDedup() {
        FsSearchService.SearchOutcome builtIn = outcomeOf("a.txt", 1, 3);
        SearchProviderRegistry registry = new SearchProviderRegistry();
        registry.register(new StubProvider("p1", List.of(
                hit("a.txt", 1, 0),   // 与内置重复(path+line+index 同键)→ 去重
                hit("a.txt", 9, 0),   // 同文件新命中 → 并入 a.txt
                hit("b.md", 2, 4)))); // 新文件 → 新文件项

        FsSearchService.SearchOutcome merged = FsSearchService.mergeProviderResults(
                builtIn, registry, anyReq(), 1000);

        assertEquals(4, merged.matchCount(), "内置 2 + 新增 2(去重 1)");
        assertFalse(merged.truncated());
        JsonNode a = merged.files().get("a.txt");
        assertEquals(3, a.path("matches").size(), "a.txt = 内置 2 + provider 1");
        assertEquals(9, a.path("matches").path(2).path("lineNumber").asInt(), "provider 命中追加在尾部");
        JsonNode b = merged.files().get("b.md");
        assertEquals(2, b.path("matches").path(0).path("lineNumber").asInt());
        assertEquals(4, b.path("matches").path(0).path("matchIndex").asInt());
        // 内置顺序在前:a.txt 在 b.md 之前
        assertEquals("a.txt", merged.files().keySet().iterator().next());
    }

    @Test
    void providerResultsCappedAtMaxResultsMarkTruncated() {
        FsSearchService.SearchOutcome builtIn = outcomeOf("a.txt", 1, 2); // 已有 2
        SearchProviderRegistry registry = new SearchProviderRegistry();
        registry.register(new StubProvider("p1", List.of(
                hit("a.txt", 10, 0), hit("a.txt", 11, 0), hit("a.txt", 12, 0))));

        FsSearchService.SearchOutcome merged = FsSearchService.mergeProviderResults(
                builtIn, registry, anyReq(), 3);

        assertEquals(3, merged.matchCount(), "触顶截断到 maxResults");
        assertTrue(merged.truncated(), "provider 命中未全量消费应置 truncated");
        assertEquals(10, merged.files().get("a.txt").path("matches").path(2).path("lineNumber").asInt(),
                "只追加第 3 条(行 10)");
    }

    @Test
    void emptyRegistryReturnsOutcomeUnchanged() {
        FsSearchService.SearchOutcome builtIn = outcomeOf("a.txt", 1);
        FsSearchService.SearchOutcome merged = FsSearchService.mergeProviderResults(
                builtIn, new SearchProviderRegistry(), anyReq(), 1000);
        assertSame(builtIn, merged, "无 provider 注册时原样返回(零行为变化)");
    }

    @Test
    void builtinAtCapSkipsProvidersEntirely() {
        FsSearchService.SearchOutcome builtIn = outcomeOf("a.txt", 1, 2);
        SearchProviderRegistry registry = new SearchProviderRegistry();
        registry.register(new StubProvider("p1", List.of(hit("a.txt", 9, 0))));
        FsSearchService.SearchOutcome merged = FsSearchService.mergeProviderResults(
                builtIn, registry, anyReq(), 2);
        assertSame(builtIn, merged, "内置已触顶时无预算可加,跳过 provider");
    }

    @Test
    void providerFailureSkippedOthersStillMerged() {
        SearchProviderRegistry registry = new SearchProviderRegistry();
        registry.register(new StubProvider("bad", List.of(), new RuntimeException("boom")));
        registry.register(new StubProvider("good", List.of(hit("b.md", 5, 0))));
        FsSearchService.SearchOutcome merged = FsSearchService.mergeProviderResults(
                outcomeOf("a.txt", 1), registry, anyReq(), 1000);
        assertEquals(2, merged.matchCount(), "坏 provider 跳过,好 provider 照常合并");
        assertTrue(merged.files().containsKey("b.md"));
    }

    @Test
    void providerNullAndEmptyResultsNoop() {
        SearchProviderRegistry registry = new SearchProviderRegistry();
        registry.register(new StubProvider("nullish", null));
        registry.register(new StubProvider("empty", List.of()));
        FsSearchService.SearchOutcome builtIn = outcomeOf("a.txt", 1, 2);
        FsSearchService.SearchOutcome merged = FsSearchService.mergeProviderResults(
                builtIn, registry, anyReq(), 1000);
        assertEquals(2, merged.matchCount());
        assertEquals(1, merged.files().size(), "空/null 结果不产出文件项");
        assertFalse(merged.truncated());
    }

    @Test
    void rgMissingWithProvidersServesProviderResultsOnly() throws Exception {
        // rg 不可用但注册了 SearchProvider:跳过内置 rg,仅聚合 provider 结果(§8.5)
        Files.createDirectories(ws);
        WorkerProperties props = new WorkerProperties();
        props.getTools().setRgPath(tempDir.resolve("no-such-rg-either").toString());
        RpcDispatcher d = new RpcDispatcher(null, new WorkerProperties());
        SearchProviderRegistry registry = new SearchProviderRegistry();
        registry.register(new StubProvider("p1", List.of(hit("doc/x.md", 4, 2))));
        new FsSearchService(d, workspaces, new RipgrepBinary(props), registry);
        CountDownLatch replied = new CountDownLatch(1);
        List<Object[]> frames = new ArrayList<>();
        HubLink link = mock(HubLink.class);
        when(link.k()).thenReturn("k");
        when(link.workerId()).thenReturn("w");
        doAnswer(inv -> {
            synchronized (frames) {
                frames.add(inv.getArguments());
            }
            replied.countDown();
            return null;
        }).when(link).pub(any(), any(), any(), any(), any());
        ObjectNode payload = Json.obj().put("reqId", "req-p").put("method", RpcMethods.FS_SEARCH);
        payload.set("params", params(ws, "x"));
        ObjectNode frame = Json.obj()
                .put("channel", Channels.workerCmd("k", "w"))
                .put("event", "rpc");
        frame.set("payload", payload);
        d.onHubMessage(link, frame);
        assertTrue(replied.await(10, TimeUnit.SECONDS));
        Object[] last;
        synchronized (frames) {
            last = frames.get(frames.size() - 1);
        }
        assertEquals("rpc.ok", last[1], "有 provider 时 rg 缺失不再报错");
        JsonNode result = ((JsonNode) last[3]).path("result");
        assertEquals(1, result.path("matchCount").asInt());
        assertEquals(4, result.path("files").path(0).path("matches").path(0).path("lineNumber").asInt());
        assertFalse(result.path("truncated").asBoolean());
    }
}
