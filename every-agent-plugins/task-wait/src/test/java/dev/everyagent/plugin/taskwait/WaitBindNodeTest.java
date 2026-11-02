package dev.everyagent.plugin.taskwait;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * WaitBindNode 单测：rawContent 含胶囊 → registry 绑定（多胶囊取最后）；
 * 空 / 无胶囊 → 零绑定；next.proceed 透传。
 *
 * <p>桩一律实现 plugin-api 接口（仿 {@code inputqueue.DialogInsertTest} 的
 * TaskLifecycleContext 手写桩），不依赖 worker 任何类型。
 */
class WaitBindNodeTest {

    private WaitRegistry registry;
    private WaitBindNode node;

    @BeforeEach
    void setUp() {
        registry = new WaitRegistry();
        node = new WaitBindNode(registry);
    }

    // ── 绑定登记 ────────────────────────────────────────────────

    @Test
    void bindsLastCapsuleWhenMultiplePresent() throws Exception {
        String raw = "先等 " + WaitTaskToken.buildToken("t-A", "任务A")
                + " 再等 " + WaitTaskToken.buildToken("t-B", "任务B");

        node.invoke(ctx("t-1", raw), passThrough());

        WaitRegistry.WaitBinding b = registry.activeOf("t-1");
        assertNotNull(b, "多胶囊时应登记绑定");
        assertEquals("t-B", b.targetTaskId(), "取最后一个命中（最近意图胜）");
        assertEquals("任务B", b.targetTitle());
    }

    @Test
    void bindsSingleCapsule() throws Exception {
        node.invoke(ctx("t-1", "正文 " + WaitTaskToken.buildToken("t-A", "任务A")),
                passThrough());
        WaitRegistry.WaitBinding b = registry.activeOf("t-1");
        assertNotNull(b);
        assertEquals("t-A", b.targetTaskId());
        assertEquals("任务A", b.targetTitle());
    }

    @Test
    void rebindOverwritesPreviousRound() throws Exception {
        node.invoke(ctx("t-1", WaitTaskToken.buildToken("t-A", "任务A")), passThrough());
        node.invoke(ctx("t-1", WaitTaskToken.buildToken("t-B", "任务B")), passThrough());
        assertEquals("t-B", registry.activeOf("t-1").targetTaskId(),
                "后续轮次新胶囊覆盖旧绑定");
    }

    // ── 零绑定路径 ──────────────────────────────────────────────

    @Test
    void noCapsuleNoBinding() throws Exception {
        node.invoke(ctx("t-1", "普通输入，夹一个异 kind 胶囊 "
                + "[[[[agent-token::::other.kind||||label====x]]]]"), passThrough());
        assertNull(registry.activeOf("t-1"), "无等待胶囊时零绑定");
    }

    @Test
    void emptyOrNullRawContentNoBinding() throws Exception {
        node.invoke(ctx("t-1", ""), passThrough());
        node.invoke(ctx("t-2", null), passThrough());
        assertNull(registry.activeOf("t-1"));
        assertNull(registry.activeOf("t-2"));
    }

    // ── 链透传 ──────────────────────────────────────────────────

    @Test
    void proceedsToNextAndReturnsItsResult() throws Exception {
        AtomicInteger nextCalls = new AtomicInteger();
        TaskChain next = c -> {
            nextCalls.incrementAndGet();
            return "next-result";
        };
        Object result = node.invoke(ctx("t-1", WaitTaskToken.buildToken("t-A", "任务A")), next);

        assertEquals("next-result", result, "原样返回 next 的结果");
        assertEquals(1, nextCalls.get(), "next 恰好调用一次");
    }

    @Test
    void nodeCoordinates() {
        assertEquals("wait.bind", node.id());
        assertEquals(879f, node.order(), "order=879（严禁落 [420,850] 临界段）");
    }

    // ── 桩 ──────────────────────────────────────────────────────

    private static TaskChain passThrough() {
        return c -> null;
    }

    /** 手写 TaskLifecycleContext 桩（仿 DialogInsertTest）：只填本节点消费的槽位。 */
    private static TaskLifecycleContext ctx(String taskId, String rawContent) {
        return new TaskLifecycleContext() {
            @Override public String taskId() { return taskId; }
            @Override public String title() { return "任务"; }
            @Override public String workspaceRoot() { return ""; }
            @Override public String workspaceId() { return "ws"; }
            @Override public String mainAgentId() { return "a-main"; }
            @Override public String status() { return "running"; }
            @Override public TaskRuntime taskInfo() { return null; }
            @Override public Object taskLock() { return new Object(); }
            @Override public long startedAt() { return 0; }
            @Override public void startedAt(long ms) { }
            @Override public void onUsageBroadcast(Runnable hook) { }
            @Override public String input() { return "正文"; }
            @Override public String rawContent() { return rawContent; }
            @Override public Map<String, Object> runParams() { return Map.of(); }
            @Override public Object rpcContext() { return null; }
            @Override public void input(String i) { }
            @Override public void rawContent(String r) { }
            @Override public void runParams(Map<String, Object> r) { }
            @Override public void metadata(Map<String, Object> m) { }
        };
    }
}
