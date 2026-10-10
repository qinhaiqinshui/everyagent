package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskKernel;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.plugin.api.task.TaskRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TaskLifecycleExecutorTest {

    private TaskLifecycleExecutor executor;
    private TaskLifecycleContext ctx;

    @BeforeEach
    void setUp() {
        executor = new TaskLifecycleExecutor();
        ctx = new TestContext();
    }

    // ===== 1. 下行顺序 = order 升序 =====
    @Test
    void downstreamOrder_isAscendingByOrder() {
        List<String> callOrder = new ArrayList<>();
        List<TaskLifecycleNode> nodes = List.of(
            new TrackingNode("B", 200, callOrder),
            new TrackingNode("A", 100, callOrder),
            new TrackingNode("C", 300, callOrder)
        );
        TaskKernel kernel = c -> new TaskOutcome(TaskOutcome.TaskEndStatus.DONE, null, 0, 0);
        executor.run(nodes, kernel, ctx);
        assertEquals(List.of("A", "B", "C"), callOrder);
    }

    // ===== 2. 否决短路（下行不调 next→内层不执行） =====
    @Test
    void veto_shortCircuitsInnerNodes() {
        AtomicInteger innerExecuted = new AtomicInteger();
        List<TaskLifecycleNode> nodes = List.of(
            new TaskLifecycleNode() {
                @Override public String id() { return "veto"; }
                @Override public float order() { return 100; }
                @Override public TaskOutcome invoke(TaskLifecycleContext c, TaskChain n) {
                    return TaskOutcome.failed("vetoed");
                }
            },
            new TrackingNode("inner", 200, new ArrayList<>())
        );
        TaskKernel kernel = c -> {
            innerExecuted.incrementAndGet();
            return TaskOutcome.done(0, 0);
        };
        TaskOutcome result = (TaskOutcome) executor.run(nodes, kernel, ctx);
        assertEquals(0, innerExecuted.get(), "内核不应执行");
        assertEquals(TaskOutcome.TaskEndStatus.FAILED, result.status());
    }

    // ===== 3. 逆序收口（上行段按 order 降序执行） =====
    @Test
    void upstreamOrder_isDescendingByOrder() {
        List<String> upOrder = new ArrayList<>();
        List<TaskLifecycleNode> nodes = List.of(
            new UpstreamNode() {
                @Override public String id() { return "low"; }
                @Override public float order() { return 300; }
                @Override protected Object up(TaskLifecycleContext c, Object r) {
                    upOrder.add("low");
                    return r;
                }
            },
            new UpstreamNode() {
                @Override public String id() { return "high"; }
                @Override public float order() { return 500; }
                @Override protected Object up(TaskLifecycleContext c, Object r) {
                    upOrder.add("high");
                    return r;
                }
            }
        );
        TaskKernel kernel = c -> TaskOutcome.done(0, 0);
        executor.run(nodes, kernel, ctx);
        assertEquals(List.of("high", "low"), upOrder, "上行段应按 order 降序执行");
    }

    // ===== 4. try-finally 必达且仅一次 =====
    @Test
    void upstreamMustExecute_exactlyOnce_evenWhenKernelReturnsFailed() {
        AtomicInteger upCount = new AtomicInteger();
        List<TaskLifecycleNode> nodes = List.of(
            new UpstreamNode() {
                @Override public String id() { return "guard"; }
                @Override public float order() { return 950; }
                @Override protected Object up(TaskLifecycleContext c, Object r) {
                    upCount.incrementAndGet();
                    return r;
                }
            }
        );
        // 内核返回 FAILED 结局（非抛异常），上行段仍应执行
        TaskKernel kernel = c -> TaskOutcome.failed(new RuntimeException("kernel error"));
        executor.run(nodes, kernel, ctx);
        assertEquals(1, upCount.get(), "上行段应执行且仅执行一次");
    }

    // ===== 5. 取消→CANCELLED 非 FAILED =====
    @Test
    void interruptCancellation_returnsCancelled() throws Exception {
        // TaskKernel.run 不声明 throws，所以通过节点下行段抛 InterruptedException
        List<TaskLifecycleNode> nodes = List.of(
            new TaskLifecycleNode() {
                @Override public String id() { return "interrupt"; }
                @Override public float order() { return 100; }
                @Override public TaskOutcome invoke(TaskLifecycleContext c, TaskChain n) throws Exception {
                    throw new InterruptedException("cancelled");
                }
            }
        );
        TaskKernel kernel = c -> TaskOutcome.done(0, 0);
        TaskOutcome result = (TaskOutcome) executor.run(nodes, kernel, ctx);
        assertEquals(TaskOutcome.TaskEndStatus.CANCELLED, result.status());
    }

    // ===== 6. 临界段单次持锁 =====
    @Test
    void criticalSection_holdsLockOnce() {
        AtomicInteger lockEnterCount = new AtomicInteger();
        List<TaskLifecycleNode> nodes = List.of(
            new UpstreamNode() {
                @Override public String id() { return "a"; }
                @Override public float order() { return 850; }
                @Override protected Object up(TaskLifecycleContext c, Object r) { return r; }
            },
            new UpstreamNode() {
                @Override public String id() { return "b"; }
                @Override public float order() { return 420; }
                @Override protected Object up(TaskLifecycleContext c, Object r) { return r; }
            }
        );
        // 用自定义 context，taskLock 返回一个可计数的锁对象
        TaskLifecycleContext lockCtx = new TestContext() {
            @Override
            public Object taskLock() {
                lockEnterCount.incrementAndGet();
                return this;
            }
        };
        TaskKernel kernel = c -> TaskOutcome.done(0, 0);
        executor.run(nodes, kernel, lockCtx);
        // taskLock() 应只被调用一次（临界段共享一次 synchronized 进入）
        assertEquals(1, lockEnterCount.get(), "临界段应只持锁一次");
    }

    // ===== 7. 临界段外节点不持锁 =====
    @Test
    void nonCriticalSectionNode_doesNotShareLock() {
        List<TaskLifecycleNode> nodes = List.of(
            new UpstreamNode() {
                @Override public String id() { return "spawned.await"; }
                @Override public float order() { return 950; }
                @Override protected Object up(TaskLifecycleContext c, Object r) { return r; }
            },
            new UpstreamNode() {
                @Override public String id() { return "workspace.activity"; }
                @Override public float order() { return 350; }
                @Override protected Object up(TaskLifecycleContext c, Object r) { return r; }
            }
        );
        TaskLifecycleContext lockCtx = new TestContext() {
            @Override
            public Object taskLock() { return this; }
        };
        TaskKernel kernel = c -> TaskOutcome.done(0, 0);
        executor.run(nodes, kernel, lockCtx);
        // 950 和 350 都在临界段外，不经过临界段 synchronized
        // 这个测试主要验证不抛异常
    }

    // ===== 8. SectionNode 下行在段边界外执行（无锁） =====
    @Test
    void sectionNode_downRunsOutsideCriticalSection() {
        AtomicInteger lockEnterCount = new AtomicInteger();
        List<String> downOrder = new ArrayList<>();
        List<TaskLifecycleNode> nodes = List.of(
            new SectionNode() {
                @Override public String id() { return "queue"; }
                @Override public float order() { return 700; }
                @Override protected void down(TaskLifecycleContext c) { downOrder.add("queue.down"); }
                @Override protected Object up(TaskLifecycleContext c, Object r) { return r; }
            },
            new SectionNode() {
                @Override public String id() { return "status"; }
                @Override public float order() { return 840; }
                @Override protected void down(TaskLifecycleContext c) { downOrder.add("status.down"); }
                @Override protected Object up(TaskLifecycleContext c, Object r) { return r; }
            }
        );
        TaskLifecycleContext lockCtx = new TestContext() {
            @Override
            public Object taskLock() {
                lockEnterCount.incrementAndGet();
                return this;
            }
        };
        TaskKernel kernel = c -> TaskOutcome.done(0, 0);
        executor.run(nodes, kernel, lockCtx);
        assertEquals(List.of("queue.down", "status.down"), downOrder,
                "SectionNode 下行段应在段边界外按 order 升序执行");
        assertEquals(1, lockEnterCount.get(), "临界段应只持锁一次（上行段共享）");
    }

    // ===== 9. SectionNode 下行段否决（抛异常→内层不执行） =====
    @Test
    void sectionNode_downThrows_vetoesInner() {
        AtomicInteger innerExecuted = new AtomicInteger();
        List<TaskLifecycleNode> nodes = List.of(
            new SectionNode() {
                @Override public String id() { return "veto"; }
                @Override public float order() { return 500; }
                @Override protected void down(TaskLifecycleContext c) throws Exception {
                    throw new IllegalStateException("veto");
                }
                @Override protected Object up(TaskLifecycleContext c, Object r) { return r; }
            }
        );
        TaskKernel kernel = c -> {
            innerExecuted.incrementAndGet();
            return TaskOutcome.done(0, 0);
        };
        TaskOutcome result = (TaskOutcome) executor.run(nodes, kernel, ctx);
        assertEquals(0, innerExecuted.get(), "下行段否决后内核不应执行");
        assertEquals(TaskOutcome.TaskEndStatus.FAILED, result.status());
    }

    // ===== 10. 轮次循环在临界段内侧：收口节点整轮任务仅一次、逐轮节点每轮执行 =====
    // 回归：queue.loop 曾置于 order=395（临界段外侧），每轮 next.proceed 的上行段把
    // registry.remove/persistence.untrack/concurrency.release 等一次性收口节点逐轮执行——
    // 任务中途被移出注册表（续跑轮次实时推送断流、事件不落盘）。修复后 queue.loop=870
    // 位于临界段 [420,850] 内侧，循环只重入「每轮语义」节点（consume.input 等）。
    @Test
    void roundLoopInsideCriticalSection_finalizesOncePerRun() {
        List<String> events = new ArrayList<>();
        AtomicInteger kernelRounds = new AtomicInteger();
        java.util.Deque<String> queue = new java.util.concurrent.ConcurrentLinkedDeque<>();
        queue.add("queued-1");

        List<TaskLifecycleNode> nodes = List.of(
            // 一次性收口簇（临界段 [420,850]）
            upNode("registry.remove", 420, events),
            upNode("persistence.untrack", 500, events),
            upNode("disk.index", 550, events),
            upNode("status.persist", 650, events),
            upNode("log.flush", 750, events),
            upNode("concurrency.release", 800, events),
            new SectionNode() {
                @Override public String id() { return "status"; }
                @Override public float order() { return 840; }
                @Override protected void down(TaskLifecycleContext c) { events.add("status.down"); }
                @Override protected Object up(TaskLifecycleContext c, Object r) { events.add("status.up"); return r; }
            },
            // 轮次循环节点（真实 order=870，临界段内侧；重复 next.proceed 续跑队列项）
            new TaskLifecycleNode() {
                @Override public String id() { return "queue.loop"; }
                @Override public float order() { return 870; }
                @Override public Object invoke(TaskLifecycleContext c, TaskChain n) throws Exception {
                    Object result = n.proceed(c);
                    while (result instanceof TaskOutcome to && to.status() == TaskOutcome.TaskEndStatus.DONE) {
                        String polled = queue.poll();
                        if (polled == null) break;
                        c.input(polled);
                        result = n.proceed(c);
                    }
                    return result;
                }
            },
            // 每轮输入消费节点（真实 order=880）
            new TaskLifecycleNode() {
                @Override public String id() { return "consume.input"; }
                @Override public float order() { return 880; }
                @Override public Object invoke(TaskLifecycleContext c, TaskChain n) throws Exception {
                    events.add("consume:" + c.input());
                    return n.proceed(c);
                }
            }
        );
        TaskKernel kernel = c -> {
            kernelRounds.incrementAndGet();
            return TaskOutcome.done(0, 0);
        };
        executor.run(nodes, kernel, ctx);

        assertEquals(2, kernelRounds.get(), "内核应跑两轮（首轮 + 队列 1 项）");
        assertEquals(2, (int) events.stream().filter(e -> e.startsWith("consume:")).count(),
                "consume.input 每轮执行一次");
        assertEquals(1, (int) events.stream().filter(e -> e.equals("registry.remove")).count(),
                "registry.remove 整轮任务仅执行一次");
        assertEquals(1, (int) events.stream().filter(e -> e.equals("persistence.untrack")).count(),
                "persistence.untrack 整轮任务仅执行一次");
        assertEquals(1, (int) events.stream().filter(e -> e.equals("status.up")).count(),
                "终态收口 status.up 整轮任务仅执行一次");
        assertEquals(1, (int) events.stream().filter(e -> e.equals("concurrency.release")).count(),
                "concurrency.release 整轮任务仅执行一次（曾逐轮扣减导致计数下溢）");
        assertEquals(List.of("consume:", "consume:queued-1"),
                events.stream().filter(e -> e.startsWith("consume:")).toList(),
                "队列项应作为第二轮输入被消费");
    }

    /** 记录上行段调用的辅助节点。 */
    private static UpstreamNode upNode(String name, float order, List<String> events) {
        return new UpstreamNode() {
            @Override public String id() { return name; }
            @Override public float order() { return order; }
            @Override protected Object up(TaskLifecycleContext c, Object r) {
                events.add(name);
                return r;
            }
        };
    }

    // ===== 辅助类 =====

    /** 追踪下行调用顺序的节点。 */
    private static class TrackingNode implements TaskLifecycleNode {
        private final String name;
        private final float order;
        private final List<String> callOrder;

        TrackingNode(String name, float order, List<String> callOrder) {
            this.name = name;
            this.order = order;
            this.callOrder = callOrder;
        }

        @Override public String id() { return name; }
        @Override public float order() { return order; }

        @Override
        public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
            callOrder.add(name);
            return next.proceed(ctx);
        }
    }

    /** 简单的 TaskLifecycleContext 测试实现（input 可写读回传）。 */
    private static class TestContext implements TaskLifecycleContext {
        private String input = "";
        @Override public String taskId() { return "test"; }
        @Override public String title() { return "test"; }
        @Override public String workspaceRoot() { return "/tmp"; }
        @Override public String workspaceId() { return "ws"; }
        @Override public String mainAgentId() { return "main"; }
        @Override public String status() { return "running"; }
        @Override public TaskRuntime taskInfo() { return null; }
        @Override public Object taskLock() { return this; }
        @Override public long startedAt() { return 0; }
        @Override public void startedAt(long ms) { }
        @Override public void onUsageBroadcast(Runnable hook) { }
        @Override public String input() { return input; }
        @Override public void input(String input) { this.input = input; }
        @Override public String rawContent() { return ""; }
        @Override public void rawContent(String rawContent) { }
        @Override public Map<String, Object> runParams() { return Map.of(); }
        @Override public void runParams(Map<String, Object> runParams) { }
        @Override public Map<String, Object> metadata() { return Map.of(); }
        @Override public void metadata(Map<String, Object> metadata) { }
        @Override public Object rpcContext() { return null; }
    }
}
