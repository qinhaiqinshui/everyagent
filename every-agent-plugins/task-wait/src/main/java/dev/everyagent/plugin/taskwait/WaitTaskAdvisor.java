package dev.everyagent.plugin.taskwait;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.plugin.api.task.StoredTaskInfo;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.task.TaskService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;
import tools.jackson.databind.node.ObjectNode;

/**
 * 等待任务 Advisor（等待主体，整体仿 {@code modelratelimit.RateLimitAdvisor} 结构）。
 *
 * <p>职责唯一（红线）：等待 + 发 trace。首个模型调用前消费
 * {@link WaitRegistry} 的内存绑定，<b>阻塞轮询目标任务直至 done 才放行</b>；
 * 其他终态（failed / cancelled / missing）、自等、循环等待 → 发 error trace 后抛
 * {@link WaitTaskFailedException}（任务失败收口，非重试）。
 *
 * <p>事件协议：kind={@code task.wait}、persist=true（落盘）、{@code Mode.REPLACE}、
 * agentId=null（task 层兜底 mainAgentId）、同一 traceId（=Snowflake ID=seq）upsert——
 * waiting 起始帧 → done / error / cancelled 终帧，前端按同 seq 原地覆盖、
 * waiting 态每秒读秒。
 *
 * <p>状态查询口径：内存 {@code taskService.get(id).status()} → 磁盘
 * {@code taskService.diskEntry(id).summary().path("status")} → 均无 = missing
 * （目标任务已删除）。
 *
 * <p>阻塞语义：call 路径直接阻塞；stream 路径用 {@code Flux.defer} 包裹——
 * 阻塞发生在订阅期，落在任务虚拟线程上（虚拟线程 park，零线程开销）。
 * 每次发射前判 {@code exec.terminal()}（leak-guard：主体已收口不再发射）。
 */
public class WaitTaskAdvisor implements CallAdvisor, StreamAdvisor {

    /**
     * 轮询间隔（ms）。包内可覆写的静态字段：默认 1000（规格 §2），
     * 测试调小加速轮询类用例。
     */
    static volatile long pollIntervalMs = 1000L;

    /** 循环等待检测的逐跳上限（防御：超长链不无限走；超过即视为无环、正常等待）。 */
    private static final int MAX_CYCLE_HOPS = 16;

    private final WaitRegistry registry;
    private final TaskService taskService;
    private final String taskId;
    private final AgentContext agent;
    private final ExecContext exec;

    public WaitTaskAdvisor(WaitRegistry registry, TaskService taskService, String taskId,
            AgentContext agent, ExecContext exec) {
        this.registry = registry;
        this.taskService = taskService;
        this.taskId = taskId;
        this.agent = agent;
        this.exec = exec;
    }

    @Override
    public String getName() {
        return "Wait Task Advisor";
    }

    @Override
    public int getOrder() {
        // DialogInsertAdvisor(DEF+30) 内侧；EmptyResponseRetry(DEF+100) /
        // TransientErrorRetry(DEF+200) 外侧——异常不经过任何重试 advisor
        return ToolCallingAdvisor.DEFAULT_ORDER + 40;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        waitIfNeeded();
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // 阻塞必须发生在订阅期（defer 内）= 落在任务虚拟线程上，组装期零阻塞
        return Flux.defer(() -> {
            waitIfNeeded();
            return chain.nextStream(request);
        });
    }

