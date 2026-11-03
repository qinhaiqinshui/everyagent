package dev.everyagent.plugin.git;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.plugin.api.rpc.RpcContext;
import dev.everyagent.plugin.api.spi.ExecResult;
import dev.everyagent.plugin.api.spi.NativeExec;
import dev.everyagent.plugin.api.spi.WorkspaceManager;
import dev.everyagent.plugin.api.spi.WorkspaceSandbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GitService 真实 git 行为集成测试(原 worker 侧 FsGitModuleTest 的 git.* 段迁入;
 * 插件对 worker 零依赖——全桩实现 plugin-api 接口,ARCHITECTURE §1.1)。
 *
 * <p>与 worker 侧差异:不再经 hub WebSocket 走 RPC 分发(那要求 worker 测试环境能扫到
 * 内置插件源码树,Maven 模块目录下不成立,曾长期在多数环境报 UNKNOWN_METHOD),而是
 * 直驱 {@link GitService} 的包可见方法;{@link RpcContext} 用捕获应答的桩实现,
 * 错误语义直接断言异常类型(RpcDispatcher 的异常→错误码映射属 worker 契约,另有覆盖)。
 * git 仍是宿主原生真跑(经 {@link NativeGit} → NativeExec 桩 → ProcessBuilder)。
 *
 * <p>回归面(FsGitModuleTest 原有用例逐条对应):
 * <ol>
 *   <li>status:未跟踪文件/分支名/ahead-behind(含 bare 远端 push 后归零);</li>
 *   <li>commit/log/diff:提交 shortId、状态清空、log 作者消息、diff before/after 全文
 *       (原 {@code gitCommitLogDiff} 在 worker 侧因丢失 @Test 从未真正执行,此处复活);</li>
 *   <li>show:三次提交的 created/updated/deleted 变更类型、before/after 全文、二进制判定、
 *       根提交(--root)与非法 commit 参数;</li>
 *   <li>discard:恢复「已删除/已丢失」文件(原 resolveExisting 误报 NOT_FOUND 的回归,含中文路径);</li>
 *   <li>commit 跳过陈旧勾选路径(勾选后被删除的未跟踪文件不得阻断提交)。</li>
 * </ol>
 */
class GitServiceIntegrationTest {

    @TempDir
    Path ws;

    private GitService service;

    @BeforeEach
    void initRepo() throws Exception {
        runGit("init", "-b", "main");
        runGit("config", "user.name", "测试用户");
        runGit("config", "user.email", "t@example.com");
        service = new GitService(testWorkspaces(), new GitCredentialStore(),
                new NativeGit(testConfig(), testNativeExec()));
    }

    // ---- 测试桩(plugin-api 接口实现,零 worker 依赖) ----

    /** 绑定 ws 根的最小工作区管理器桩:忽略 workspace 参数值,恒返回绑定本测试 ws 的沙箱。 */
    private WorkspaceManager testWorkspaces() {
        Path root = ws.toAbsolutePath().normalize();
        return new WorkspaceManager() {
            @Override
            public WorkspaceSandbox sandboxFor(String raw) {
                return testSandbox(root);
            }

            @Override public String idOfRoot(String root) { return null; }
            @Override public Path systemDir() { return null; }
            @Override public Path defaultRoot() { return ws; }
            @Override public List<Path> pruneStaleAndListMountRoots() { return List.of(); }
        };
    }

    private static WorkspaceSandbox testSandbox(Path root) {
        return new WorkspaceSandbox() {
            @Override public Path root() { return root; }
            @Override public List<Path> extraRoots() { return List.of(); }

            @Override
            public String display(Path p) {
                return "/" + root().relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
            }

            @Override
            public Path resolveExisting(String rel) throws IOException {
                Path p = resolveWithin(rel);
                if (!Files.exists(p)) {
                    throw new dev.everyagent.plugin.api.exception.NotFoundException("路径不存在: " + rel);
                }
                return p;
            }

            @Override
            public Path resolveTarget(String rel) {
                return resolveWithin(rel);
            }

            @Override
            public Path resolveLoose(String rel) {
                return resolveWithin(rel);
            }

            @Override
            public void requireNotRoot(Path p) {
                if (p.toAbsolutePath().normalize().equals(root())) {
                    throw new IllegalArgumentException("不能操作工作区根本身");
                }
            }

            /** 词法归一并校验不逃逸根(测试桩:不做 realpath,足够 git 用例)。 */
            private Path resolveWithin(String rel) {
                Path p = Path.of(rel);
                Path norm = (p.isAbsolute() ? p : root().resolve(p)).normalize();
                if (!norm.startsWith(root())) {
                    throw new IllegalArgumentException("路径越界: " + rel);
                }
                return norm;
            }
        };
    }

