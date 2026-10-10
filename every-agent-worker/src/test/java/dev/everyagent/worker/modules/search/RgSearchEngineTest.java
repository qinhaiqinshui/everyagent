package dev.everyagent.worker.modules.search;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.tools.RipgrepBinary;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置 rg 执行引擎的纯函数(argv 拼接 / JSON lines 解析 / 路径归一 / glob 切分)与真实进程
 * 用例(exec 逐行消费 + 超时看门狗),钉住 VSCode ripgrepTextSearchEngine 同族的 argv 契约。
 */
class RgSearchEngineTest {

    @TempDir
    Path tempDir;

    // ---- 纯函数:argv 拼接 ----

    @Test
    void buildArgsFixedStringDefaultsToIgnoreCase() {
        assertEquals(List.of("--hidden", "--json", "--crlf", "--no-config", "--ignore-case",
                "--fixed-strings", "-e", "foo.bar", "."),
                RgSearchEngine.buildArgs("foo.bar", false, false, false, List.of(), List.of(), "."));
    }

    @Test
    void buildArgsCaseSensitiveAndRegex() {
        assertEquals(List.of("--hidden", "--json", "--crlf", "--no-config", "--case-sensitive",
                "-e", "\\d+", "."),
                RgSearchEngine.buildArgs("\\d+", true, true, false, List.of(), List.of(), "."));
        assertEquals(List.of("--hidden", "--json", "--crlf", "--no-config", "--ignore-case",
                "-e", "a|b", "."),
                RgSearchEngine.buildArgs("a|b", true, false, false, List.of(), List.of(), "."));
    }

    @Test
    void buildArgsWholeWordWrapsPattern() {
        assertEquals(List.of("--hidden", "--json", "--crlf", "--no-config", "--ignore-case",
                "-e", "\\b(?:cat)\\b", "."),
                RgSearchEngine.buildArgs("cat", true, false, true, List.of(), List.of(), "."));
        assertEquals(List.of("--hidden", "--json", "--crlf", "--no-config", "--ignore-case",
                "-e", "\\b(?:c\\.t)\\b", "."),
                RgSearchEngine.buildArgs("c.t", false, false, true, List.of(), List.of(), "."));
    }

    @Test
    void buildArgsIncludeAndExcludeGlobs() {
        assertEquals(List.of("--hidden", "--json", "--crlf", "--no-config", "--ignore-case",
                "--fixed-strings", "-e", "x",
                "-g", "!*", "-g", "*.ts", "-g", "**/*.md",
                "-g", "!dist/**", "-g", "!node_modules/**", "."),
                RgSearchEngine.buildArgs("x", false, false, false,
                        List.of("*.ts", "**/*.md"), List.of("dist/**", "node_modules/**"), "."));
        assertEquals(List.of("--hidden", "--json", "--crlf", "--no-config", "--ignore-case",
                "--fixed-strings", "-e", "x", "-g", "!*.log", "."),
                RgSearchEngine.buildArgs("x", false, false, false, List.of(), List.of("*.log"), "."));
    }

    @Test
    void buildArgsScopedSearchPath() {
        assertEquals(List.of("--hidden", "--json", "--crlf", "--no-config", "--ignore-case",
                "--fixed-strings", "-e", "x", "docs"),
                RgSearchEngine.buildArgs("x", false, false, false, List.of(), List.of(), "docs"));
    }

    @Test
    void buildFileArgsListsFilesWithoutJson() {
        assertEquals(List.of("--hidden", "--files", "--no-config", "--no-messages",
                "-g", "!*", "-g", "*.ts", "-g", "!dist/**", "docs"),
                RgSearchEngine.buildFileArgs(List.of("*.ts"), List.of("dist/**"), "docs"));
        assertEquals(List.of("--hidden", "--files", "--no-config", "--no-messages", "."),
                RgSearchEngine.buildFileArgs(List.of(), List.of(), "."));
    }