    /**
     * 等待主体（包内可见，测试直接驱动）。
     *
     * <ol>
     *   <li>{@code registry.take(taskId)} 无绑定 → 直通（同轮后续调用 /
     *       无胶囊轮次的绝大多数路径零开销）；</li>
     *   <li>自等 → error trace + 抛 {@link WaitTaskFailedException}；</li>
     *   <li>循环等待 → 沿 {@code registry.activeOf} 逐跳走（只读，上限
     *       {@value #MAX_CYCLE_HOPS} 跳），回指本任务即抛；</li>
     *   <li>发起始 trace（waiting）；</li>
     *   <li>轮询（快路径先查后等）：done → done trace + 放行；
     *       failed / cancelled / missing → error trace + 抛；
     *       其余（running / waiting-user / created）→ sleep 后续查；
     *       InterruptedException → 尽力发 cancelled trace → 恢复中断标记 →
     *       抛 RuntimeException（任务按取消收口）。</li>
     * </ol>
     */
    void waitIfNeeded() {
        WaitRegistry.WaitBinding b = registry.take(taskId);
        if (b == null) {
            return;
        }
        // 自等：目标任务即本任务，永远不会 done → 立即失败收口
        if (taskId.equals(b.targetTaskId())) {
            long traceId = SnowflakeId.next();
            emit(traceId, b, "error", "不能等待任务自身", errorData(b, "self"));
            throw new WaitTaskFailedException("不能等待任务自身");
        }
        // 循环等待：目标任务链上任一回指本任务 → 互相等待，谁都不会 done
        detectCycle(b);

        long traceId = SnowflakeId.next();
        long startedAt = System.currentTimeMillis();
        emitStart(traceId, b, startedAt);

        // 快路径（先查后等，需求 9）：进入轮询前先查一次——用户选中时目标进行中、
        // 提交运行时已 done → 起始+完成两连发（waitedMs≈0）直接放行；首查即
        // failed / cancelled / missing 同样直接走终态分支，不进入轮询。
        while (true) {
            String s = targetStatus(b.targetTaskId());
            if ("done".equals(s)) {
                long endedAt = System.currentTimeMillis();
                long waitedMs = endedAt - startedAt;
                emit(traceId, b, "done", "等待完成（" + formatDuration(waitedMs) + "）",
                        doneData(b, startedAt, endedAt, waitedMs));
                return;
            }
            if ("failed".equals(s) || "cancelled".equals(s) || "missing".equals(s)) {
                emit(traceId, b, "error", "目标任务未成功完成，等待中止", errorData(b, s));
                throw new WaitTaskFailedException("等待的任务「" + b.targetTitle()
                        + "」未成功完成（" + terminalLabel(s) + "），本任务不再继续");
            }
            // 其余（running / waiting-user / created）→ 虚拟线程 park 后续查
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                try {
                    emit(traceId, b, "cancelled", "等待已取消",
                            cancelledData(b, startedAt, s));
                } catch (RuntimeException ignored) {
                    // 尽力发射：发射失败忽略，取消收口优先
                }
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 循环等待检测：从首个目标沿 {@code registry.activeOf} 逐跳走（只读，不消费），
     * 任一跳回指本任务即抛 {@link WaitTaskFailedException}。
     */
    private void detectCycle(WaitRegistry.WaitBinding first) {
        String next = first.targetTaskId();
        for (int hop = 0; hop < MAX_CYCLE_HOPS && next != null; hop++) {
            if (next.equals(taskId)) {
                throw new WaitTaskFailedException("检测到任务互相等待（循环等待），本任务不再继续");
            }
            WaitRegistry.WaitBinding nb = registry.activeOf(next);
            next = nb == null ? null : nb.targetTaskId();
        }
    }

    /**
     * 目标任务状态查询：内存运行时 → 磁盘 meta（终态驱逐后）→ missing（已删除）。
     */
    private String targetStatus(String targetTaskId) {
        if (taskService != null) {
            TaskRuntime r = taskService.get(targetTaskId);
            if (r != null) {
                return r.status();
            }
            StoredTaskInfo d = taskService.diskEntry(targetTaskId);
            if (d != null && d.summary() != null) {
                return d.summary().path("status").asString("");
            }
        }
        return "missing";
    }

    // ── trace 发射（统一 leak-guard + agent.emitter） ──────────────────

    /** 发起始帧（waiting）：前端进入读秒态。 */
    private void emitStart(long traceId, WaitRegistry.WaitBinding b, long startedAt) {
        emit(traceId, b, "waiting", "等待目标任务完成…", Json.obj()
                .put("targetTaskId", b.targetTaskId())
                .put("targetTitle", b.targetTitle())
                .put("startedAt", startedAt)
                .put("state", "waiting"));
    }

    /** 完成帧 data：在起始 data 基础上补 endedAt / waitedMs / state=done。 */
    private ObjectNode doneData(WaitRegistry.WaitBinding b, long startedAt, long endedAt,
            long waitedMs) {
        return Json.obj()
                .put("targetTaskId", b.targetTaskId())
                .put("targetTitle", b.targetTitle())
                .put("startedAt", startedAt)
                .put("endedAt", endedAt)
                .put("waitedMs", waitedMs)
                .put("state", "done");
    }

    /** 失败帧 data：state=error + 目标终态（self=自等）。 */
    private ObjectNode errorData(WaitRegistry.WaitBinding b, String targetStatus) {
        return Json.obj()
                .put("targetTaskId", b.targetTaskId())
                .put("targetTitle", b.targetTitle())
                .put("state", "error")
                .put("targetStatus", targetStatus);
    }

    /** 取消帧 data：state=cancelled（本任务被中断，非目标终态）。 */
    private ObjectNode cancelledData(WaitRegistry.WaitBinding b, long startedAt,
            String targetStatus) {
        return Json.obj()
                .put("targetTaskId", b.targetTaskId())
                .put("targetTitle", b.targetTitle())
                .put("startedAt", startedAt)
                .put("state", "cancelled")
                .put("targetStatus", targetStatus);
    }

    /**
     * 统一发射口：同 traceId upsert（Mode.REPLACE、persist=true、agentId=null）。
     * leak-guard：{@code exec.terminal()} 为 true 跳过；agent / emitter 缺席时跳过。
     */
    private void emit(long traceId, WaitRegistry.WaitBinding b, String status, String summary,
            ObjectNode data) {
        if (exec != null && exec.terminal()) {
            return;
        }
        if (agent == null || agent.emitter() == null) {
            return;
        }
        agent.emitter().emit(EmitEvent.of(traceId, "task.wait", null,
                "等待「" + b.targetTitle() + "」", summary, null, status, data,
                EmitEvent.Mode.REPLACE));
    }

    // ── 工具 ──────────────────────────────────────────────────────

    /** 终态中文标签（异常信息展示用）。 */
    private static String terminalLabel(String status) {
        return switch (status) {
            case "failed" -> "已失败";
            case "cancelled" -> "已取消";
            case "missing" -> "已删除";
            default -> status;
        };
    }

    /** 耗时格式化：XmYs（不足 1 分钟 → Ns，如 {@code 1m23s} / {@code 45s}）。 */
    static String formatDuration(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes == 0) {
            return seconds + "s";
        }
        return minutes + "m" + seconds + "s";
    }
}
