package dev.everyagent.worker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.everyagent.contract.frame.Frames;
import dev.everyagent.contract.ids.Ids;
import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.config.WorkerProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bug 复现（2026-11 t_5v9a / sub_5v917）：子 agent 在瞬时错误退避「读秒」期间被
 * stop_agent 停止后，读秒到期不得重订模型——否则旧流「复活」，与重新派发的新运行并发。
 *
 * <p>本版用<b>真实 openai-java 桥</b>（OpenAiChatModel + okhttp + Flux.create 异步桥,
 * 即生产同款模型层）打测试内嵌的 mock OpenAI SSE 服务器,完整覆盖生产路径:
 * <ol>
 *   <li>主 agent 请求(user 含 SUBSTOP:) → mock 回 run_agent 工具调用;</li>
 *   <li>子 agent 首次模型请求(user 含 RETRYIO:) → mock <b>挂起不回</b>(复刻网关静默,
 *       t_5v9a 空窗) → FirstChunkWatchdog 超时 → TransientErrorRetry 退避读秒(8s);</li>
 *   <li>主 agent 第二轮收到 run_agent 结果 → mock 延迟 4s 回 stop_agent 调用
 *       (此时子 agent 已在读秒第 ~2 tick) → 主 agent 停止子 agent → 收口 done;</li>
 *   <li>等读秒走完 + buffer,断言:RETRYIO 分支不发生第二次 HTTP 请求(读秒被取消)。</li>
 * </ol>
 * 复现(bug)时:读秒走完旧流重订 → RETRYIO 第 2 次 HTTP 请求 → 旧流恢复产出。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class StopDuringBackoffRetryE2eTest {

    private static final String KEY = "test-key-stopbackoff";
    private static final AtomicLong REQ = new AtomicLong();
    private static final String MODEL_ID = "model-stopbackoff-test";

    private static final Path WS = TestCleanup.register(
            Path.of("target/test-ws-stopbackoff-" + System.nanoTime()).toAbsolutePath().normalize());
    private static final Path HOME_DIR = TestCleanup.register(
            Path.of("target/test-home-stopbackoff-" + System.nanoTime()).toAbsolutePath().normalize());

    private static final int PORT = freePort();

    // ── mock 服务器状态（RETRYIO 分支 = 子 agent 模型流） ──
    /** RETRYIO 请求总数：1 = 首次（挂起），≥2 = 读秒后重订（旧流复活铁证）。 */
    static final AtomicInteger RETRYIO_REQS = new AtomicInteger();
    /** 每次 RETRYIO 请求到达时刻（毫秒），断言「stop 之后不得有新请求」用。 */
    static final java.util.List<Long> RETRYIO_AT_MS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private static int freePort() {
        try (var s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 从测试模块工作目录向上找含 every-agent-plugins 的仓库根（内置插件源码目录）。 */
    private static String builtinPluginsDir() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++) {
            Path candidate = dir.resolve("every-agent-plugins");
            if (Files.isDirectory(candidate)) {
                return candidate.toString();
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未找到 every-agent-plugins（内置插件目录）");
    }

    // ───────────────────────── mock OpenAI SSE 服务器 ─────────────────────────

    private static final HttpServer server;
    private static final ExecutorService serverPool;

    static {
        try {
            serverPool = Executors.newCachedThreadPool();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(serverPool);
            server.createContext("/", StopDuringBackoffRetryE2eTest::handle);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void resetMockState() {
        RETRYIO_REQS.set(0);
    }

    @AfterAll
    static void stopMockServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverPool != null) {
            serverPool.shutdownNow();
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        String body;
        try (var is = ex.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        System.out.println("[mock] " + ex.getRequestMethod() + " " + ex.getRequestURI()
                + " bodyLen=" + body.length());
        try {
            System.out.println("[mock] waits=" + countWaitResults(Json.parse(body).path("messages"))
                    + " last=" + lastPreview(body));
            JsonNode req = Json.parse(body);
            JsonNode messages = req.path("messages");
            JsonNode last = messages.size() > 0 ? messages.get(messages.size() - 1) : null;
            String lastContent = last == null ? "" : contentText(last);
            // 全量消息文本（抽 subId 用：工具结果文本里含「子 agent 已启动: sub_xxx」）
            StringBuilder all = new StringBuilder();
            for (JsonNode m : messages) {
                all.append(contentText(m)).append('\n');
            }

            if (lastContent.contains("RETRYIO:")) {
                int n = RETRYIO_REQS.incrementAndGet();
                RETRYIO_AT_MS.add(System.currentTimeMillis());
                if (n <= 4) {
                    // 心跳挂起（复刻 t_5v9a 网关形态）：连接建立、SSE 保活注释行持续下发,
                    // 但永不产数据 chunk —— okhttp 字节级读超时被无限续命,只有
                    // FirstChunkWatchdog 能超时中断 → 退避读秒。前 4 次全部挂起
                    // (初始 1 次 + 重试 3 次),子 agent 走完 3 波读秒也不完成 ——
                    // stopAll 必落在某波读秒中段(强制复现形态 B)。cancel 后写失败即退出。
                    heartbeatHang(ex);
                    return;
                }
                // 重订 #5+（兜底）：正常回答
                sse(ex, List.of(
                        textDelta("重试成功"),
                        finishDelta("stop")));
                return;
            }
            if (lastContent.contains("SUBSTOP:")) {
                sse(ex, List.of(
                        toolCallDelta("call-sub-1", "run_agent",
                                "{\"input\":\"RETRYIO: 请处理这个子任务\",\"title\":\"重试读秒子任务\"}"),
                        finishDelta("tool_calls")));
                return;
            }
            // 以下按「最后一条消息」(工具结果)分派——历史消息全文匹配会命中旧轮文本导致循环。
            if (lastContent.contains("子 agent 已启动")) {
                // 第 1 步：wait_agents 3s（子 agent 此刻在看门狗超时后的第 1 次读秒中）
                sse(ex, List.of(
                        toolCallDelta("call-wait-1", "wait_agents", "{\"timeoutMs\":3000}"),
                        finishDelta("tool_calls")));
                return;
            }
            int waits = countWaitResults(messages);
            if (lastContent.contains("waitStatus") && waits <= 3) {
                // 第 2~4 步：继续 wait_agents 3s —— 第 3 次(相同参数)被死循环守卫拒,
                // 主 agent 随即收口 → 任务收口等待(~11s)超时 → 级联 stopAll 停子 agent
                // (与用户点停止/stop_agent 同一停止路径),落点恰在第 2 次读秒中段。
                sse(ex, List.of(
                        toolCallDelta("call-wait-" + (waits + 1), "wait_agents", "{\"timeoutMs\":3000}"),
                        finishDelta("tool_calls")));
                return;
            }
            if (lastContent.contains("waitStatus") && waits >= 4) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("(sub[_-][A-Za-z0-9]+)").matcher(allText(messages));
                assertTrue(m.find(), "会话应含 subId: " + allText(messages));
                String subId = m.group(1);
                sse(ex, List.of(
                        toolCallDelta("call-stop-1", "stop_agent",
                                "{\"agentId\":\"" + subId + "\"}"),
                        finishDelta("tool_calls")));
                return;
            }
            if (lastContent.contains("死循环预警") || lastContent.contains("死循环")) {
                // 死循环守卫拒了第 3 次 wait —— 主 agent 即将收口(级联 stopAll 在 ~11s 后)
                sse(ex, List.of(
                        textDelta("收到死循环预警"),
                        finishDelta("stop")));
                return;
            }
            if (lastContent.contains("已请求停止")) {
                sse(ex, List.of(
                        textDelta("主 agent 已停止子 agent"),
                        finishDelta("stop")));
                return;
            }
            sse(ex, List.of(
                    textDelta("回声:" + (lastContent.length() > 20
                            ? lastContent.substring(0, 20) : lastContent)),
                    finishDelta("stop")));
        } catch (IOException | RuntimeException e) {
            // 客户端断开等：尽力关流
            try {
                ex.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static String contentText(JsonNode m) {
        JsonNode c = m.path("content");
        if (c.isTextual()) {
            return c.asString();
        }
        if (c.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : c) {
                if (part.isObject() && part.path("type").asString("").equals("text")) {
                    sb.append(part.path("text").asString(""));
                }
            }
            return sb.toString();
        }
        return "";
    }

    /** 会话中 wait_agents 工具结果条数（content 含 waitStatus 的 tool 消息）。 */
    private static int countWaitResults(JsonNode messages) {
        int n = 0;
        for (JsonNode m : messages) {
            if ("tool".equals(m.path("role").asString("")) && contentText(m).contains("waitStatus")) {
                n++;
            }
        }
        return n;
    }

    /** 最后一条消息文本预览（mock 分派日志用）。 */
    private static String lastPreview(String body) {
        try {
            JsonNode messages = Json.parse(body).path("messages");
            JsonNode last = messages.size() > 0 ? messages.get(messages.size() - 1) : null;
            String s = last == null ? "" : contentText(last);
            s = s.replace("\n", " ");
            return s.substring(0, Math.min(70, s.length()));
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** 全量消息文本拼接（抽 subId 用）。 */
    private static String allText(JsonNode messages) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode m : messages) {
            sb.append(contentText(m)).append('\n');
        }
        return sb.toString();
    }

    /** SSE 心跳挂起：响应头 + 每秒一条保活注释行（不产数据 chunk），20s 后补数据。 */
    private static void heartbeatHang(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Connection", "close");
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream os = ex.getResponseBody()) {
            for (int i = 0; i < 20; i++) {
                Thread.sleep(1_000);
                os.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            os.write(("data: " + textDelta("迟到的首片") + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.write(("data: " + finishDelta("stop") + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 客户端已断开（cancel/断线）：挂起结束
        }
    }

    /** SSE 输出：data: {chunk}\n\n ×N + data: [DONE]。 */
    private static void sse(HttpExchange ex, List<String> chunks) throws IOException {
        sseDelayed(ex, chunks, 0);
    }

    private static void sseDelayed(HttpExchange ex, List<String> chunks, long delayMs) throws IOException {
        // Connection: close —— 禁用 keep-alive,避免 JDK HttpServer chunked 流的连接复用竞态
        // (okhttp 复用半关闭连接会报 OpenAIIoException: Stream failed)。
        ex.getResponseHeaders().set("Connection", "close");
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream os = ex.getResponseBody()) {
            for (int i = 0; i < chunks.size(); i++) {
                if (i > 0 && delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                os.write(("data: " + chunks.get(i) + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    private static String textDelta(String text) {
        return "{\"id\":\"ch-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
                + "\"model\":\"mock\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"role\":\"assistant\",\"content\":" + quote(text) + "},"
                + "\"finish_reason\":null}]}";
    }

    private static String toolCallDelta(String id, String name, String argsJson) {
        String argsEscaped = quote(argsJson);
        return "{\"id\":\"ch-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
                + "\"model\":\"mock\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                + "\"tool_calls\":[{\"index\":0,\"id\":\"" + id + "\",\"type\":\"function\","
                + "\"function\":{\"name\":\"" + name + "\",\"arguments\":" + argsEscaped + "}}]},"
                + "\"finish_reason\":null}]}";
    }

    private static String finishDelta(String reason) {
        return "{\"id\":\"ch-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
                + "\"model\":\"mock\",\"choices\":[{\"index\":0,\"delta\":{},"
                + "\"finish_reason\":\"" + reason + "\"}]}";
    }

    /** JSON 字符串字面量转义。 */
    private static String quote(String s) {
        return Json.write(s);
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
        // 注意：不 override ChatModelFactory —— 走真实 openai-compat 构建
        // (OpenAiChatModel + okhttp + openai-java 异步桥 = 生产同款模型层)
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("server.port", () -> String.valueOf(PORT));
        r.add("worker.hubs[0].url", () -> "ws://127.0.0.1:" + PORT + "/fakehub");
        r.add("worker.hubs[0].api-key", () -> KEY);
        r.add("worker.hubs[0].hub-key", () -> "test-hub-key");
        r.add("worker.worker-id", () -> "test-worker-stopbackoff");
        r.add("worker.hub-initial-backoff-ms", () -> "100");
        r.add("worker.hub-max-backoff-ms", () -> "300");
        r.add("worker.limits.max-concurrent-tasks", () -> "2");
        // 瞬时错误重试：3 次、fixed 10s 退避（读秒 10 tick,给 stop 留足落点余量）
        r.add("worker.retry.max-request-retries", () -> "3");
        r.add("worker.retry.backoff-base-ms", () -> "10000");
        r.add("worker.retry.strategy", () -> "fixed");
        // 首帧看门狗 1s：心跳挂起流 1s 内无首 chunk 即超时中断 → 进入退避读秒（复刻 t_5v9a）。
        // next 间隔放宽：主 agent 停止脚本的延迟片不受间隔超时误杀。
        r.add("worker.model-first-chunk-timeout-ms", () -> "1000");
        r.add("worker.model-timeout-ms", () -> "120000");
        r.add("worker.home-dir", () -> HOME_DIR.toString());
        r.add("worker.workspace-root", () -> WS.toString());
        // 关键：让内置插件（transient-error-retry / subagent 等）真正加载
        r.add("worker.builtin-plugins-dir", StopDuringBackoffRetryE2eTest::builtinPluginsDir);
        r.add("worker.sandbox.type", () -> "none");
        // 真实 openai-compat 模型：base-url 指向本测试内嵌 mock 服务器
        r.add("worker.models[0].config-id", () -> MODEL_ID);
        r.add("worker.models[0].name", () -> "测试模型");
        r.add("worker.models[0].provider", () -> "openai-compat");
        r.add("worker.models[0].base-url",
                () -> "http://127.0.0.1:" + (server == null ? 0 : server.getAddress().getPort()));
        r.add("worker.models[0].model", () -> "mock");
        r.add("worker.models[0].api-key", () -> "sk-mock");
    }

    @Autowired
    WorkerProperties workerProps;

    @Autowired
    HubPool pool;

    @Autowired
    dev.everyagent.worker.task.TaskStore taskStore;

    private final String k = Ids.ownerKey(KEY);
    private WsTestClient fe;

    @BeforeEach
    void setUp() throws java.io.IOException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!pool.anyConnected()) {
            assertTrue(System.currentTimeMillis() < deadline, "hub 未在 20s 内连上 FakeHub");
            sleep(50);
        }
        fe = WsTestClient.connect(URI.create("ws://127.0.0.1:" + PORT + "/fakehub"));
        hello(fe);
        sub(fe, Channels.tasks(k, workerProps.getWorkerId()));
        sub(fe, Channels.workerEvt(k, workerProps.getWorkerId()));
        Files.createDirectories(WS);
    }

    @AfterAll
    static void cleanupDirs() {
        TestCleanup.deleteAll();
    }

    @Test
    void stopDuringBackoffCancelsRetryResubscription() {
        String taskId = create("SUBSTOP: 派发并停止");
        // 子 agent 时间线(订阅 t0)：1s 看门狗超时 → 读秒 10s(t1~11) → 重试#2 心跳挂起
        // 1s 超时(t12) → 第 2 次读秒(t12~22)。
        // 主 agent：run_agent → wait_agents×2 → 第 3 次相同 wait 被死循环守卫拒 → 提前收口
        // → 任务收口等待(~11s)超时/子 agent 落定 → 级联 stopAll 停子 agent(与用户停止同路径),
        // stopAll 落点 ≈ 子 agent 第 2 次读秒中段。
        // 轮询磁盘事件文件等子 agent 终态(agent.done 落盘 = stopped/completed 定稿时刻)。
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (subAgentDoneOnDisk(taskId)) {
                break;
            }
            sleep(300);
        }
        assertTrue(subAgentDoneOnDisk(taskId),
                "60s 内子 agent 未落终态 reqs=" + RETRYIO_REQS.get());
        long finalAt = System.currentTimeMillis();
        // 终态后再等读秒走完 + 重订窗口(残余读秒最长 10s + 重订 1s + 轮询粒度容差)
        sleep(12_000);

        long lastRetryAt = RETRYIO_AT_MS.isEmpty() ? 0 : RETRYIO_AT_MS.get(RETRYIO_AT_MS.size() - 1);
        int reqs = RETRYIO_REQS.get();
        System.out.println("[test] finalAt=" + finalAt + " reqs=" + reqs
                + " retryAt=" + RETRYIO_AT_MS);
        // 修复后：终态(含 stopAll 的 markStopped)后读秒到期被终态闸门拦截,不再有新请求;
        // bug 时：读秒走完旧流重订（终态后仍有新请求/reqs=3）
        assertTrue(lastRetryAt <= finalAt,
                "子 agent 终态后读秒到期不得重订模型（旧流复活）reqs=" + reqs
                        + " finalAt=" + finalAt + " retryAt=" + RETRYIO_AT_MS);
    }

    /** 磁盘上子 agent 事件文件(sub_*.jsonl)是否已含 agent.done(终态定稿)。 */
    private boolean subAgentDoneOnDisk(String taskId) {
        try {
            Path dir = taskStore.dirOf(taskId);
            if (!Files.isDirectory(dir)) {
                return false;
            }
            try (var files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.getFileName().toString().startsWith("sub_")
                        && p.getFileName().toString().endsWith(".jsonl")).toList()) {
                    if (Files.readString(f).contains("\"event\":\"agent.done\"")) {
                        return true;
                    }
                }
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // 目录未建/半写:继续轮询
        }
        return false;
    }

    // ---- 帮助方法（与 FileReferenceAttachmentTest 同款 harness） ----

    private void hello(WsTestClient c) {
        c.send("{\"type\":\"hello\",\"ver\":" + Frames.PROTOCOL_VERSION + ",\"role\":\"frontend\",\"apiKey\":\""
                + KEY + "\",\"clientId\":\"fe-stopbackoff\"}");
        c.await(t -> t.contains("\"type\":\"welcome\""), "welcome");
    }

    private void sub(WsTestClient c, String channel) {
        c.send("{\"type\":\"sub\",\"channel\":\"" + channel + "\"}");
    }

    private String pub(String channel, String event, String payloadJson) {
        return "{\"type\":\"pub\",\"mid\":\"m-" + REQ.incrementAndGet() + "\",\"channel\":\"" + channel
                + "\",\"event\":\"" + event + "\",\"ts\":" + System.currentTimeMillis()
                + ",\"payload\":" + payloadJson + "}";
    }

    private String rpc(String method, String paramsJson) {
        String reqId = "r-" + REQ.incrementAndGet();
        fe.send(pub(Channels.workerCmd(k, workerProps.getWorkerId()), "rpc",
                "{\"reqId\":\"" + reqId + "\",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}"));
        return fe.await(t -> t.contains(reqId) && (t.contains("rpc.ok") || t.contains("rpc.err")),
                "rpc " + method);
    }

    private String create(String input) {
        String resp = rpc("task.run", Json.write(Json.obj()
                .put("input", input).put("configId", MODEL_ID).put("workspace", WS.toString())));
        assertTrue(resp.contains("rpc.ok"), "创建失败: " + resp);
        return Json.parse(resp).path("payload").path("result").path("taskId").asString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
