package dev.everyagent.plugin.taskwait;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.agent.AgentActivity;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.task.StoredTaskInfo;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.task.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WaitTaskAdvisor 单测（核心）：等待放行 / 终态失败 / 快路径 / 轮询 / 自等 /
 * 循环等待 / 取消路径 / leak-guard / stream defer 语义 / provider 装配范围。
 *
 * <p>桩一律测试内自建（手写 TaskService / AgentContext 桩 + Mockito mock
 * plugin-api 接口），不依赖 worker 任何类型。
 */
class WaitTaskAdvisorTest {

    private static final String TASK_ID = "t_wait";
    private static final String TARGET_ID = "t_target";
    private static final String MAIN_AGENT = "agent-main";

    private WaitRegistry registry;
    private StubTaskService taskService;
    private RecordingAgent agent;
    private ExecContext exec;

    @BeforeEach
    void setUp() {
        registry = new WaitRegistry();
        taskService = new StubTaskService();
        agent = new RecordingAgent();
        exec = mock(ExecContext.class);
        when(exec.terminal()).thenReturn(false);
        // 测试调小轮询间隔（默认 1000ms），加速轮询类用例
        WaitTaskAdvisor.pollIntervalMs = 10;
    }

    @AfterEach
    void tearDown() {
        WaitTaskAdvisor.pollIntervalMs = 1000;
    }

    private WaitTaskAdvisor advisor() {
        return new WaitTaskAdvisor(registry, taskService, TASK_ID, agent, exec);
    }

    // ── 1. 无绑定直通 ──────────────────────────────────────────

    @Test
    void noBindingPassesThroughWithoutSideEffects() {
        ChatClientResponse resp = call(advisor());

        assertNotNull(resp, "无绑定时原样放行");
        assertEquals(0, agent.events.size(), "零事件");
    }

    // ── 2. 快路径：目标已 done → 两连发（同 id）放行 ────────────

    @Test
    void fastPathTargetAlreadyDoneEmitsWaitingThenDoneAndPasses() {
        taskService.putDisk(TARGET_ID, "done");
        registry.bind(TASK_ID, TARGET_ID, "写周报");

        long t0 = System.nanoTime();
        ChatClientResponse resp = call(advisor());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertNotNull(resp, "目标已 done 应直接放行（不阻塞）");
        assertTrue(elapsedMs < 2000, "快路径不应进入轮询（实际 " + elapsedMs + "ms）");

        assertEquals(2, agent.events.size(), "起始+完成两连发");
        EmitEvent start = agent.events.get(0);
        EmitEvent done = agent.events.get(1);
        assertEquals(start.id(), done.id(), "两条事件同 traceId（同 seq upsert）");
        assertEquals("task.wait", start.kind());
        assertTrue(start.persist(), "trace 落盘");
        assertEquals(EmitEvent.Mode.REPLACE, start.mode());
        assertEquals("waiting", start.status());
        assertEquals("done", done.status());
        assertEquals("waiting", data(start).path("state").asString());
        assertEquals("done", data(done).path("state").asString());
        assertTrue(done.summary().startsWith("等待完成（"), "summary=等待完成（XmYs）: " + done.summary());
        assertTrue(done.summary().endsWith("）"));
        long waitedMs = data(done).path("waitedMs").asLong(-1);
        assertTrue(waitedMs >= 0 && waitedMs < 2000, "快路径等待耗时≈0: " + waitedMs);
        assertEquals(TARGET_ID, data(done).path("targetTaskId").asString());
        assertEquals("写周报", data(done).path("targetTitle").asString());
        assertTrue(data(done).has("endedAt"), "done 帧补 endedAt");
    }

    // ── 3. 轮询若干次后 done → 放行（绑定一次性消费） ────────────

    @Test
    void pollsUntilTargetDoneThenPasses() throws Exception {
        taskService.putRunning(TARGET_ID, "running");
        registry.bind(TASK_ID, TARGET_ID, "写周报");

        // 120ms 后目标任务收口：内存驱逐 + 磁盘 meta 落 done
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(120);
                taskService.finish(TARGET_ID, "done");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        long t0 = System.nanoTime();
        ChatClientResponse resp = call(advisor());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertNotNull(resp, "目标 done 后放行");
        assertTrue(elapsedMs >= 80, "应经历轮询等待（实际 " + elapsedMs + "ms）");

        assertEquals(2, agent.events.size());
        assertEquals(agent.events.get(0).id(), agent.events.get(1).id());
        assertEquals("waiting", agent.events.get(0).status());
        assertEquals("done", agent.events.get(1).status());

        // 绑定已被 take 消费：同轮后续调用零开销直通
        int eventsBefore = agent.events.size();
        call(advisor());
        assertEquals(eventsBefore, agent.events.size(), "二次调用不再等待/发事件");
    }

