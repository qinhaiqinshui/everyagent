package dev.everyagent.worker;

import dev.everyagent.contract.frame.Frames;
import dev.everyagent.contract.ids.Ids;
import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.registry.ChatModelEnhancerRegistry;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.ConfigStore;
import dev.everyagent.worker.modules.ConfigStore.ResolvedConfig;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.worker.config.ChatModelFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5 验收:fs(沙箱)模块 + workspace 注册表 —— 文件读写全走管道、越界路径被拒、
 * 大文件分批、多工作区注册;用 @Order 保证断言序(类内共享工作区状态)。
 *
 * <p>原 FsGitModuleTest 的 git.* 段已迁往 git 插件自持测试
 * ({@code every-agent-plugins/git/.../GitServiceIntegrationTest},直驱 GitService,
 * 不再依赖「worker 模块目录下必须能扫到 every-agent-plugins/」这一运行前提——
 * git RPC 由插件注册,Maven 在 worker 模块目录跑测试时内置插件源码树不可达,
 * git.* 一律 UNKNOWN_METHOD,该前提在多数环境不成立)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FsModuleTest {

    private static final String KEY = "test-key-fsmodule";
    /** 每次运行唯一目录:避免清理旧状态的 Windows 删除竞态。 */
    private static final Path WS = TestCleanup.register(
            Path.of("target/test-ws-fsmodule-" + System.nanoTime()));
    /** 系统目录(每次运行唯一,@AfterAll 统一清理)。 */
    private static final Path HOME_DIR = TestCleanup.register(
            Path.of("target/test-home-fsmodule-" + System.nanoTime()).toAbsolutePath().normalize());
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
        ChatModelFactory fakeModelFactory(WorkerProperties props,
                ChatModelEnhancerRegistry enhancerRegistry,
                ConfigStore configStore) {
            return new ChatModelFactory(props, enhancerRegistry, configStore) {
                @Override
                public org.springframework.ai.chat.model.ChatModel build(ResolvedConfig cfg,
                        org.springframework.ai.openai.OpenAiChatOptions options, String agentId,
                        dev.everyagent.plugin.api.model.EventEmitter events) {
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
        r.add("worker.worker-id", () -> "test-worker-fsmodule");
        r.add("worker.hub-initial-backoff-ms", () -> "100");
        r.add("worker.hub-max-backoff-ms", () -> "300");
        // 系统目录每次运行唯一:models.json/workspace.json 不落真实用户主目录,也不跨运行串状态
        r.add("worker.home-dir", () -> HOME_DIR.toString());
        r.add("worker.workspace-root", () -> WS.toString());
    }

    @BeforeAll
    static void createWorkspace() throws Exception {
        Files.createDirectories(WS);
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
                + KEY + "\",\"clientId\":\"fe-fsmodule\"}");
        fe.await(t -> t.contains("\"type\":\"welcome\""), "welcome");
        fe.send("{\"type\":\"sub\",\"channel\":\"" + Channels.workerEvt(k, workerProps.getWorkerId()) + "\"}");
    }

    @AfterEach
    void tearDown() {
        if (fe != null) {
            fe.close();
        }
    }

    @AfterAll
    static void cleanupDirs() {
        TestCleanup.deleteAll();
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

    /**
     * fs.list 条目 stat 容错(回归:Windows 上 WSL/npm 生成的 LX symlink reparse 点,
     * Win32 跟随链接读属性报「系统无法访问此文件」,曾把整个 fs.list 打成 INTERNAL、
     * 搜索侧边栏文件名搜索整树报错):跟随读失败的条目不再拖垮枚举——回退 NOFOLLOW
     * 读链接自身,按 dir:false 普通文件条目返回。用悬空符号链接构造同类失败(跟随读
     * 抛 NoSuchFileException);无符号链接权限的环境(Windows 非开发者模式)跳过。
     */
    @Test
    @Order(13)
    void fsListToleratesUnreadableEntry() throws Exception {
        Path dir = WS.resolve("broken-links");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ok.txt"), "x");
        try {
            Files.createSymbolicLink(dir.resolve("dangling"), dir.resolve("missing-target"));
        } catch (java.io.IOException | UnsupportedOperationException e) {
            Assumptions.abort("当前环境无符号链接权限: " + e);
        }
        String l = rpc("fs.list", p("{\"path\":\"broken-links\"}"));
        assertTrue(l.contains("rpc.ok"), l);
        assertTrue(l.contains("ok.txt") && l.contains("dangling"), l);
        assertTrue(l.contains("\"dir\":false"), l);
    }

    @Test
    @Order(11)
    void sandboxPathsDenied() {
        assertTrue(rpc("fs.read", p("{\"path\":\"../escape.txt\"}")).contains("SANDBOX_DENIED"));
        String b64 = Base64.getEncoder().encodeToString("x".getBytes());
        // 越界用平台分隔符构造(Windows 反斜杠/Linux 正斜杠都须触发 SANDBOX_DENIED),
        // 反斜杠需 JSON 转义;不硬编码 Windows 路径语法,保证测试跨平台可移植
        String escape = (".." + java.io.File.separator + ".." + java.io.File.separator + "x.txt")
                .replace("\\", "\\\\");
        assertTrue(rpc("fs.write", p("{\"path\":\"" + escape + "\",\"contentBase64\":\"" + b64 + "\"}"))
                .contains("SANDBOX_DENIED"));
        // 工作区外的绝对路径一律拒(allowed 校验先于存在性,用不存在的路径即可,
        // 不依赖 C:/Windows 这类 Windows 专属盘符路径)
        assertTrue(rpc("fs.read", p("{\"path\":\"/definitely-outside-everyagent.txt\"}"))
                .contains("SANDBOX_DENIED"));
        assertTrue(rpc("fs.delete", p("{\"path\":\".\"}")).contains("SANDBOX_DENIED"));
    }

    /**
     * fs.revealInOs 参数与沙箱校验:与读同款 jailed(resolveExisting)——越界路径拒收、
     * 不存在路径 NOT_FOUND。两条断言跨 OS 确定性成立;成功路径依赖宿主桌面环境
     * (explorer/Finder/xdg-open),不在测试中断言。
     */
    @Test
    @Order(14)
    void fsRevealInOsValidated() {
        String denied = rpc("fs.revealInOs", p("{\"path\":\"../escape.txt\"}"));
        assertTrue(denied.contains("SANDBOX_DENIED"), denied);
        String missing = rpc("fs.revealInOs", p("{\"path\":\"no-such-file.txt\"}"));
        assertTrue(missing.contains("NOT_FOUND"), missing);
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

    // ---- workspace(架构 §5.9:多工作区注册表,fs/task.run 每调用显式指定)----

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
        assertTrue(!w.contains("wsKey"), "wsKey 已随存储维度移除: " + first);
        assertTrue(Files.exists(workerProps.resolveWorkspacesDir().resolve("workspaces.json")),
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
        // 系统目录的父目录必然是其祖先:不依赖「项目位于 user.home 之下」的机器布局
        String ancestor = Json.write(Json.obj().put("path", ".")
                .put("workspace", workerProps.resolveHomeDir().getParent().toString()));
        assertTrue(rpc("fs.list", ancestor).contains("SANDBOX_DENIED"), "系统目录祖先被拒");
    }

    @Test
    @Order(32)
    void fsDoesNotRegisterTaskRunDoes() {
        Path stranger = TestCleanup.register(
                Path.of("target/test-ws-stranger-" + System.nanoTime()).toAbsolutePath());
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
        String workspaceId = null;
        for (JsonNode wsn : Json.parse(list2).path("payload").path("result").path("workspaces")) {
            if (stranger.normalize().toString().equals(wsn.path("root").asString())) {
                registered = true;
                workspaceId = wsn.path("id").asString(null);
            }
        }
        assertTrue(registered, "task.run 注册工作区: " + list2);
        // 任务数据住系统目录 workspaces/<workspaceId>/tasks/<taskId>/(不落工作区)
        // workspaceId 由注册表给出:非默认工作区不复用 defaultworkspace 的 id。
        assertTrue(workspaceId != null && !workspaceId.isEmpty(), "注册项带 workspaceId: " + list2);
        assertTrue(Files.isRegularFile(workerProps.resolveWorkspacesDir().resolve(workspaceId)
                .resolve("tasks").resolve(taskId).resolve("meta.json")),
                "任务数据住系统目录 workspaces/<workspaceId>/tasks/<taskId>,不落工作区");
        rpc("task.cancel", "{\"taskId\":\"" + taskId + "\"}");
    }

    // ---- 帮助方法 ----

    /** 注入 workspace 参数(fs 每调用必填,本类共享工作区 WS)。 */
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

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