    /** 只实现 NativeGit 用到的 git 配置域(executable 空 = 自动探测;其余域不可达)。 */
    private static WorkerConfig testConfig() {
        return new WorkerConfig() {
            @Override public Git git() {
                return new Git() {
                    @Override public String executable() { return ""; }
                    @Override public long timeoutMs() { return 60_000; }
                };
            }

            @Override public Limits limits() { throw new UnsupportedOperationException(); }
            @Override public Retry retry() { throw new UnsupportedOperationException(); }
            @Override public Sandbox sandbox() { throw new UnsupportedOperationException(); }
            @Override public Permissions permissions() { throw new UnsupportedOperationException(); }
            @Override public Path resolveHomeDir() { throw new UnsupportedOperationException(); }
            @Override public Path resolveSandboxPersistentRoot() { throw new UnsupportedOperationException(); }
            @Override public Path resolveSkillsDir() { throw new UnsupportedOperationException(); }
            @Override public Path resolveRuntimeDir() { throw new UnsupportedOperationException(); }
        };
    }

    /**
     * NativeExec 桩:ProcessBuilder argv 直传真跑宿主进程(与 worker OsSandbox.spawnNative
     * 同语义:超时、双流分离捕获),让 NativeGit 全链路(探测/执行/输出解码)走真实路径。
     */
    private static NativeExec testNativeExec() {
        return (argv, cwd, env, timeoutMs) -> {
            Process p;
            try {
                ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd.toFile());
                if (env != null && !env.isEmpty()) {
                    pb.environment().putAll(env);
                }
                p = pb.start();
            } catch (IOException e) {
                return new ExecResult("", "启动失败: " + e.getMessage(), -1, false);
            }
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread tOut = new Thread(() -> readQuietly(p.getInputStream(), out));
            Thread tErr = new Thread(() -> readQuietly(p.getErrorStream(), err));
            tOut.setDaemon(true);
            tErr.setDaemon(true);
            tOut.start();
            tErr.start();
            boolean finished;
            try {
                finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
                return new ExecResult(out.toString(), err.toString(), p.exitValue(), true);
            }
            if (!finished) {
                p.destroyForcibly();
                return new ExecResult(out.toString(), err.toString(), -1, true);
            }
            try {
                tOut.join(2000);
                tErr.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ExecResult(out.toString(), err.toString(), p.exitValue(), false);
        };
    }