    @Test
    void compileNamePatternSemantics() {
        var literal = RgSearchEngine.compileNamePattern("C++", false, true, false);
        assertTrue(literal.matcher("aC++b").find());
        assertFalse(literal.matcher("aCxb").find(), ". 不是通配");
        assertTrue(RgSearchEngine.compileNamePattern("cat", false, false, false).matcher("CAT").find());
        assertFalse(RgSearchEngine.compileNamePattern("cat", false, true, false).matcher("CAT").find());
        var wholeWord = RgSearchEngine.compileNamePattern("cat", false, true, true);
        assertFalse(wholeWord.matcher("catalog").find(), "全字:catalog 不命中");
        assertTrue(wholeWord.matcher("cat").find(), "全字:独立 cat 命中");
        var wholeWordLiteral = RgSearchEngine.compileNamePattern("c.t", false, true, true);
        assertFalse(wholeWordLiteral.matcher("cat").find(), "全字+固定串:. 转义后不当通配");
        assertTrue(wholeWordLiteral.matcher("c.t").find());
    }

    @Test
    void parseMatchLineExtractsFieldsAndStripsEol() {
        String json = "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"./src/a.txt\"},"
                + "\"lines\":{\"text\":\"hello world\\r\\n\"},\"line_number\":3,"
                + "\"submatches\":[{\"match\":{\"text\":\"world\"},\"start\":6,\"end\":11}]}}";
        RgSearchEngine.RawHit hit = RgSearchEngine.parseMatchLine(json);
        assertEquals("./src/a.txt", hit.rawPath());
        assertEquals(3, hit.lineNumber());
        assertEquals("hello world", hit.line());
        assertEquals(6, hit.matchIndex());
        assertEquals("world", hit.matchText());
    }

    @Test
    void parseMatchLineSkipsNonMatchRecords() {
        assertNull(RgSearchEngine.parseMatchLine(
                "{\"type\":\"begin\",\"data\":{\"path\":{\"text\":\"./a.txt\"}}}"));
        assertNull(RgSearchEngine.parseMatchLine(
                "{\"type\":\"summary\",\"data\":{\"elapsed_total\":{}}}"));
    }

    @Test
    void relPathNormalizesToWorkspaceRelativePosix() {
        Path root = Path.of("/tmp/ws").toAbsolutePath().normalize();
        assertEquals("src/a.txt", RgSearchEngine.relPath(root, "./src/a.txt"));
        assertEquals("src/a.txt", RgSearchEngine.relPath(root, "src/a.txt"));
        assertEquals("a.txt", RgSearchEngine.relPath(root, "./a.txt"));
    }

    @Test
    void splitGlobsTrimsAndDropsEmpty() {
        assertEquals(List.of("*.ts", "**/*.md"), RgSearchEngine.splitGlobs(" *.ts , , **/*.md "));
        assertEquals(List.of(), RgSearchEngine.splitGlobs(""));
        assertEquals(List.of(), RgSearchEngine.splitGlobs(null));
    }

    @Test
    void escapeRegexEscapesMetaChars() {
        assertEquals("c\\+\\+", RgSearchEngine.escapeRegex("c++"));
        assertEquals("foo\\.bar", RgSearchEngine.escapeRegex("foo.bar"));
    }

    // ---- 真实 rg 进程 ----

    @Test
    void execConsumesJsonLinesAndReportsNoTruncation() throws Exception {
        Path rg = locateRg();
        Assumptions.assumeTrue(rg != null, "环境无 rg,跳过真实进程用例");
        Files.createDirectories(tempDir.resolve(".git"));
        Path ws = tempDir.resolve("ws");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("a.txt"), "needle one\nnope\nneedle two\n");

        WorkerProperties props = new WorkerProperties();
        props.getTools().setRgPath(rg.toString());
        RgSearchEngine engine = new RgSearchEngine(new RipgrepBinary(props), props);

