// ea: WorkflowRuntime emitter 双路由与并发追加完整性（engine 包内直构 runtime）
package dev.everyagent.plugin.workflow.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.event.StreamEmitter;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.spi.ExecResult;
import dev.everyagent.plugin.api.spi.NativeExec;
import dev.everyagent.plugin.api.spi.WorkspaceManager;
import dev.everyagent.plugin.api.spi.WorkspaceSandbox;
import dev.everyagent.plugin.workflow.model.EventLine;
import dev.everyagent.plugin.workflow.store.WorkflowStore;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * agents/&lt;name&gt;.jsonl 并发追加无交错：多线程经 runtime emitter 同名/异名并发发射，
 * 行完整性（无半行/无交错）、seq 单调、双路由分流正确（实例域进 events.jsonl，
 * agent 域进 per-agent jsonl，瞬态丢弃）。
 */
class WorkflowRuntimeEmitTest {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 40;

    @TempDir
    Path home;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    /** 最小 WorkspaceManager 桩（engine 包内自持，不跨包借用 FakeWorkspaces）。 */
    private static final class Ws implements WorkspaceManager {
        private final Path systemDir;

        Ws(Path systemDir) {
            this.systemDir = systemDir;
        }

        @Override
        public String idOfRoot(String root) {
            return "ws_emit";
        }

        @Override
        public Path systemDir() {
            return systemDir;
        }

        @Override
        public Path defaultRoot() {
            return systemDir;
        }

        @Override
        public WorkspaceSandbox sandboxFor(String raw) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Path> pruneStaleAndListMountRoots() {
            return List.of(systemDir);
        }
    }

    /** 最小 WorkerServices 桩（emitter 双路由不触其余服务；stream 可注入，node.delta 扇出用）。 */
    private static final class Svc implements WorkerServices {
        private final StreamEmitter stream;

        Svc() {
            this(null);
        }

        Svc(StreamEmitter stream) {
            this.stream = stream;
        }

        @Override
        public dev.everyagent.plugin.api.spi.SandboxBackend sandbox() {
            throw new UnsupportedOperationException();
        }

        @Override
        public NativeExec nativeExec() {
            return (argv, cwd, env, timeoutMs) -> new ExecResult("", "", 0, false);
        }

        @Override
        public WorkspaceManager workspaces() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.everyagent.plugin.api.spi.TokenEstimator tokenEstimator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.everyagent.plugin.api.slash.SlashTokenService slashTokens() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.everyagent.plugin.api.interaction.InteractionService interaction() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.everyagent.plugin.api.config.WorkerConfig config() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.everyagent.plugin.api.spi.IdGenerator ids() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.everyagent.plugin.api.event.StreamEmitter stream() {
            if (stream == null) {
                throw new UnsupportedOperationException();
            }
            return stream;
        }