    // ── 4. 目标终态失败 → 抛异常 + error 事件 ────────────────────

    @Test
    void targetFailedThrowsWithErrorTrace() {
        taskService.putDisk(TARGET_ID, "failed");
        registry.bind(TASK_ID, TARGET_ID, "写周报");

        WaitTaskFailedException e = assertThrows(WaitTaskFailedException.class,
                () -> call(advisor()));
        assertTrue(e.getMessage().contains("写周报"), "异常信息含目标任务名: " + e.getMessage());
        assertTrue(e.getMessage().contains("已失败"), "异常信息含终态: " + e.getMessage());
        assertTrue(e.getMessage().contains("本任务不再继续"));

        assertEquals(2, agent.events.size(), "起始+失败两连发（首查即终态，不进轮询）");
        EmitEvent start = agent.events.get(0);
        EmitEvent error = agent.events.get(1);
        assertEquals(start.id(), error.id());
        assertEquals("waiting", start.status());
        assertEquals("error", error.status());
        assertEquals("error", data(error).path("state").asString());
        assertEquals("failed", data(error).path("targetStatus").asString());
    }

    @Test
    void targetCancelledThrowsWithErrorTrace() {
        taskService.putDisk(TARGET_ID, "cancelled");
        registry.bind(TASK_ID, TARGET_ID, "写周报");

        WaitTaskFailedException e = assertThrows(WaitTaskFailedException.class,
                () -> call(advisor()));
        assertTrue(e.getMessage().contains("已取消"), "异常信息含终态: " + e.getMessage());

        EmitEvent error = agent.events.get(1);
        assertEquals("cancelled", data(error).path("targetStatus").asString());
    }

    @Test
    void targetMissingThrowsWithErrorTrace() {
        // 内存无 + 磁盘无 = 目标任务已删除
        registry.bind(TASK_ID, "t-gone", "被删的任务");

        WaitTaskFailedException e = assertThrows(WaitTaskFailedException.class,
                () -> call(advisor()));
        assertTrue(e.getMessage().contains("已删除"), "异常信息含终态: " + e.getMessage());

        assertEquals(2, agent.events.size());
        EmitEvent error = agent.events.get(1);
        assertEquals("missing", data(error).path("targetStatus").asString());
        assertEquals("t-gone", data(error).path("targetTaskId").asString());
    }

    // ── 5. 自等 → 立即抛 ───────────────────────────────────────

    @Test
    void selfWaitFailsImmediately() {
        registry.bind(TASK_ID, TASK_ID, "本任务自己");

        WaitTaskFailedException e = assertThrows(WaitTaskFailedException.class,
                () -> call(advisor()));
        assertTrue(e.getMessage().contains("不能等待任务自身"));

        assertEquals(1, agent.events.size(), "只发一条 error trace（无起始帧）");
        EmitEvent error = agent.events.get(0);
        assertEquals("task.wait", error.kind());
        assertEquals("error", error.status());
        assertEquals("error", data(error).path("state").asString());
    }

    // ── 6. 循环等待 → 抛（不发起始帧） ──────────────────────────

    @Test
    void cycleWaitFailsImmediately() {
        registry.bind(TASK_ID, "t-B", "任务B");
        registry.bind("t-B", TASK_ID, "任务A"); // B 等待本任务 → 互相等待

        WaitTaskFailedException e = assertThrows(WaitTaskFailedException.class,
                () -> call(advisor()));
        assertTrue(e.getMessage().contains("循环等待"), "信息: " + e.getMessage());
        assertEquals(0, agent.events.size(), "循环等待在起始帧之前判定，零事件");
    }

    @Test
    void nonCyclicChainDoesNotFalsePositive() {
        // 本任务 → 目标 → 第三任务（链不回指）：正常等待；目标已 done 快路径放行
        registry.bind(TASK_ID, TARGET_ID, "写周报");
        registry.bind(TARGET_ID, "t-C", "任务C");
        taskService.putDisk(TARGET_ID, "done");

        assertNotNull(call(advisor()), "多跳链不回指本任务 → 不误报循环，正常放行");
        assertEquals(2, agent.events.size());
    }

    // ── 7. 取消路径（interrupt）→ cancelled trace + RuntimeException ──