        List<RgSearchEngine.RawHit> hits = new ArrayList<>();
        List<String> args = RgSearchEngine.buildArgs("needle", false, false, false,
                List.of(), List.of(), ".");
        RgSearchEngine.StreamOutcome out = engine.exec(ws, args, line -> {
            RgSearchEngine.RawHit h = RgSearchEngine.parseMatchLine(line);
            if (h != null) {
                hits.add(h);
            }
            return true;
        });
        assertFalse(out.truncated());
        assertFalse(out.timedOut());
        assertEquals(2, hits.size(), hits.toString());
        assertEquals(1, hits.get(0).lineNumber());
        assertEquals(3, hits.get(1).lineNumber());
        assertFalse(engine.finishTruncated(out, hits.size(), "test"));
    }

    @Test
    void execStopsEarlyWhenHandlerReturnsFalseAndFlagsTruncated() throws Exception {
        Path rg = locateRg();
        Assumptions.assumeTrue(rg != null, "环境无 rg,跳过真实进程用例");
        Files.createDirectories(tempDir.resolve(".git"));
        Path ws = tempDir.resolve("ws");
        Files.createDirectories(ws);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("needle ").append(i).append('\n');
        }
        Files.writeString(ws.resolve("a.txt"), sb.toString());

        WorkerProperties props = new WorkerProperties();
        props.getTools().setRgPath(rg.toString());
        RgSearchEngine engine = new RgSearchEngine(new RipgrepBinary(props), props);
        int[] count = {0};
        List<String> args = RgSearchEngine.buildArgs("needle", false, false, false, List.of(), List.of(), ".");
        RgSearchEngine.StreamOutcome out = engine.exec(ws, args, line -> {
            RgSearchEngine.RawHit h = RgSearchEngine.parseMatchLine(line);
            return h == null || ++count[0] < 3;
        });
        assertTrue(out.truncated(), "消费者返回 false 应标记截断");
        assertEquals(3, count[0]);
    }

    @Test
    void execTimeoutKillsHangingRg() throws Exception {
        boolean win = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        Assumptions.assumeTrue(win, "假 rg 夹具为 Windows 批处理,非 Windows 跳过");
        Path fake = tempDir.resolve("fake-rg.cmd");
        String match = "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"a.txt\"},"
                + "\"lines\":{\"text\":\"needle\\\\n\"},\"line_number\":1,"
                + "\"submatches\":[{\"match\":{\"text\":\"needle\"},\"start\":0,\"end\":6}]}}";
        Files.writeString(fake, "@echo off\r\necho " + match + "\r\nping -n 4 127.0.0.1 >nul\r\n",
                StandardCharsets.ISO_8859_1);
        Files.createDirectories(tempDir.resolve(".git"));
        Path ws = tempDir.resolve("ws");
        Files.createDirectories(ws);

        WorkerProperties props = new WorkerProperties();
        props.getTools().setRgPath(fake.toString());
        props.getSearch().setRgTimeoutMs(800);
        RgSearchEngine engine = new RgSearchEngine(new RipgrepBinary(props), props);

        List<String> args = RgSearchEngine.buildArgs("needle", false, false, false, List.of(), List.of(), ".");
        long start = System.nanoTime();
        List<String> lines = new ArrayList<>();
        RgSearchEngine.StreamOutcome out = engine.exec(ws, args, line -> {
            lines.add(line);
            return true;
        });
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        assertTrue(out.timedOut(), "超时应强杀挂起的 rg");
        assertTrue(engine.finishTruncated(out, lines.size(), "test"));
        assertTrue(elapsed < 10_000, "不应等满假 rg 的 3s 挂起之后太久: " + elapsed + "ms");
    }

    @Test
    void finishTruncatedThrowsEngineExceptionOnBadExitWithNoResults() {
        WorkerProperties props = new WorkerProperties();
        RgSearchEngine engine = new RgSearchEngine(new RipgrepBinary(props), props);
        RgSearchEngine.StreamOutcome bad = new RgSearchEngine.StreamOutcome(false, false, 2, "boom");
        try {
            engine.finishTruncated(bad, 0, "test");
            org.junit.jupiter.api.Assertions.fail("应抛 SearchEngineException");
        } catch (SearchEngineException e) {
            assertTrue(e.getMessage().contains("rg 搜索失败"), e.getMessage());
        }
    }

    /** 测试环境 rg 定位:程序根 runtime/bin → 模块父目录 → PATH。 */
    static Path locateRg() {
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
}