        @Override
        public Path dataDirOf(String subjectId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.everyagent.plugin.api.model.EventEmitter emitterOf(String subjectId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addRoundClosedListener(dev.everyagent.plugin.api.task.RoundClosedListener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addFileWriteListener(dev.everyagent.plugin.api.spi.FileWriteListener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toSandboxPath(Path hostPath) {
            return hostPath.toString();
        }

        @Override
        public dev.everyagent.plugin.api.agent.AgentFactory agentFactoryFor(
                dev.everyagent.plugin.api.execution.ExecContext exec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ModelConfig> modelConfigs() {
            return List.of();
        }

        @Override
        public dev.everyagent.plugin.api.task.TaskService task() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.everyagent.plugin.api.task.TaskStoreService store() {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void concurrentAgentAndInstanceEmitKeepLinesIntact() throws Exception {
        WorkspaceManager ws = new Ws(home);
        WorkflowStore store = new WorkflowStore(ws);
        // 注册 ws_emit 工作区（idOfRoot 恒返 ws_emit）
        store.refreshWorkspaceRegistry();
        var def = new dev.everyagent.plugin.workflow.model.Definition(
                dev.everyagent.plugin.workflow.model.Definition.SCHEMA_VERSION,
                "wf_emit", "emit", null, "ws_emit", 0L, 0L, null, null, null, null,
                List.of(new dev.everyagent.plugin.workflow.model.NodeDef("s", "start", "s", null, null,
                        null, null, null, null, null, null)),
                List.of());
        var saved = store.saveDefinition("ws_emit", def);
        var meta = store.createInstance(saved, Map.of());

        Path instanceDir = home.resolve("workspaces").resolve("ws_emit").resolve("workflows")
                .resolve("instances").resolve(meta.id());
        WorkflowRuntime runtime = new WorkflowRuntime(store, new Svc(), "ws_emit", meta.id(),
                instanceDir, home.toString());
        runtime.status(dev.everyagent.plugin.workflow.model.InstanceStatus.RUNNING);

        var futures = new java.util.ArrayList<Future<Integer>>();
        for (int t = 0; t < THREADS; t++) {
            final int thread = t;
            futures.add(pool.submit((Callable<Integer>) () -> {
                int ok = 0;
                for (int i = 0; i < PER_THREAD; i++) {
                    String tag = thread + ":" + i;
                    // 同名 agent 域事件（user.message/message 交替）——同名文件并发追加
                    runtime.emitter().emit(EmitEvent.of(
                            dev.everyagent.plugin.api.proto.SnowflakeId.next(),
                            Events.USER_MESSAGE, "writer#wi_" + meta.id().substring(3),
                            null, null, "u" + tag, null,
                            Map.of("rawContent", "u" + tag), EmitEvent.Mode.REPLACE));
                    runtime.emitter().emit(EmitEvent.of(
                            dev.everyagent.plugin.api.proto.SnowflakeId.next(),
                            Events.MESSAGE, "writer#wi_" + meta.id().substring(3),
                            null, null, "m" + tag, null, null, EmitEvent.Mode.REPLACE));
                    // 实例域事件
                    runtime.emitter().emit(EmitEvent.of(
                            dev.everyagent.plugin.api.proto.SnowflakeId.next(),
                            "node.output", null, null, null, "n" + tag, null, null,
                            EmitEvent.Mode.REPLACE));
                    // 瞬态与未知 kind 丢弃
                    runtime.emitter().emit(EmitEvent.transientOf(
                            dev.everyagent.plugin.api.proto.SnowflakeId.next(),
                            Events.DELTA, "writer#wi_" + meta.id().substring(3),
                            null, null, "d" + tag, null, null, EmitEvent.Mode.APPEND));
                    // 匿名 agentId（无 #wi 后缀）不落 agents/
                    runtime.emitter().emit(EmitEvent.of(
                            dev.everyagent.plugin.api.proto.SnowflakeId.next(),
                            Events.MESSAGE, "n1.7", null, null, "anon", null, null,
                            EmitEvent.Mode.REPLACE));
                    ok++;
                }
                return ok;
            }));
        }
        for (Future<Integer> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }

        // agents/writer.jsonl：每线程 2 行（user.message + message），全部完整
        var agentLines = store.readAgentEvents("ws_emit", meta.id(), "writer");
        assertThat(agentLines).hasSize(THREADS * PER_THREAD * 2);
        List<Long> seqs = agentLines.stream().map(l -> Long.parseLong(l.seq())).sorted().toList();
        assertThat(seqs.stream().distinct().count()).isEqualTo((long) seqs.size());
        for (var l : agentLines) {
            assertThat(l.agentId()).startsWith("writer#");
            String content = l.payload().path("content").asString();
            assertThat(content).contains(":");
            if ("user.message".equals(l.event())) {
                assertThat(content).startsWith("u");
            } else {
                assertThat(content).startsWith("m");
            }
        }
        // 磁盘行数与读侧一致（无半行/无交错）
        Path writerFile = instanceDir.resolve("agents").resolve("writer.jsonl");
        List<String> raw = Files.readAllLines(writerFile);
        assertThat(raw).hasSize(THREADS * PER_THREAD * 2);
        for (String line : raw) {
            assertThat(Json.parse(line).path("event").asString()).isIn("user.message", "message");
        }
        // 只有一个 agent 文件（匿名不落盘）
        try (var s = Files.list(instanceDir.resolve("agents"))) {
            assertThat(s.map(p -> p.getFileName().toString()).toList())
                    .containsExactly("writer.jsonl");
        }
        // events.jsonl：实例域 node.output 全量（+ store.createInstance 的 wf.created）
        List<EventLine> evs = store.readEvents("ws_emit", meta.id(), 0, 0);
        assertThat(evs.stream().filter(e -> "node.output".equals(e.event())).count())
                .isEqualTo((long) THREADS * PER_THREAD);
        // 内存尾部与磁盘对齐
        assertThat(store.tailEvents("ws_emit", meta.id(), 0).size())
                .isLessThanOrEqualTo(256);
        // 终态后 emit 自吞不抛（leak-guard 场景的近亲：落盘失败也不打断调用线程）
        runtime.status(dev.everyagent.plugin.workflow.model.InstanceStatus.FAILED);
        runtime.emitter().emit(EmitEvent.of(1L, "node.output", null, null, null, "x", null, null,
                EmitEvent.Mode.REPLACE));
    }

    /**
     * node.delta 瞬态契约（架构 §7.13）：node 绑定视图（view(configId, nodeId)）装配的
     * emitter 把 agent 域 delta/thinking 增量以 node.delta 扇出实例流频道（帧外层
     * {seq,ts,event,payload}，内层 {nodeId,agentId,kind,content}，kind 映射 thinking→
     * thinking、delta→text）；瞬态不落盘——events.jsonl 与内存尾部队列均不含；
     * 其余事件经包装视图照旧走双路由；未经包装直发的 delta 维持丢弃。
     */
    @Test
    void nodeDeltaFansOutViaNodeBoundViewWithoutPersisting() throws Exception {
        List<String[]> frames = new java.util.concurrent.CopyOnWriteArrayList<>(); // {event, seq, frame}
        List<String> channels = new java.util.concurrent.CopyOnWriteArrayList<>();
        StreamEmitter recorder = new StreamEmitter() {
            @Override
            public void fanout(java.util.function.Function<String, String> channelNamer, String event,
                    Long seq, JsonNode payload, JsonNode ext) {
                channels.add(channelNamer.apply("OWNER_KEY"));
                frames.add(new String[]{event, String.valueOf(seq), payload.toString()});
            }

            @Override
            public String workerId() {
                return "w1";
            }
        };
        WorkflowStore store = new WorkflowStore(new Ws(home));
        store.refreshWorkspaceRegistry();
        var def = new dev.everyagent.plugin.workflow.model.Definition(
                dev.everyagent.plugin.workflow.model.Definition.SCHEMA_VERSION,
                "wf_delta", "delta", null, "ws_emit", 0L, 0L, null, null, null, null,
                List.of(new dev.everyagent.plugin.workflow.model.NodeDef("n7", "start", "s", null, null,
                        null, null, null, null, null, null)),
                List.of());
        var saved = store.saveDefinition("ws_emit", def);
        var meta = store.createInstance(saved, Map.of());
        Path instanceDir = home.resolve("workspaces").resolve("ws_emit").resolve("workflows")
                .resolve("instances").resolve(meta.id());
        WorkflowRuntime runtime = new WorkflowRuntime(store, new Svc(recorder), "ws_emit", meta.id(),
                instanceDir, home.toString());
        runtime.status(dev.everyagent.plugin.workflow.model.InstanceStatus.RUNNING);
        String agentKey = "writer#wi_" + meta.id().substring(3);

        var view = runtime.view(null, "n7");
        long d1 = dev.everyagent.plugin.api.proto.SnowflakeId.next();
        view.emitter().emit(EmitEvent.transientOf(d1, Events.DELTA, agentKey,
                null, null, "正文片段", null, null, EmitEvent.Mode.APPEND));
        long t1 = dev.everyagent.plugin.api.proto.SnowflakeId.next();
        view.emitter().emit(EmitEvent.transientOf(t1, Events.THINKING, agentKey,
                null, null, "思考片段", null, null, EmitEvent.Mode.APPEND));
        // 其余事件经包装视图照旧双路由：实例域落 events.jsonl、agent 域归户 jsonl
        view.emitter().emit(EmitEvent.of(dev.everyagent.plugin.api.proto.SnowflakeId.next(),
                "node.output", null, null, null, "out", null, null, EmitEvent.Mode.REPLACE));
        view.emitter().emit(EmitEvent.of(dev.everyagent.plugin.api.proto.SnowflakeId.next(),
                Events.MESSAGE, agentKey, null, null, "m", null, null, EmitEvent.Mode.REPLACE));
        // 未经 node 视图包装直发 delta：不扇出、不落盘（现状不变）
        runtime.emitter().emit(EmitEvent.transientOf(
                dev.everyagent.plugin.api.proto.SnowflakeId.next(),
                Events.DELTA, agentKey, null, null, "raw", null, null, EmitEvent.Mode.APPEND));

        // 扇出恰 2 帧 node.delta，频道带 worker 段与实例段（与实例事件同通道）
        assertThat(frames).hasSize(2);
        assertThat(channels).containsOnly("u.OWNER_KEY.worker.w1.wf." + meta.id() + ".stream");
        assertThat(frames.get(0)[0]).isEqualTo("node.delta");
        assertThat(frames.get(0)[1]).isEqualTo(String.valueOf(d1));
        JsonNode f0 = Json.parse(frames.get(0)[2]);
        assertThat(f0.path("event").asString()).isEqualTo("node.delta");
        assertThat(f0.path("seq").asString()).isEqualTo(Long.toString(d1));
        assertThat(f0.path("ts").asLong()).isPositive();
        assertThat(f0.path("payload").path("nodeId").asString()).isEqualTo("n7");
        assertThat(f0.path("payload").path("agentId").asString()).isEqualTo(agentKey);
        assertThat(f0.path("payload").path("kind").asString()).isEqualTo("text");
        assertThat(f0.path("payload").path("content").asString()).isEqualTo("正文片段");
        JsonNode f1 = Json.parse(frames.get(1)[2]);
        assertThat(f1.path("seq").asString()).isEqualTo(Long.toString(t1));
        assertThat(f1.path("payload").path("kind").asString()).isEqualTo("thinking");
        assertThat(f1.path("payload").path("content").asString()).isEqualTo("思考片段");

        // 瞬态不落盘：磁盘与内存尾部均无 node.delta（补拉天然不含即为正确）
        List<EventLine> evs = store.readEvents("ws_emit", meta.id(), 0, 0);
        assertThat(evs).extracting(EventLine::event).containsExactly("wf.created", "node.output");
        assertThat(store.tailEvents("ws_emit", meta.id(), 0))
                .extracting(EventLine::event).containsExactly("wf.created", "node.output");
        for (String raw : Files.readAllLines(instanceDir.resolve("events.jsonl"))) {
            assertThat(raw).doesNotContain("node.delta");
        }
        // agent 域照旧：writer.jsonl 恰 1 行 message（delta 不进 per-agent 会话线）
        var agentLines = store.readAgentEvents("ws_emit", meta.id(), "writer");
        assertThat(agentLines).hasSize(1);
        assertThat(agentLines.get(0).event()).isEqualTo("message");
    }

    /** 步骤 4 契约钉子：三通道标记 + 拒绝式交互桩 + 档案口失败返回空表。 */
    @Test
    void subjectMarkerAndInteractionStubAndArchiveFailurePath() throws Exception {
        assertThat(dev.everyagent.plugin.workflow.engine.WorkflowSubject.isWorkflowSubject(runtime0())).isTrue();
        WorkflowRuntime rt = runtime0();
        assertThat(rt.metadata().get(WorkflowSubject.MARKER_KEY)).isEqualTo(Boolean.TRUE);
        assertThat(rt.metadata()).isInstanceOf(java.util.concurrent.ConcurrentHashMap.class);
        // per-node 视图同标记 + snapshot 不共享
        WorkflowRuntime.NodeExecView v = rt.view("cfg-x");
        assertThat(WorkflowSubject.isWorkflowSubject(v)).isTrue();
        assertThat(v.snapshot().configId()).isEqualTo("cfg-x");
        assertThat(v.subjectId()).isEqualTo(rt.subjectId());
        assertThat(rt.view(null).snapshot().configId()).isNull();
        // 拒绝式交互桩：非 null，ask 一律 cancelled（SubAgentManager waiting-user 判定用）
        assertThat(rt.interaction()).isNotNull();
        var res = rt.interaction().ask(List.of(), 100, Map.of());
        assertThat(res.status()).isEqualTo("cancelled");
        assertThat(rt.interaction().hasPendingFor("x", "y")).isFalse();
        // 档案口：非法 agent 名（读侧抛 BadParams）容错为空表不抛
        assertThat(rt.conversationArchive().load("bad/name#wi_x")).isEmpty();
        assertThat(rt.conversationArchive().load(null)).isEmpty();
        // compressionSeed 走 default null（安全降级）
        assertThat(rt.compressionSeed("a")).isNull();
    }

    private WorkflowRuntime runtime0() {
        WorkflowStore store = new WorkflowStore(new Ws(home));
        store.refreshWorkspaceRegistry();
        var def = new dev.everyagent.plugin.workflow.model.Definition(
                dev.everyagent.plugin.workflow.model.Definition.SCHEMA_VERSION,
                "wf_emit2", "emit2", null, "ws_emit", 0L, 0L, null, null, null, null,
                List.of(new dev.everyagent.plugin.workflow.model.NodeDef("s", "start", "s", null, null,
                        null, null, null, null, null, null)),
                List.of());
        var saved = store.saveDefinition("ws_emit", def);
        var meta = store.createInstance(saved, Map.of());
        Path instanceDir = home.resolve("workspaces").resolve("ws_emit").resolve("workflows")
                .resolve("instances").resolve(meta.id());
        return new WorkflowRuntime(store, new Svc(), "ws_emit", meta.id(), instanceDir,
                home.toString());
    }
}