    @Test
    void interruptionCancelsWait() throws Exception {
        taskService.putRunning(TARGET_ID, "running"); // 恒 running
        registry.bind(TASK_ID, TARGET_ID, "写周报");

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread vt = Thread.ofVirtual().start(() -> {
            try {
                call(advisor());
            } catch (Throwable t) {
                thrown.set(t);
            }
        });

        // 等起始帧出现后中断（确保已进入等待）
        awaitStatus("waiting");
        vt.interrupt();
        vt.join(5000);
        assertFalse(vt.isAlive(), "中断后等待线程应退出");

        Throwable t = thrown.get();
        assertNotNull(t, "中断后应抛 RuntimeException");
        assertInstanceOf(RuntimeException.class, t);
        assertInstanceOf(InterruptedException.class, t.getCause(),
                "cause 为 InterruptedException（仿 RateLimitAdvisor 的取消收口）");

        assertEquals(2, agent.events.size());
        EmitEvent cancelled = agent.events.get(1);
        assertEquals(agent.events.get(0).id(), cancelled.id(), "同 traceId upsert");
        assertEquals("cancelled", cancelled.status());
        assertEquals("cancelled", data(cancelled).path("state").asString());
    }

    // ── 8. leak-guard：terminal 后跳过发射 ──────────────────────

    @Test
    void terminalContextSkipsEmission() {
        when(exec.terminal()).thenReturn(true);
        taskService.putDisk(TARGET_ID, "done");
        registry.bind(TASK_ID, TARGET_ID, "写周报");

        assertNotNull(call(advisor()), "terminal 不影响放行/收口逻辑");
        assertEquals(0, agent.events.size(), "主体已收口不再发射事件");
    }

    // ── 9. stream 路径：defer 语义 + 快路径 ─────────────────────

    @Test
    void streamDefersBlockingUntilSubscribe() {
        taskService.putRunning(TARGET_ID, "running"); // 恒 running：若组装期阻塞会卡死
        registry.bind(TASK_ID, TARGET_ID, "写周报");

        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        Flux<ChatClientResponse> flux = advisor().adviseStream(request(), chain);

        assertEquals(0, agent.events.size(), "阻塞必须发生在订阅期（Flux.defer 内）");
        verify(chain, never()).nextStream(any());
    }

    @Test
    void streamFastPathDonePassesThrough() {
        taskService.putDisk(TARGET_ID, "done");
        registry.bind(TASK_ID, TARGET_ID, "写周报");

        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        ChatClientResponse chunk = mock(ChatClientResponse.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(chunk));

        List<ChatClientResponse> received = new ArrayList<>();
        advisor().adviseStream(request(), chain).doOnNext(received::add).blockLast();

        assertEquals(List.of(chunk), received, "等待完成后下行链放行");
        assertEquals(2, agent.events.size(), "订阅期完成 waiting→done 两连发");
    }

    // ── 10. provider：装配范围与坐标 ────────────────────────────

    @Test
    void providerAppliesOnlyToMainAgentOfExistingTask() {
        WaitTaskAdvisorProvider provider = new WaitTaskAdvisorProvider(registry, taskService);
        assertEquals("task-wait", provider.pluginId());
        assertEquals(ToolCallingAdvisor.DEFAULT_ORDER + 40, provider.order());

        TaskRuntime t = mock(TaskRuntime.class);
        when(t.mainAgentId()).thenReturn(MAIN_AGENT);
        taskService.putRuntime(TASK_ID, t);

        assertTrue(provider.appliesTo(advisorCtx(MAIN_AGENT)), "主 agent 装配");
        assertFalse(provider.appliesTo(advisorCtx("agent-sub")), "子 agent 不装配");
        assertFalse(provider.appliesTo(advisorCtx(null)), "agentId null 不装配");

        taskService.inMemory.remove(TASK_ID);
        assertFalse(provider.appliesTo(advisorCtx(MAIN_AGENT)), "任务不存在（已终态）不装配");
    }

    @Test
    void providerCreatesAdvisorBoundToTaskAndAgent() {
        WaitTaskAdvisorProvider provider = new WaitTaskAdvisorProvider(registry, taskService);
        AdvisorContext ctx = advisorCtx(MAIN_AGENT);

        WaitTaskAdvisor a = (WaitTaskAdvisor) provider.create(ctx);
        assertNotNull(a);
        assertEquals("Wait Task Advisor", a.getName());
        assertEquals(ToolCallingAdvisor.DEFAULT_ORDER + 40, a.getOrder());
    }

    // ── 11. 耗时格式化 ──────────────────────────────────────────

