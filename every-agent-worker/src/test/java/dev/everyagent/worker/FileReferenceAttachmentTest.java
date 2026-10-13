package dev.everyagent.worker;

import dev.everyagent.contract.frame.Frames;
import dev.everyagent.contract.ids.Ids;
import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
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
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `@` 文件引用（图片）端到端验收：rawContent 里的 {@code system.workspace_file} opaque token
 * 必须被物化成「（附图：xxx）」正文 + 图片附件，三条投递路径同权：
 * <ol>
 *   <li>首轮（{@code file.reference.process} 875 → consume.input）；</li>
 *   <li>队列轮（运行中输入入队 → queue.loop 续跑 → 同一节点链）；</li>
 *   <li>「插入到当前对话」（输入队列项 → QueueDispatchNode 转投插入队列 → DialogInsertAdvisor
 *       在工具循环下行 drain 直接投给模型，不经过 875 节点——此处必须与轮次路径同一条物化实现）。</li>
 * </ol>
 *
 * <p>与 WorkerIntegrationTest 同款 harness（FakeHub + WsTestClient + FakeChatModel），差异：
 * 显式把 {@code worker.builtin-plugins-dir} 指向仓库 {@code every-agent-plugins}，
 * 让 image-vision（FileReferenceHandler）与 task-input-queue（queue.loop / 插入对话）真正参与运行；
 * 模型替身额外录制每次请求的 Prompt，断言「图片确实以 Media 形态到了模型手里」。
 *
 * <p>同时看护 token payload 解码：用例取<b>中文名</b>文件（`图.png`）——其 UTF-8 payload 的
 * base64 命中 URL-safe 62/63 号字符（`-`/`_`），历史上 {@link SlashTokenEncoder#fromBase64Url}
 * 用「归一化 + getUrlDecoder」组合在此抛异常、被兜底吞成空 payload，导致 @ 引用图片既不注入
 * 附件也不改写正文（粘贴生成的 ASCII 附件名不含 `-`/`_`，恒好 → 「粘贴能识别、@ 失效」）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class FileReferenceAttachmentTest {

    private static final String KEY = "test-key-fileref";
    private static final AtomicLong REQ = new AtomicLong();
    private static final String WORKSPACE_FILE_KIND = "system.workspace_file";
    /** 测试模型配置 id（非池 provider，ChatModelFactory 覆写落到 FakeChatModel）。 */
    private static final String MODEL_ID = "model-fileref-test";

    /** 独立工作区（每次运行唯一，@AfterAll 清理）：@ 引用的图片放在工作区根。 */
    private static final Path WS = TestCleanup.register(
            Path.of("target/test-ws-fileref-" + System.nanoTime()).toAbsolutePath().normalize());

    /** 工作区根图片的原始字节（内容不重要：image-vision 未超限直接读字节转 base64，不解码）。 */
    private static final byte[] IMAGE_BYTES = "fake-png-bytes".getBytes(StandardCharsets.UTF_8);

    private static final Path HOME_DIR = TestCleanup.register(
            Path.of("target/test-home-fileref-" + System.nanoTime()).toAbsolutePath().normalize());

    private static final int PORT = freePort();

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

    /** 模型替身：录制每次请求的 Prompt（断言「图片以 Media 形态到了模型」的唯一手段）。 */
    static final List<org.springframework.ai.chat.prompt.Prompt> SEEN_PROMPTS =
            java.util.Collections.synchronizedList(new ArrayList<>());

    /** 录制型 FakeChatModel（FakeChatModel 的 fixed 脚本 + 请求录制）。 */
    static class RecordingChatModel extends FakeChatModel {
        @Override
        public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(
                org.springframework.ai.chat.prompt.Prompt prompt) {
            SEEN_PROMPTS.add(prompt);
            return super.stream(prompt);
        }

        @Override
        public org.springframework.ai.chat.model.ChatResponse call(
                org.springframework.ai.chat.prompt.Prompt prompt) {
            SEEN_PROMPTS.add(prompt);
            return super.call(prompt);
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

        /** 模型工厂覆盖：所有配置一律落到脚本化 FakeChatModel（无需真实 provider）。 */
        @Bean
        @Primary
        dev.everyagent.worker.config.ChatModelFactory fakeModelFactory(
                WorkerProperties props,
                dev.everyagent.worker.plugin.registry.ChatModelEnhancerRegistry enhancerRegistry,
                dev.everyagent.worker.modules.ConfigStore configStore) {
            return new dev.everyagent.worker.config.ChatModelFactory(props, enhancerRegistry, configStore) {
                @Override
                public org.springframework.ai.chat.model.ChatModel build(
                        dev.everyagent.worker.modules.ConfigStore.ResolvedConfig cfg,
                        org.springframework.ai.openai.OpenAiChatOptions options, String agentId) {
                    return new RecordingChatModel();
                }

                // agent 装配走 4 参重载（buildAgentModel 直接调它），必须一并覆盖。
                @Override
                public org.springframework.ai.chat.model.ChatModel build(
                        dev.everyagent.worker.modules.ConfigStore.ResolvedConfig cfg,
                        org.springframework.ai.openai.OpenAiChatOptions options, String agentId,
                        dev.everyagent.plugin.api.model.EventEmitter events) {
                    return new RecordingChatModel();
                }
            };
        }

        @Bean
        @Primary
        org.springframework.ai.chat.model.ChatModel fakeChatModel() {
            return new RecordingChatModel();
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("server.port", () -> String.valueOf(PORT));
        r.add("worker.hubs[0].url", () -> "ws://127.0.0.1:" + PORT + "/fakehub");
        r.add("worker.hubs[0].api-key", () -> KEY);
        r.add("worker.hubs[0].hub-key", () -> "test-hub-key");
        r.add("worker.worker-id", () -> "test-worker-fileref");
        r.add("worker.hub-initial-backoff-ms", () -> "100");
        r.add("worker.hub-max-backoff-ms", () -> "300");
        r.add("worker.limits.max-concurrent-tasks", () -> "2");
        r.add("worker.retry.max-request-retries", () -> "0");
        r.add("worker.home-dir", () -> HOME_DIR.toString());
        r.add("worker.workspace-root", () -> WS.toString());
        // 关键：让内置插件（image-vision / task-input-queue）真正加载——
        // 默认按 JVM 工作目录找 every-agent-plugins，maven 下工作目录是模块目录，找不到。
        r.add("worker.builtin-plugins-dir", FileReferenceAttachmentTest::builtinPluginsDir);
        // 沙箱后端 none：测内不起 codex setup（真机 setup 会等 UAC 弹窗，任务挂住）。
        r.add("worker.sandbox.type", () -> "none");
        // 显式给一个「非池」模型配置：任务一律用它，ChatModelFactory 覆写落到 FakeChatModel，
        // 全程零真实网络（model-pool 插件只在 provider=model-pool 时才接管，这里天然绕开）。
        r.add("worker.models[0].config-id", () -> MODEL_ID);
        r.add("worker.models[0].name", () -> "测试模型");
        r.add("worker.models[0].provider", () -> "openai-compat");
        r.add("worker.models[0].base-url", () -> "http://127.0.0.1:1/unused");
        r.add("worker.models[0].model", () -> "model-test");
        r.add("worker.models[0].api-key", () -> "sk-test");
    }

    @Autowired
    WorkerProperties workerProps;

    @Autowired
    dev.everyagent.worker.task.TaskStore taskStore;

    @Autowired
    HubPool pool;

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
        SEEN_PROMPTS.clear();
        // 工作区根放一张图（内容不重要：image-vision 直接读字节转 base64）
        Files.createDirectories(WS);
        Files.write(WS.resolve("图.png"), IMAGE_BYTES);
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

    /** 首轮：新建任务里 @ 引用工作区图片 → 正文改写为「（附图：图.png）」+ metadata.attachments 注入。 */
    @Test
    void firstRoundImageReferenceAttaches() {
        String taskId = runImageTask(WS);
        awaitDone(taskId);

        JsonNode userMessage = userMessage(taskId, "图里有什么内容");
        assertTrue(userMessage.path("payload").path("content").asString().contains("（附图：图.png）"),
                "正文应改写为附图提示（不是裸路径）: " + userMessage);
        assertImageAttachment(taskId);
    }

    /** 队列轮：任务运行中输入入队，续跑轮同样要处理 @ 引用（附件 + 正文改写）。 */
    @Test
    void queuedRoundImageReferenceAttaches() {
        String taskId = create("SLOW:占位运行中");
        syncUntil(taskId, e -> "delta".equals(e.path("event").asString()), "首片 delta（任务在跑）");

        // 运行中提交带图片 token 的输入 → queue.dispatch 入队 → queue.loop 续跑消费
        String resp = rpc("task.run", Json.write(Json.obj()
                .put("taskId", taskId)
                .put("input", "图.png 图里有什么内容")
                .put("rawContent", rawContentForImage())
                .put("configId", MODEL_ID)
                .put("workspace", WS.toString())));
        assertTrue(resp.contains("rpc.ok"), "运行中提交应入队成功: " + resp);

        awaitDone(taskId);
        JsonNode userMessage = userMessage(taskId, "图里有什么内容");
        assertTrue(userMessage.path("payload").path("content").asString().contains("（附图：图.png）"),
                "队列轮正文应改写为附图提示（不是裸路径）: " + userMessage);
        assertImageAttachment(taskId);
    }

    /**
     * 「插入到当前对话」：输入队列项经「插入即消费」转投插入对话队列 → DialogInsertAdvisor 在
     * 工具循环下行 drain（**不经过 file.reference.process 875 节点**，直接投给模型）。
     * 该路径必须与轮次路径同一条物化实现：正文改写成「（附图：图.png）」、图片以 Media 进模型、
     * 附件并进 metadata.attachments。
     */
    @Test
    void dialogInsertImageReferenceAttaches() {
        // 任务停在 ask_user 上（waiting-user），留出「入队 + 插入」的确定窗口；
        // 回答 ask 后工具结果回灌 → 工具循环续跑 → 插入项在这一轮的下行被 drain。
        String taskId = create("ASK:占位问题");
        JsonNode askCreate = syncUntil(taskId,
                e -> "ask.create".equals(e.path("event").asString()), "ask.create");
        String askId = askCreate.path("payload").path("data").path("askId").asString();
        assertTrue(askId.startsWith("q_"), "askId 应取到: " + askCreate);

        // ① 运行中提交带图片 token 的输入 → 输入队列（前端队列面板的 pending 项）
        String queued = rpc("task.run", Json.write(Json.obj()
                .put("taskId", taskId)
                .put("input", "图.png 图里有什么内容")
                .put("rawContent", rawContentForImage())
                .put("configId", MODEL_ID)
                .put("workspace", WS.toString())));
        assertTrue(queued.contains("rpc.ok"), "运行中提交应入队成功: " + queued);

        // ② 队列面板点「插入」→ 该项从输入队列摘出、转投插入对话队列
        String inserted = rpc("task.run", Json.write(Json.obj()
                .put("taskId", taskId)
                .put("input", "图.png 图里有什么内容")
                .set("metadata", Json.obj().put("insert", true).put("index", 0))
                .put("workspace", WS.toString())));
        assertTrue(inserted.contains("rpc.ok"), "插入当前对话应成功: " + inserted);

        // ③ 回答 ask → 工具结果回灌模型 → 工具循环续跑
        fe.send(pub(Channels.workerInput(k, workerProps.getWorkerId()), "ask.reply",
                "{\"askId\":\"" + askId + "\",\"answer\":\"占位问题：是\"}"));
        awaitDone(taskId);

        // 用户消息区：正文改写为附图提示（不是裸路径），rawContent 仍在（前端还原胶囊）
        JsonNode userMessage = userMessage(taskId, "图里有什么内容");
        assertTrue(userMessage.path("payload").path("content").asString().contains("（附图：图.png）"),
                "插入项正文应改写为附图提示（不是裸路径）: " + userMessage);
        assertFalse(userMessage.path("payload").path("content").asString().contains(" 图.png "),
                "正文不该残留裸路径: " + userMessage);
        assertImageAttachment(taskId);

        // 模型侧：插入项那一次请求必须带图片 Media（只改正文不投图 = AI 仍看不到图）
        org.springframework.ai.chat.messages.UserMessage seen = insertedUserMessageAtModel();
        assertNotNull(seen, "插入项必须以多模态消息（带 Media）投喂模型；实收请求: " + seenPromptTail());
        assertTrue(seen.getText().contains("（附图：图.png）"),
                "模型侧正文应为附图提示: " + seen.getText());
        assertTrue(String.valueOf(seen.getMedia().get(0).getData())
                        .contains(java.util.Base64.getEncoder().encodeToString(IMAGE_BYTES)),
                "Media 应携带该图片的 base64: " + seen.getMedia().get(0).getData());
    }

    // ---- 帮助方法 ----

    /** 录制的请求里含图片 Media 的那条 user 消息（无则 null）。 */
    private org.springframework.ai.chat.messages.UserMessage insertedUserMessageAtModel() {
        String base64 = java.util.Base64.getEncoder().encodeToString(IMAGE_BYTES);
        for (org.springframework.ai.chat.prompt.Prompt prompt : List.copyOf(SEEN_PROMPTS)) {
            for (org.springframework.ai.chat.messages.Message m : prompt.getInstructions()) {
                if (m instanceof org.springframework.ai.chat.messages.UserMessage um
                        && !um.getMedia().isEmpty()
                        && String.valueOf(um.getMedia().get(0).getData()).contains(base64)) {
                    return um;
                }
            }
        }
        return null;
    }

    /** 录制请求的尾部消息快照（断言失败时给人看现场）。 */
    private String seenPromptTail() {
        StringBuilder sb = new StringBuilder();
        for (org.springframework.ai.chat.prompt.Prompt prompt : List.copyOf(SEEN_PROMPTS)) {
            List<org.springframework.ai.chat.messages.Message> ms = prompt.getInstructions();
            sb.append("--- 请求(").append(ms.size()).append(" 条消息) 末条: ")
                    .append(ms.isEmpty() ? "(空)" : ms.get(ms.size() - 1).getClass().getSimpleName()
                            + "=" + ms.get(ms.size() - 1).getText())
                    .append('\n');
        }
        return sb.toString();
    }

    private String runImageTask(Path workspace) {
        String resp = rpc("task.run", Json.write(Json.obj()
                .put("input", "图.png 图里有什么内容")
                .put("rawContent", rawContentForImage())
                .put("configId", MODEL_ID)
                .put("workspace", workspace.toString())));
        assertTrue(resp.contains("rpc.ok"), "创建失败: " + resp);
        return Json.parse(resp).path("payload").path("result").path("taskId").asString();
    }

    /** 与前端 composer 同格式的 opaque token（label=文件名，payload={path,fileName,fullPath}）。 */
    private static String rawContentForImage() {
        String token = SlashTokenEncoder.buildToken(WORKSPACE_FILE_KIND, "图.png", "/图.png",
                Json.obj().put("path", "图.png").put("fileName", "图.png").put("fullPath", "/图.png"));
        return token + " 图里有什么内容";
    }

    private JsonNode userMessage(String taskId, String contains) {
        return syncUntil(taskId, e -> "user.message".equals(e.path("event").asString())
                && e.path("payload").path("content").asString().contains(contains), "user.message");
    }

    /** metadata.attachments 必须含该图片（fileName 取自 token payload → 一并证明 payload 解码成功）。 */
    private void assertImageAttachment(String taskId) {
        JsonNode attachments = readMeta(taskId).path("metadata").path("attachments");
        JsonNode image = null;
        for (JsonNode item : attachments) {
            if ("image".equals(item.path("type").asString())) {
                image = item;
                break;
            }
        }
        assertNotNull(image, "metadata.attachments 应含图片附件: " + attachments);
        assertEquals("图.png", image.path("fileName").asString(), "附件文件名取自 token payload: " + image);
        assertTrue(image.path("dataUrl").asString().startsWith("data:image/png;base64,"),
                "附件应为 dataURL 形态: " + image.path("dataUrl").asString());
    }

    private JsonNode readMeta(String taskId) {
        try {
            return Json.parse(Files.readString(taskStore.dirOf(taskId).resolve("meta.json")));
        } catch (java.io.IOException e) {
            throw new AssertionError("meta 读取失败 task=" + taskId, e);
        }
    }

    private void awaitDone(String taskId) {
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(taskId), "任务 done");
    }

    private void hello(WsTestClient c) {
        c.send("{\"type\":\"hello\",\"ver\":" + Frames.PROTOCOL_VERSION + ",\"role\":\"frontend\",\"apiKey\":\""
                + KEY + "\",\"clientId\":\"fe-fileref\"}");
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

    private record PollResp(List<JsonNode> data, JsonNode result, JsonNode err) {
    }

    private PollResp poll(String paramsJson) {
        String reqId = "r-" + REQ.incrementAndGet();
        fe.send(pub(Channels.workerCmd(k, workerProps.getWorkerId()), "rpc",
                "{\"reqId\":\"" + reqId + "\",\"method\":\"task.poll\",\"params\":" + paramsJson + "}"));
        List<JsonNode> data = new ArrayList<>();
        JsonNode result = null;
        JsonNode err = null;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String t = fe.awaitNew(x -> x.contains(reqId)
                            && (x.contains("\"event\":\"rpc.data\"")
                            || x.contains("\"event\":\"rpc.ok\"")
                            || x.contains("\"event\":\"rpc.err\"")),
                    "task.poll " + paramsJson);
            JsonNode payload = Json.parse(t).path("payload");
            if (t.contains("\"event\":\"rpc.data\"")) {
                for (JsonNode e : payload.path("batch")) {
                    data.add(e);
                }
            } else if (t.contains("\"event\":\"rpc.ok\"")) {
                result = payload.path("result");
                break;
            } else {
                err = payload;
                break;
            }
        }
        return new PollResp(data, result, err);
    }

    private List<JsonNode> sync(String taskId, long afterSeq) {
        long deadline = System.currentTimeMillis() + 15_000;
        List<JsonNode> events = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            ObjectNode params = Json.obj()
                    .put("taskId", taskId)
                    .put("mode", "events")
                    .put("afterSeq", afterSeq)
                    .put("limit", 500);
            PollResp p = poll(Json.write(params));
            assertTrue(p.err() == null, "task.poll sync 失败: " + String.valueOf(p.err()));
            if (p.data().isEmpty()) {
                return events;
            }
            events.addAll(p.data());
            afterSeq = p.data().get(p.data().size() - 1).path("seq").asLong();
        }
        throw new AssertionError("task.poll sync 超时 taskId=" + taskId);
    }

    private JsonNode syncUntil(String taskId, Predicate<JsonNode> cond, String what) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            for (JsonNode e : sync(taskId, 0)) {
                if (cond.test(e)) {
                    return e;
                }
            }
            sleep(150);
        }
        throw new AssertionError("超时(task.poll 轮询),等待: " + what);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}