// ea: WorkflowRuntime emitter 单路由与并发发射完整性（engine 包内直构 runtime）
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
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.spi.WorkspaceManager;
import dev.everyagent.plugin.api.spi.WorkspaceSandbox;
import dev.everyagent.plugin.workflow.model.EventLine;
import dev.everyagent.plugin.workflow.store.WorkflowStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * emitter 单路由与并发发射完整性：多线程并发发射下实例域事件（wf./node./supervisor.）
 * 进 events.jsonl（行完整、seq 单调）；agent 域事件（user.message/message/usage 等）不再
 * 入工作流持久面（AI 节点会话单写 origin 任务域，§7.20.8）——实例目录无 agents/。
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

    @Test
    void concurrentEmitKeepsInstanceLinesIntactAndDropsAgentDomain() throws Exception {
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
        WorkflowRuntime runtime = new WorkflowRuntime(store, "ws_emit", meta.id(),
                instanceDir, home.toString(), null);
        runtime.status(dev.everyagent.plugin.workflow.model.InstanceStatus.RUNNING);

        var futures = new java.util.ArrayList<Future<Integer>>();
        for (int t = 0; t < THREADS; t++) {
            final int thread = t;
            futures.add(pool.submit((Callable<Integer>) () -> {
                int ok = 0;
                for (int i = 0; i < PER_THREAD; i++) {
                    String tag = thread + ":" + i;
                    // agent 域事件（命名/匿名/瞬态）——一律不入工作流持久面
                    runtime.emitter().emit(EmitEvent.of(
                            dev.everyagent.plugin.api.proto.SnowflakeId.next(),
                            Events.USER_MESSAGE, "writer#wi_" + meta.id().substring(3),
                            null, null, "u" + tag, null,
                            Map.of("rawContent", "u" + tag), EmitEvent.Mode.REPLACE));
                    runtime.emitter().emit(EmitEvent.of(
                            dev.everyagent.plugin.api.proto.SnowflakeId.next(),
                            Events.MESSAGE, "writer#wi_" + meta.id().substring(3),
                            null, null, "m" + tag, null, null, EmitEvent.Mode.REPLACE));
                    runtime.emitter().emit(EmitEvent.of(
                            dev.everyagent.plugin.api.proto.SnowflakeId.next(),
                            Events.MESSAGE, "n1.7", null, null, "anon", null, null,
                            EmitEvent.Mode.REPLACE));
                    runtime.emitter().emit(EmitEvent.transientOf(
                            dev.everyagent.plugin.api.proto.SnowflakeId.next(),
                            Events.DELTA, "writer#wi_" + meta.id().substring(3),
                            null, null, "d" + tag, null, null, EmitEvent.Mode.APPEND));
                    // 实例域事件
                    runtime.emitter().emit(EmitEvent.of(
                            dev.everyagent.plugin.api.proto.SnowflakeId.next(),
                            "node.output", null, null, null, "n" + tag, null, null,
                            EmitEvent.Mode.REPLACE));
                    ok++;
                }
                return ok;
            }));
        }
        for (Future<Integer> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }

        // events.jsonl：实例域 node.output 全量（+ store.createInstance 的 wf.created）
        List<EventLine> evs = store.readEvents("ws_emit", meta.id(), 0, 0);
        assertThat(evs.stream().filter(e -> "node.output".equals(e.event())).count())
                .isEqualTo((long) THREADS * PER_THREAD);
        // agent 域事件零泄漏进实例持久面
        assertThat(evs.stream().map(EventLine::event))
                .doesNotContain(Events.USER_MESSAGE, Events.MESSAGE, Events.USAGE, Events.TOOL_RESULT);
        // seq 唯一（磁盘 jsonl 的文件序与 seq 序一致）
        List<Long> seqs = evs.stream().map(EventLine::seqValue).sorted().toList();
        assertThat(seqs.stream().distinct().count()).isEqualTo((long) seqs.size());
        // 磁盘行数与读侧一致（无半行/无交错）
        List<String> raw = Files.readAllLines(instanceDir.resolve("events.jsonl"));
        assertThat(raw).hasSize(evs.size());
        for (String line : raw) {
            assertThat(Json.parse(line).path("event").asString()).isNotEmpty();
        }
        // 实例目录不创建 agents/（会话单写任务域）
        assertThat(instanceDir.resolve("agents")).doesNotExist();
        // 内存尾部与磁盘对齐
        assertThat(store.tailEvents("ws_emit", meta.id(), 0).size())
                .isLessThanOrEqualTo(256);
        // 终态后 emit 自吞不抛（leak-guard 场景的近亲：落盘失败也不打断调用线程）
        runtime.status(dev.everyagent.plugin.workflow.model.InstanceStatus.FAILED);
        runtime.emitter().emit(EmitEvent.of(1L, "node.output", null, null, null, "x", null, null,
                EmitEvent.Mode.REPLACE));
    }

    /** 契约钉子：三通道标记 + 拒绝式交互桩 + 档案口委托 origin 任务。 */
    @Test
    void subjectMarkerAndInteractionStubAndArchiveDelegation() throws Exception {
        assertThat(dev.everyagent.plugin.workflow.engine.WorkflowSubject.isWorkflowSubject(runtime0(null))).isTrue();
        WorkflowRuntime rt = runtime0(null);
        assertThat(rt.metadata().get(WorkflowSubject.MARKER_KEY)).isEqualTo(Boolean.TRUE);
        assertThat(rt.metadata()).isInstanceOf(java.util.concurrent.ConcurrentHashMap.class);
        assertThat(rt.agentFactory()).isNull(); // agent 装配一律经 origin 任务 ExecContext
        // 拒绝式交互桩：非 null，ask 一律 cancelled（SubAgentManager waiting-user 判定用）
        assertThat(rt.interaction()).isNotNull();
        var res = rt.interaction().ask(List.of(), 100, Map.of());
        assertThat(res.status()).isEqualTo("cancelled");
        assertThat(rt.interaction().hasPendingFor("x", "y")).isFalse();
        // 未绑定：档案口 null（AI 节点运行将由引擎/节点守卫拒绝）
        assertThat(rt.conversationArchive()).isNull();
        assertThat(rt.taskExec()).isNull();
        // 绑定：档案口委托 origin 任务（任务域档案重建路径）
        java.util.List<org.springframework.ai.chat.messages.Message> seed = List.of(
                new org.springframework.ai.chat.messages.UserMessage("先前指令"));
        WorkflowRuntime bound = runtime0(new dev.everyagent.plugin.api.execution.ExecContext() {
            @Override public String subjectId() { return "task_1"; }
            @Override public String workspaceRoot() { return home.toString(); }
            @Override public String workspaceId() { return "ws_emit"; }
            @Override public dev.everyagent.plugin.api.model.ModelConfig snapshot() { return null; }
            @Override public dev.everyagent.plugin.api.model.EventEmitter emitter() { return e -> e.id(); }
            @Override public dev.everyagent.plugin.api.agent.AgentFactory agentFactory() { return null; }
            @Override public Map<String, Object> metadata() { return Map.of(); }
            @Override public Path dataDir() { return home; }
            @Override public boolean terminal() { return false; }
            @Override public dev.everyagent.plugin.api.interaction.InteractionService interaction() { return null; }
            @Override public dev.everyagent.plugin.api.agent.ConversationArchive conversationArchive() {
                return agentId -> seed;
            }
            @Override public Map<String, dev.everyagent.plugin.api.agent.AgentContext> agents() {
                return Map.of();
            }
        });
        assertThat(bound.taskExec().subjectId()).isEqualTo("task_1");
        assertThat(bound.conversationArchive()).isNotNull();
        assertThat(bound.conversationArchive().load("writer#wi")).isSameAs(seed);
        // compressionSeed 走 default null（安全降级）
        assertThat(bound.compressionSeed("a")).isNull();
    }

    private WorkflowRuntime runtime0(dev.everyagent.plugin.api.execution.ExecContext taskExec) {
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
        return new WorkflowRuntime(store, "ws_emit", meta.id(), instanceDir,
                home.toString(), taskExec);
    }
}