    private static void readQuietly(java.io.InputStream in, StringBuilder sink) {
        try (var reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) >= 0) {
                sink.append(buf, 0, n);
            }
        } catch (IOException ignored) {
            // 进程销毁时流关闭属正常
        }
    }

    /** 捕获应答的 RpcContext 桩:params 来自 Json 对象,ok/err 落字段供断言。 */
    private static final class FakeRpc implements RpcContext {
        private final ObjectNode params;
        JsonNode result;
        String errCode;
        String errMessage;

        FakeRpc(ObjectNode params) {
            this.params = params == null ? Json.obj() : params;
        }

        static FakeRpc of(ObjectNode params) {
            FakeRpc rpc = new FakeRpc(params);
            // workspace 必填语义占位:testWorkspaces 桩不消费取值,恒绑定本测试 ws
            rpc.params.put("workspace", "test-ws");
            return rpc;
        }

        @Override public String strParam(String name) {
            JsonNode n = params.path(name);
            if (n.isMissingNode() || n.isNull() || n.asString().isEmpty()) {
                throw new BadParamsException("缺少参数 " + name);
            }
            return n.asString();
        }

        @Override public String optStrParam(String name, String def) {
            JsonNode n = params.path(name);
            return n.isMissingNode() || n.isNull() ? def : n.asString();
        }

        @Override public long optLongParam(String name, long def) {
            JsonNode n = params.path(name);
            return n.isMissingNode() || n.isNull() ? def : n.asLong(def);
        }

        @Override public void ok(JsonNode ok) { this.result = ok; }
        @Override public void err(String code, String message) {
            this.errCode = code;
            this.errMessage = message;
        }
        @Override public JsonNode params() { return params; }
    }

    // ---- 帮助方法 ----

    /** 在测试工作区直跑系统 git(测试前置,不经 NativeGit)。 */
    private void runGit(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(ws.toString());
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "git " + String.join(" ", args) + " 失败: " + out);
    }

    private static Path write(Path ws, String rel, String content) throws Exception {
        Path f = ws.resolve(rel);
        Files.createDirectories(f.getParent() == null ? ws : f.getParent());
        return Files.writeString(f, content, StandardCharsets.UTF_8);
    }

    private JsonNode commit(String message) throws IOException {
        FakeRpc rpc = FakeRpc.of(Json.obj().put("message", message));
        service.commit(rpc);
        assertTrue(rpc.result != null, "commit 应成功: " + rpc.errMessage);
        return rpc.result;
    }

    private JsonNode status() throws IOException {
        FakeRpc rpc = FakeRpc.of(Json.obj());
        service.status(rpc);
        assertTrue(rpc.result != null, "status 应成功: " + rpc.errMessage);
        return rpc.result;
    }

    private JsonNode log(int max) throws IOException {
        FakeRpc rpc = FakeRpc.of(Json.obj().put("max", max));
        service.log(rpc);
        assertTrue(rpc.result != null, "log 应成功: " + rpc.errMessage);
        return rpc.result;
    }

    private JsonNode show(String commitId) throws IOException {
        FakeRpc rpc = FakeRpc.of(Json.obj().put("commit", commitId));
        service.show(rpc);
        assertTrue(rpc.result != null, "show 应成功: " + rpc.errMessage);
        return rpc.result;
    }

    /** 生成含 NUL 的伪随机二进制内容(确保 git 判为二进制)。 */
    private static byte[] noisyBinaryBytes(int size) {
        byte[] bytes = new byte[size];
        java.util.Random rnd = new java.util.Random(0xbeef);
        rnd.nextBytes(bytes);
        for (int i = 0; i < bytes.length; i += 16) {
            bytes[i] = 0;
        }
        return bytes;
    }

    // ---- status ----

    @Test
    void statusSeesUntrackedAndBranch() throws Exception {
        write(ws, "gitfile.txt", "第一版");
        JsonNode s = status();
        assertEquals("main", s.path("branch").asString(), s.toString());
        assertTrue(s.path("untracked").toString().contains("gitfile.txt"), s.toString());
        assertEquals(0, s.path("ahead").asInt(-1), "无上游时 ahead 应为 0: " + s);
        assertEquals(0, s.path("behind").asInt(-1), "无上游时 behind 应为 0: " + s);
    }

    @Test
    void statusAheadBehindWithPush() throws Exception {
        // 未配置上游:ahead/behind 恒 0(角标「已提交未推送」不误报)。
        write(ws, "first.txt", "第一版");
        commit("根提交");
        assertEquals(0, status().path("ahead").asInt(-1), "无上游时 ahead 应为 0");

        // 配置 bare 远端并推送 → upstream 建立,ahead=0。
        Path remote = Files.createTempDirectory("ea-git-remote-");
        List<String> init = List.of("git", "init", "--bare", remote.toString());
        assertEquals(0, new ProcessBuilder(init).redirectErrorStream(true).start().waitFor(),
                "bare 远端初始化失败");
        FakeRpc add = FakeRpc.of(Json.obj().put("name", "origin").put("url", remote.toString()));
        service.remoteAdd(add);
        assertTrue(add.result != null, "remote.add 应成功: " + add.errMessage);

        FakeRpc push = FakeRpc.of(Json.obj());
        service.push(push);
        assertTrue(push.result != null, "push 应成功: " + push.errMessage);
        assertEquals(0, status().path("ahead").asInt(-1), "push 后不应有未推送提交");

        // 再做一次本地提交:ahead=1;推送后归零。
        write(ws, "pending.txt", "待推送");
        commit("待推送提交");
        assertEquals(1, status().path("ahead").asInt(-1), "本地领先 1 个提交");
        FakeRpc push2 = FakeRpc.of(Json.obj());
        service.push(push2);
        assertTrue(push2.result != null, "第二次 push 应成功: " + push2.errMessage);
        assertEquals(0, status().path("ahead").asInt(-1), "推送后 ahead 应归零");
    }

    // ---- commit / log / diff(原 gitCommitLogDiff 在 worker 侧丢失 @Test,此处复活) ----

    @Test
    void commitLogAndDiff() throws Exception {
        write(ws, "gitfile.txt", "第一版");
        JsonNode c = commit("首次提交");
        assertEquals(8, c.path("shortId").asString().length(), c.toString());

        JsonNode clean = status();
        assertTrue(clean.path("untracked").isEmpty(), "提交后不应有未跟踪文件: " + clean);
        assertTrue(clean.path("modified").isEmpty() && clean.path("added").isEmpty(),
                "提交后工作区应干净: " + clean);

        JsonNode logNode = log(50);
        JsonNode first = logNode.path("commits").path(0);
        assertEquals("首次提交", first.path("message").asString(), logNode.toString());
        assertEquals("测试用户", first.path("author").asString(), logNode.toString());
        assertTrue(first.path("ts").asLong(0) > 0, "log 应带时间戳: " + logNode);

        write(ws, "gitfile.txt", "第二版 内容变化");
        FakeRpc diff = FakeRpc.of(Json.obj().put("path", "gitfile.txt"));
        service.diff(diff);
        assertTrue(diff.result != null, "diff 应成功: " + diff.errMessage);
        assertFalse(diff.result.path("empty").asBoolean(true), "修改后 diff 不应为空: " + diff.result);
        assertEquals("gitfile.txt", diff.result.path("filePath").asString(), diff.result.toString());
        assertEquals("updated", diff.result.path("changeType").asString(), diff.result.toString());
        assertTrue(diff.result.path("beforeContent").asString().contains("第一版"), diff.result.toString());
        assertTrue(diff.result.path("afterContent").asString().contains("第二版"), diff.result.toString());
    }

    // ---- show ----

    @Test
    void showListsFilesContentsChangeTypesBinary() throws Exception {
        // 提交1:新建 a;提交2:改 a + 新建 c + 二进制(此时 log(0) = 二提,先断言二提)。
        write(ws, "show-a.txt", "show 内容 A");
        commit("show 根提交");

        write(ws, "show-a.txt", "show 内容 A 改");
        write(ws, "show-c.txt", "show 内容 C");
        Files.write(ws.resolve("show.bin"), noisyBinaryBytes(4096));
        commit("show 二提");

        JsonNode second = show(log(50).path("commits").path(0).path("id").asString());
        JsonNode files2 = second.path("files");
        assertTrue(files2.isArray() && files2.size() >= 3, "应含改 a / 新 c / 二进制: " + second);

        boolean sawUpdatedA = false;
        boolean sawCreatedC = false;
        boolean sawBinary = false;
        for (JsonNode f : files2) {
            if (f.path("path").asString().equals("show-a.txt")) {
                assertEquals("updated", f.path("changeType").asString(), second.toString());
                assertTrue(f.path("beforeContent").asString().contains("show 内容 A"), second.toString());
                assertTrue(f.path("afterContent").asString().contains("show 内容 A 改"), second.toString());
                sawUpdatedA = true;
            }
            if (f.path("path").asString().equals("show-c.txt")) {
                assertEquals("created", f.path("changeType").asString(), second.toString());
                assertTrue(f.path("beforeContent").asString().isEmpty(), "新增 before 应为空: " + second);
                assertTrue(f.path("afterContent").asString().contains("show 内容 C"), second.toString());
                sawCreatedC = true;
            }
            if (f.path("path").asString().equals("show.bin")) {
                assertTrue(f.path("binary").asBoolean(false), "含 NUL 应为二进制: " + second);
                assertTrue(!f.has("beforeContent") && !f.has("afterContent"),
                        "二进制不读全文: " + second);
                sawBinary = true;
            }
        }
        assertTrue(sawUpdatedA && sawCreatedC && sawBinary, "三类变更都覆盖: " + second);

        // 提交3:删 a(log(0) = 删除提交)→ deleted,after 为空。
        Files.delete(ws.resolve("show-a.txt"));
        commit("show 删除");
        JsonNode third = show(log(50).path("commits").path(0).path("id").asString());
        boolean sawDeleted = false;
        for (JsonNode f : third.path("files")) {
            if (f.path("path").asString().equals("show-a.txt")) {
                assertEquals("deleted", f.path("changeType").asString(), third.toString());
                assertTrue(f.path("beforeContent").asString().contains("show 内容 A 改"), third.toString());
                assertTrue(f.path("afterContent").asString().isEmpty(), "删除 after 应为空: " + third);
                sawDeleted = true;
            }
        }
        assertTrue(sawDeleted, "应含删除项: " + third);

        // 根提交(log 倒序,最旧的根提交在最后一条):before 为空 + created。
        JsonNode commits = log(50).path("commits");
        String rootId = null;
        for (JsonNode cnode : commits) {
            rootId = cnode.path("id").asString(); // 遍历到最后一条 = 最旧
        }
        JsonNode sRoot = show(rootId);
        boolean sawRootCreated = false;
        for (JsonNode f : sRoot.path("files")) {
            if (f.path("path").asString().equals("show-a.txt")) {
                assertEquals("created", f.path("changeType").asString(), "根提交应 created: " + sRoot);
                assertTrue(f.path("beforeContent").asString().isEmpty(), "根提交父版本应不存在: " + sRoot);
                assertTrue(f.path("afterContent").asString().contains("show 内容 A"), sRoot.toString());
                sawRootCreated = true;
            }
        }
        assertTrue(sawRootCreated, "根提交应含 show-a.txt: " + sRoot);
    }

    @Test
    void showBadParamsRejected() {
        // 缺 commit → BadParams(参数层拒绝)
        assertThrows(BadParamsException.class, () -> service.show(FakeRpc.of(Json.obj())));
        // 不存在的 commit → BadParams(提交不存在)
        assertThrows(BadParamsException.class, () -> service.show(
                FakeRpc.of(Json.obj().put("commit", "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"))));
    }

    // ---- discard(回归:已删除/丢失文件须能被恢复,原 resolveExisting 误报 NOT_FOUND) ----

    @Test
    void discardRestoresDeletedFile() throws Exception {
        String rel = "novels/三国/修炼体系.md";
        write(ws, rel, "修炼内容");
        commit("提交修炼体系");
        Files.delete(ws.resolve(rel));
        JsonNode st = status();
        assertTrue(st.path("missing").toString().contains("修炼体系") // 已删除路径进 missing
                        || st.path("removed").toString().contains("修炼体系"),
                "状态应标记该文件已删除/丢失: " + st);

        FakeRpc rpc = FakeRpc.of(Json.obj().set("paths", Json.arr().add(rel)));
        service.discard(rpc);
        assertTrue(rpc.result != null, "discard 应成功: " + rpc.errMessage);
        assertTrue(rpc.result.path("discarded").toString().contains("修炼体系"),
                "已删除文件应被恢复而非 NOT_FOUND: " + rpc.result);
        assertTrue(Files.exists(ws.resolve(rel)), "放弃更改后文件应重新存在");
        assertEquals("修炼内容", Files.readString(ws.resolve(rel), StandardCharsets.UTF_8));
    }

    // ---- commit 跳过陈旧勾选路径(回归:unmatched pathspec 曾阻断整体提交) ----

    @Test
    void commitSkipsStaleSelectedPaths() throws Exception {
        // 勾选「新增(未跟踪)」后文件又被删除:git status 对其彻底不可见,
        // git add -A -- <paths> 会因 unmatched pathspec 整体失败,应跳过陈旧路径提交其余选中项。
        String gone = "novels/三国/星辉一体论·草案.md";
        write(ws, gone, "草稿");
        Files.delete(ws.resolve(gone));
        write(ws, "gitfile.txt", "第三版");
        JsonNode s = status();
        assertFalse(s.toString().contains("星辉一体论"), "已删除的未跟踪文件不应出现在状态里: " + s);

        // 混合陈旧路径提交:应成功提交有效部分。
        FakeRpc mixed = FakeRpc.of(Json.obj().put("message", "跳过陈旧路径")
                .set("paths", Json.arr().add("gitfile.txt").add(gone)));
        service.commit(mixed);
        assertTrue(mixed.result != null, "混合陈旧路径的提交应成功: " + mixed.errMessage);
        assertFalse(status().toString().contains("gitfile.txt"), "选中文件应已提交");

        // 选中路径全部已无变更:清晰报 BadParams(绝不静默回退成全量提交)。
        FakeRpc stale = FakeRpc.of(Json.obj().put("message", "全陈旧")
                .set("paths", Json.arr().add(gone)));
        assertThrows(BadParamsException.class, () -> service.commit(stale));
    }

    // ---- clone 参数防线 ----

    @Test
    void cloneRequiresUrl() {
        // 缺 url → BadParams(strParam 层);以 - 开头的 url → 参数注入防线拒绝。
        assertThrows(BadParamsException.class, () -> service.clone(FakeRpc.of(Json.obj())));
        assertThrows(BadParamsException.class, () -> service.clone(
                FakeRpc.of(Json.obj().put("url", "--upload-pack=evil"))));
    }
}
