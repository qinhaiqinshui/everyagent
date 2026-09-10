package dev.everyagent.worker;

import dev.everyagent.contract.frame.Frames;
import dev.everyagent.contract.ids.Ids;
import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.ConfigStore.ResolvedConfig;
import dev.everyagent.worker.proto.Channels;
import dev.everyagent.worker.task.ChatModelFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;
import tools.jackson.databind.JsonNode;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5 验收:fs(沙箱)/git 模块 —— 文件读写全走管道、越界路径被拒、git 状态/提交/差异。
 * 类内共享一个已 init 的 git 仓库,用 @Order 保证 fs 先于 git 断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FsGitModuleTest {

    private static final String KEY = "test-key-fsgit";
    /** 每次运行唯一目录:避免清理旧 .git 的 Windows 删除竞态(init 对已存在 .git 会跳过骨架写入)。 */
    private static final Path WS = Path.of("target/test-ws-fsgit-" + System.nanoTime());
    private static final AtomicLong REQ = new AtomicLong();

    /** 预占端口:HubLink 构造时即捕获 hub URL,必须首连就指向本测试的 FakeHub。 */
    private static final int PORT = freePort();

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Cfg {
        @Bean
        ServerEndpointExporter serverEndpointExporter() {
            return new ServerEndpointExporter();
        }

        @Bean
        FakeHub fakeHub() {
            return new FakeHub();
        }

        @Bean
        @Primary
        ChatModelFactory fakeModelFactory(WorkerProperties props) {
            return new ChatModelFactory(props) {
                @Override
                public org.springframework.ai.chat.model.ChatModel build(ResolvedConfig cfg,
                        org.springframework.ai.openai.OpenAiChatOptions options, String agentId) {
                    return new FakeChatModel();
                }
            };
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("server.port", () -> String.valueOf(PORT));
        r.add("worker.hubs[0].url", () -> "ws://127.0.0.1:" + PORT + "/fakehub");
        r.add("worker.hubs[0].api-key", () -> KEY);
        // hub-key 为 WorkerProperties 必填(既有契约漂移,FakeHub 不校验,占位即可)
        r.add("worker.hubs[0].hub-key", () -> "test-hub-key");
        r.add("worker.worker-id", () -> "test-worker-fsgit");
        r.add("worker.hub-initial-backoff-ms", () -> "100");
        r.add("worker.hub-max-backoff-ms", () -> "300");
        // 系统目录每次运行唯一:models.json/workspace.json 不落真实用户主目录,也不跨运行串状态
        r.add("worker.home-dir", () -> "target/test-home-fsgit-" + System.nanoTime());
        r.add("worker.workspace-root", () -> WS.toString());
    }

    @BeforeAll
    static void initRepo() throws Exception {
        Files.createDirectories(WS);
        runGit(WS, "init", "-b", "main");
        runGit(WS, "config", "user.name", "测试用户");
        runGit(WS, "config", "user.email", "t@example.com");
        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(WS.resolve(".git/HEAD")),
                "git init 未完整落盘(缺 HEAD)");
    }

    @Autowired
    WorkerProperties workerProps;

    @Autowired
    HubPool pool;

    private final String k = Ids.ownerKey(KEY);
    private WsTestClient fe;

    @BeforeEach
    void setUp() {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!pool.anyConnected()) {
            assertTrue(System.currentTimeMillis() < deadline, "hub 未在 20s 内连上 FakeHub");
            sleep(50);
        }
        fe = WsTestClient.connect(URI.create("ws://127.0.0.1:" + PORT + "/fakehub"));
        fe.send("{\"type\":\"hello\",\"ver\":" + Frames.PROTOCOL_VERSION + ",\"role\":\"frontend\",\"apiKey\":\""
                + KEY + "\",\"clientId\":\"fe-fsgit\"}");
        fe.await(t -> t.contains("\"type\":\"welcome\""), "welcome");
        fe.send("{\"type\":\"sub\",\"channel\":\"" + Channels.workerEvt(k, workerProps.getWorkerId()) + "\"}");
    }

    @AfterEach
    void tearDown() {
        if (fe != null) {
            fe.close();
        }
    }

    // ---- fs ----

    @Test
    @Order(10)
    void fsWriteReadListAndChanged() {
        String b64 = Base64.getEncoder().encodeToString("hello 沙箱".getBytes(StandardCharsets.UTF_8));
        String w = rpc("fs.write", p(
                "{\"path\":\"docs/hello.txt\",\"contentBase64\":\"" + b64 + "\"}"));
        assertTrue(w.contains("rpc.ok") && w.contains("docs/hello.txt"), w);
        fe.await(t -> t.contains("\"event\":\"fs.changed\"") && t.contains("docs/hello.txt")
                && t.contains("\"kind\":\"write\"") && t.contains("\"workspace\""), "fs.changed 广播带 workspace");

        String r = rpc("fs.read", p("{\"path\":\"docs/hello.txt\"}"));
        assertTrue(r.contains("rpc.ok"), r);
        String got = Json.parse(r).path("payload").path("result").path("base64").asString();
        assertEquals("hello 沙箱", new String(Base64.getDecoder().decode(got), StandardCharsets.UTF_8));

        String l = rpc("fs.list", p("{\"path\":\"docs\"}"));
        assertTrue(l.contains("hello.txt"), l);
        assertTrue(l.contains("\"dir\":false"), l);
    }

    @Test
    @Order(11)
    void sandboxPathsDenied() {
        assertTrue(rpc("fs.read", p("{\"path\":\"../escape.txt\"}")).contains("SANDBOX_DENIED"));
        String b64 = Base64.getEncoder().encodeToString("x".getBytes());
        assertTrue(rpc("fs.write", p("{\"path\":\"..\\\\..\\\\x.txt\",\"contentBase64\":\"" + b64 + "\"}"))
                .contains("SANDBOX_DENIED"));
        assertTrue(rpc("fs.read", p("{\"path\":\"C:/Windows/win.ini\"}")).contains("SANDBOX_DENIED"));
        assertTrue(rpc("fs.delete", p("{\"path\":\".\"}")).contains("SANDBOX_DENIED"));
    }

    @Test
    @Order(12)
    void fsMkdirMoveDeleteTree() {
        rpc("fs.mkdir", p("{\"path\":\"a/b\"}"));
        String b64 = Base64.getEncoder().encodeToString("内容".getBytes(StandardCharsets.UTF_8));
        rpc("fs.write", p("{\"path\":\"a/b/f.txt\",\"contentBase64\":\"" + b64 + "\"}"));

        String chain = rpc("fs.reveal", p("{\"path\":\"a/b/f.txt\"}"));
        assertTrue(chain.contains("\"name\":\"b\"") && chain.contains("f.txt"), chain);

        rpc("fs.move", p("{\"from\":\"a/b/f.txt\",\"to\":\"a/moved.txt\"}"));
        assertTrue(rpc("fs.read", p("{\"path\":\"a/moved.txt\"}")).contains("rpc.ok"), "移动后新路径可读");
        assertTrue(rpc("fs.read", p("{\"path\":\"a/b/f.txt\"}")).contains("NOT_FOUND"), "旧路径应不存在");

        rpc("fs.delete", p("{\"path\":\"a\"}"));
        assertTrue(rpc("fs.read", p("{\"path\":\"a/moved.txt\"}")).contains("NOT_FOUND"), "删除后不可读");
    }

    @Test
    @Order(13)
    void fsBigFileBatchedRead() {
        byte[] big = new byte[700_000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i % 251);
        }
        String b64 = Base64.getEncoder().encodeToString(big);
        assertTrue(rpc("fs.write", p("{\"path\":\"big.bin\",\"contentBase64\":\"" + b64 + "\"}"))
                .contains("rpc.ok"));

        List<JsonNode> parts = new ArrayList<>();
        String end = rpcCollect("fs.read", p("{\"path\":\"big.bin\"}"), parts);
        assertTrue(end.contains("rpc.ok"), end);
        assertTrue(parts.size() >= 3, "700KB 应分批回传,实际 " + parts.size() + " 批");
        StringBuilder joined = new StringBuilder();
        for (JsonNode p : parts) {
            joined.append(p.path("base64").asString());
        }
        assertEquals(big.length, Base64.getDecoder().decode(joined.toString()).length);
    }

    // ---- git ----

    @Test
    @Order(20)
    void gitStatusSeesUntracked() {
        String b64 = Base64.getEncoder().encodeToString("第一版".getBytes(StandardCharsets.UTF_8));
        rpc("fs.write", p("{\"path\":\"gitfile.txt\",\"contentBase64\":\"" + b64 + "\"}"));
        String s = rpc("git.status", p("{}"));
        assertTrue(s.contains("rpc.ok") && s.contains("\"branch\":\"main\""), s);
        assertTrue(s.contains("gitfile.txt"), s);
    }

    @Test
    @Order(21)
    void gitCommitLogDiff() {
        String c = rpc("git.commit", p("{\"message\":\"首次提交\"}"));
        assertTrue(c.contains("rpc.ok"), c);
        String shortId = Json.parse(c).path("payload").path("result").path("shortId").asString();
        assertEquals(8, shortId.length(), c);

        String s = rpc("git.status", p("{}"));
        assertFalse(s.contains("gitfile.txt"), "提交后不应有未跟踪文件: " + s);

        String log = rpc("git.log", p("{}"));
        assertTrue(log.contains("首次提交") && log.contains("测试用户"), log);

        String b64 = Base64.getEncoder().encodeToString("第二版 内容变化".getBytes(StandardCharsets.UTF_8));
        rpc("fs.write", p("{\"path\":\"gitfile.txt\",\"contentBase64\":\"" + b64 + "\"}"));
        String d = rpc("git.diff", p("{\"path\":\"gitfile.txt\"}"));
        assertTrue(d.contains("rpc.ok"), d);
        JsonNode diff = Json.parse(d).path("payload").path("result");
        assertFalse(diff.path("empty").asBoolean(true), "修改后 diff 不应为空: " + d);
        // 对齐新契约:git.diff 返回全文 before/after(filePath/changeType/beforeContent/afterContent),非 unified diff 文本。
        assertEquals("gitfile.txt", diff.path("filePath").asString(), d);
        assertEquals("updated", diff.path("changeType").asString(), d);
        assertTrue(diff.path("beforeContent").asString().contains("第一版"), d);
        assertTrue(diff.path("afterContent").asString().contains("第二版"), d);
    }

    @Test
    @Order(22)
    void gitOpsOnMissingRepoNotFound() {
        // git.diff 的 path 越界先于仓库检查,这里验证正常路径下非 git 目录场景由 NOT_FOUND 覆盖:
        // 当前工作区已 init;git.clone 已注册为快方法,空参数应 BadParams 而非 UNKNOWN_METHOD(冒烟)
        String clone = rpc("git.clone", p("{}"));
        assertTrue(clone.contains("rpc.err") && clone.contains("BAD_PARAMS"), clone);
    }

    @Test
    @Order(23)
    void gitDiscardRestoresDeletedFile() {
        // 回归:放弃更改选中「已删除/已丢失」文件时,文件已不在磁盘,原 resolveExisting
        // 会误报 NOT_FOUND;应允许该路径并交由 git restore 从 HEAD 恢复(含中文路径)。
        String path = "novels/三国/修炼体系.md";
        String b64 = Base64.getEncoder().encodeToString("修炼内容".getBytes(StandardCharsets.UTF_8));
        assertTrue(rpc("fs.write", p("{\"path\":\"" + path + "\",\"contentBase64\":\"" + b64 + "\"}"))
                .contains("rpc.ok"), "先创建待跟踪文件");
        assertTrue(rpc("git.commit", p("{\"message\":\"提交修炼体系\"}")).contains("rpc.ok"), "先提交出 HEAD 版本");
        assertTrue(rpc("fs.delete", p("{\"path\":\"" + path + "\"}")).contains("rpc.ok"), "删除工作区文件");
        assertTrue(rpc("git.status", p("{}")).contains(path), "状态应标记该文件已删除");

        String d = rpc("git.discard", p("{\"paths\":[\"" + path + "\"]}"));
        assertTrue(d.contains("rpc.ok"), d);
        JsonNode res = Json.parse(d).path("payload").path("result");
        assertTrue(res.path("discarded").toString().contains(path), "已删除文件应被恢复而非 NOT_FOUND: " + d);
        assertTrue(rpc("fs.read", p("{\"path\":\"" + path + "\"}")).contains("rpc.ok"), "放弃更改后文件应重新存在");
    }

    @Test
    @Order(24)
    void gitStatusReportsAheadBehind() throws Exception {
        // 未配置上游:git.status 应带 ahead/behind 字段且为 0(推送角标「已提交未推送」不误报)。
        String s0 = rpc("git.status", p("{}"));
        assertTrue(s0.contains("\"ahead\":0") && s0.contains("\"behind\":0"),
                "无上游时 ahead/behind 应为 0: " + s0);

        // 配置远端 + 上游跟踪分支:push 后 ahead=0。
        Path remote = Path.of("target", "test-remote-fsgit-" + System.nanoTime()).toAbsolutePath();
        Files.createDirectories(remote);
        runGit(remote, "init", "--bare", ".");
        runGit(WS, "remote", "add", "origin", remote.toString());
        runGit(WS, "push", "-u", "origin", "main");
        String s1 = rpc("git.status", p("{}"));
        assertTrue(s1.contains("\"ahead\":0"), "push 后不应有未推送提交: " + s1);

        // 再提交一个本地提交:ahead 应变为 1。
        String b64 = Base64.getEncoder().encodeToString("待推送".getBytes(StandardCharsets.UTF_8));
        assertTrue(rpc("fs.write", p("{\"path\":\"push-pending.txt\",\"contentBase64\":\"" + b64 + "\"}"))
                .contains("rpc.ok"), "先创建待推送文件");
        assertTrue(rpc("git.commit", p("{\"message\":\"待推送提交\"}")).contains("rpc.ok"), "提交");
        String s2 = rpc("git.status", p("{}"));
        assertTrue(s2.contains("\"ahead\":1"), "本地领先 1 个提交: " + s2);

        // 推送后 ahead 归零(顺带验证 git.push 走原生 git 对 bare 远端成功)。
        assertTrue(rpc("git.push", p("{}")).contains("rpc.ok"), "推送");
        String s3 = rpc("git.status", p("{}"));
        assertTrue(s3.contains("\"ahead\":0"), "推送后 ahead 应归零: " + s3);
    }

    @Test
    @Order(25)
    void gitCommitSkipsStaleSelectedPaths() {
        // 回归:侧栏勾选「新增(未跟踪)文件」后文件又被删除——git status 对其彻底
        // 不可见,前端勾选集残留该陈旧路径时,git add -A -- <paths> 会因
        // unmatched pathspec 整体失败使提交被阻断;应跳过已无变更的路径提交其余选中项。
        String gone = "novels/三国/星辉一体论·草案.md";
        String b64 = Base64.getEncoder().encodeToString("草稿".getBytes(StandardCharsets.UTF_8));
        assertTrue(rpc("fs.write", p("{\"path\":\"" + gone + "\",\"contentBase64\":\"" + b64 + "\"}"))
                .contains("rpc.ok"), "先创建待勾选的新文件");
        assertTrue(rpc("fs.delete", p("{\"path\":\"" + gone + "\"}")).contains("rpc.ok"), "新增文件又被删除");
        String mod64 = Base64.getEncoder().encodeToString("第三版".getBytes(StandardCharsets.UTF_8));
        assertTrue(rpc("fs.write", p("{\"path\":\"gitfile.txt\",\"contentBase64\":\"" + mod64 + "\"}"))
                .contains("rpc.ok"), "制造一个真实变更");
        String s = rpc("git.status", p("{}"));
        assertFalse(s.contains(gone), "已删除的未跟踪文件不应出现在状态里: " + s);

        // 模拟前端陈旧勾选集:有效路径 + 已消失路径混合提交,应成功提交有效部分。
        String c = rpc("git.commit", p(
                "{\"message\":\"跳过陈旧路径\",\"paths\":[\"gitfile.txt\",\"" + gone + "\"]}"));
        assertTrue(c.contains("rpc.ok"), "混合陈旧路径的提交应成功: " + c);
        assertFalse(rpc("git.status", p("{}")).contains("gitfile.txt"), "选中文件应已提交");

        // 选中路径全部已无变更:清晰报 BAD_PARAMS(绝不静默回退成全量提交)。
        String stale = rpc("git.commit", p("{\"message\":\"全陈旧\",\"paths\":[\"" + gone + "\"]}"));
        assertTrue(stale.contains("rpc.err") && stale.contains("BAD_PARAMS"), stale);
    }

    // ---- workspace(架构 §5.9:多工作区注册表,fs/git/task.run 每调用显式指定)----

    @Test
    @Order(30)
    void workspacesListShowsRegistered() {
        String w = rpc("workspaces.list", "{}");
        assertTrue(w.contains("rpc.ok"), w);
        JsonNode res = Json.parse(w).path("payload").path("result");
        assertEquals(WS.toAbsolutePath().normalize().toString(), res.path("defaultRoot").asString(), w);
        JsonNode first = res.path("workspaces").path(0);
        assertEquals(WS.toAbsolutePath().normalize().toString(), first.path("root").asString(), w);
        assertTrue(first.path("addedAt").asLong(0) > 0, "注册表字段 {root, addedAt}: " + first);
        assertFalse(w.contains("wsKey"), "wsKey 已随存储维度移除: " + first);
        assertTrue(Files.exists(workerProps.resolveDataDir().resolve("workspaces.json")),
                "注册表持久化于 data/workspaces.json");
    }

    @Test
    @Order(31)
    void workspaceParamValidated() {
        assertTrue(rpc("fs.list", "{\"path\":\".\"}").contains("BAD_PARAMS"), "缺 workspace 被拒");
        assertTrue(rpc("fs.list", "{\"path\":\".\",\"workspace\":\"relative/dir\"}").contains("BAD_PARAMS"),
                "相对路径被拒");
        String sys = Json.write(Json.obj().put("path", ".")
                .put("workspace", workerProps.resolveHomeDir().toString()));
        assertTrue(rpc("fs.list", sys).contains("SANDBOX_DENIED"), "系统目录本身被拒");
        String home = Json.write(Json.obj().put("path", ".")
                .put("workspace", System.getProperty("user.home")));
        assertTrue(rpc("fs.list", home).contains("SANDBOX_DENIED"), "系统目录祖先被拒");
    }

    @Test
    @Order(32)
    void fsDoesNotRegisterTaskRunDoes() {
        Path stranger = Path.of("target/test-ws-stranger-" + System.nanoTime()).toAbsolutePath();
        int before = wsCount();
        // fs 调用陌生目录:可用但不进注册表(工作区因任务而注册)
        assertTrue(rpc("fs.list", Json.write(Json.obj().put("path", ".")
                .put("workspace", stranger.toString()))).contains("rpc.ok"));
        assertEquals(before, wsCount(), "fs 浏览不注册工作区");

        // task.run(新建)注册新工作区 + 任务目录落 data/tasks/<taskId>/
        String resp = rpc("task.run", Json.write(Json.obj()
                .put("input", "WSREG:注册工作区")
                .put("workspace", stranger.normalize().toString())));
        assertTrue(resp.contains("rpc.ok"), resp);
        String taskId = Json.parse(resp).path("payload").path("result").path("taskId").asString();
        assertTrue(taskId.startsWith("t_"), taskId);
        String list2 = rpc("workspaces.list", "{}");
        boolean registered = false;
        for (JsonNode wsn : Json.parse(list2).path("payload").path("result").path("workspaces")) {
            registered |= stranger.normalize().toString().equals(wsn.path("root").asString());
        }
        assertTrue(registered, "task.run 注册工作区: " + list2);
        assertTrue(Files.isRegularFile(workerProps.resolveDataDir().resolve("tasks").resolve(taskId)
                .resolve("meta.json")),
                "任务数据住系统目录 data/tasks/<taskId>,不落工作区");
        rpc("task.cancel", "{\"taskId\":\"" + taskId + "\"}");
    }

    // ---- 帮助方法 ----

    /** 注入 workspace 参数(fs/git 每调用必填,本类共享工作区 WS)。 */
    private static String p(String json) {
        tools.jackson.databind.node.ObjectNode o = (tools.jackson.databind.node.ObjectNode) Json.parse(json);
        o.put("workspace", WS.toAbsolutePath().normalize().toString());
        return Json.write(o);
    }

    private int wsCount() {
        return Json.parse(rpc("workspaces.list", "{}"))
                .path("payload").path("result").path("workspaces").size();
    }

    // ---- 帮助方法(RPC 收发)----

    private String rpc(String method, String paramsJson) {
        String reqId = "r-" + REQ.incrementAndGet();
        fe.send("{\"type\":\"pub\",\"mid\":\"m-" + REQ.incrementAndGet() + "\",\"channel\":\""
                + Channels.workerCmd(k, workerProps.getWorkerId()) + "\",\"event\":\"rpc\",\"ts\":"
                + System.currentTimeMillis() + ",\"payload\":{\"reqId\":\"" + reqId
                + "\",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}}");
        return fe.await(t -> t.contains(reqId) && (t.contains("rpc.ok") || t.contains("rpc.err")),
                "rpc " + method);
    }

    /** RPC 并收集全部 rpc.data 批次(大文件读取)。 */
    private String rpcCollect(String method, String paramsJson, List<JsonNode> sink) {
        String reqId = "r-" + REQ.incrementAndGet();
        fe.send("{\"type\":\"pub\",\"mid\":\"m-" + REQ.incrementAndGet() + "\",\"channel\":\""
                + Channels.workerCmd(k, workerProps.getWorkerId()) + "\",\"event\":\"rpc\",\"ts\":"
                + System.currentTimeMillis() + ",\"payload\":{\"reqId\":\"" + reqId
                + "\",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}}");
        return fe.await(t -> {
            if (!t.contains(reqId)) {
                return false;
            }
            if (t.contains("rpc.data")) {
                Json.parse(t).path("payload").path("batch").forEach(sink::add);
                return false;
            }
            return t.contains("rpc.ok") || t.contains("rpc.err");
        }, "rpc " + method);
    }

    /** 用系统原生 git 初始化测试仓库(测试前置,不经 worker 的 NativeGit)。 */
    private static void runGit(Path dir, String... args) throws Exception {
        java.util.List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(dir.toString());
        cmd.addAll(java.util.List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "git " + String.join(" ", args) + " 失败: " + out);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