    @Test
    void formatsDurationAsXmYs() {
        assertEquals("0s", WaitTaskAdvisor.formatDuration(0));
        assertEquals("0s", WaitTaskAdvisor.formatDuration(999));
        assertEquals("1s", WaitTaskAdvisor.formatDuration(1000));
        assertEquals("45s", WaitTaskAdvisor.formatDuration(45_000));
        assertEquals("1m0s", WaitTaskAdvisor.formatDuration(60_000));
        assertEquals("1m23s", WaitTaskAdvisor.formatDuration(83_000));
        assertEquals("90m12s", WaitTaskAdvisor.formatDuration(5_412_000));
    }

    // ── 辅助 ────────────────────────────────────────────────────

    /** 经 adviseCall 公共路径驱动（真实链：waitIfNeeded → chain.nextCall）。 */
    private ChatClientResponse call(WaitTaskAdvisor a) {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientResponse resp = mock(ChatClientResponse.class);
        when(chain.nextCall(any())).thenReturn(resp);
        return a.adviseCall(request(), chain);
    }

    private static ChatClientRequest request() {
        return ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage("继续"))))
                .build();
    }

    private AdvisorContext advisorCtx(String agentId) {
        AdvisorContext ctx = mock(AdvisorContext.class);
        when(ctx.subjectId()).thenReturn(TASK_ID);
        when(ctx.agentId()).thenReturn(agentId);
        when(ctx.agentEntity()).thenReturn(agent);
        when(ctx.terminal()).thenReturn(false);
        return ctx;
    }

    private static ObjectNode data(EmitEvent e) {
        return (ObjectNode) e.data();
    }

    /** 轮询等待指定 status 的 trace 帧出现（中断用例的同步点）。 */
    private void awaitStatus(String status) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (agent.events.stream().anyMatch(e -> status.equals(e.status()))) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("等待 status=" + status + " 的 trace 帧超时未出现");
    }

    // ── 桩 ──────────────────────────────────────────────────────

    /** 可编程 TaskService 桩：内存运行态 / 磁盘终态 / 缺失三态自由编排。 */
    private static final class StubTaskService implements TaskService {
        final Map<String, TaskRuntime> inMemory = new ConcurrentHashMap<>();
        final Map<String, StoredTaskInfo> onDisk = new ConcurrentHashMap<>();

        void putRunning(String taskId, String status) {
            TaskRuntime t = mock(TaskRuntime.class);
            when(t.status()).thenReturn(status);
            inMemory.put(taskId, t);
        }

        void putRuntime(String taskId, TaskRuntime t) {
            inMemory.put(taskId, t);
        }

        void putDisk(String taskId, String status) {
            StoredTaskInfo d = mock(StoredTaskInfo.class);
            when(d.summary()).thenReturn(Json.obj().put("status", status));
            onDisk.put(taskId, d);
        }

        /** 模拟任务收口：内存驱逐 + 磁盘 meta 落终态。 */
        void finish(String taskId, String status) {
            inMemory.remove(taskId);
            putDisk(taskId, status);
        }

        @Override
        public TaskRuntime get(String taskId) { return inMemory.get(taskId); }

        @Override
        public StoredTaskInfo diskEntry(String taskId) { return onDisk.get(taskId); }

        @Override
        public void publishUpdated(String taskId) { }
    }

    /** AgentContext 最小桩：emitter 捕获全部 EmitEvent（线程安全）。 */
    private static final class RecordingAgent implements AgentContext {
        final List<EmitEvent> events = new CopyOnWriteArrayList<>();

        @Override public String agentId() { return MAIN_AGENT; }
        @Override public String title() { return "main"; }
        @Override public long createdAt() { return 0L; }
        @Override public EventEmitter emitter() {
            return event -> {
                events.add(event);
                return event.id();
            };
        }
        @Override public String status() { return "running"; }
        @Override public boolean finished() { return false; }
        @Override public void finished(boolean finished) { }
        @Override public String lastText() { return null; }
        @Override public List<Message> conversation() {
            throw new UnsupportedOperationException();
        }
        @Override public ChatModel chatModel() {
            throw new UnsupportedOperationException();
        }
        @Override public String currentModel() { return null; }
        @Override public dev.everyagent.plugin.api.event.Usage lastRound() {
            throw new UnsupportedOperationException();
        }
        @Override public String lastModel() { return null; }
        @Override public dev.everyagent.plugin.api.event.Usage usage() {
            throw new UnsupportedOperationException();
        }
        @Override public AgentActivity activity() {
            throw new UnsupportedOperationException();
        }
        @Override public void updateActivity(String reasoning, String content, String error) { }
        @Override public void resetForRerun() { }
        @Override public boolean claimTerminal(String status) { return true; }
    }
}
