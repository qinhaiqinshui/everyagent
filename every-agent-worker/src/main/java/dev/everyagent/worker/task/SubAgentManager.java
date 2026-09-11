package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.ConfigStore;
import dev.everyagent.worker.modules.ConfigStore.ResolvedConfig;
import dev.everyagent.worker.tools.AskUserTool;
import dev.everyagent.worker.tools.BashTool;
import dev.everyagent.worker.tools.CommandExecutor;
import dev.everyagent.worker.tools.FileTools;
import dev.everyagent.worker.tools.FsToolSupport;
import dev.everyagent.worker.tools.PermissionGate;
import dev.everyagent.worker.tools.PowerShellTool;
import dev.everyagent.worker.tools.RipgrepBinary;
import dev.everyagent.worker.os.OsSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 子 agent 管理(架构 §5.6):
 * 子 agent 独立会话(不继承父上下文)、工具集不含 agent 工具(结构上禁递归);
 * 级联停止;任务收口前自动等待全部子 agent。
 */
@Component
public class SubAgentManager {

    public static final String SUB_SYSTEM_PROMPT =
            "你是任务中派生的子 agent。专注完成交给你的单一目标,善用工具,给出简明的最终结论。";

    /** wait_agents 未显式传 timeoutMs 时的默认等待上限(毫秒),防长时间挂起主 agent(任务收口等待另用系统限额)。 */
    private static final long DEFAULT_WAIT_TIMEOUT_MS = 30_000;

    /** 取消竞态定稿等待上限(毫秒):future 已取消但子线程尚未写终态时的有界等待。 */
    private static final long SETTLE_NUDGE_MS = 2_000;

    private static final Logger log = LoggerFactory.getLogger(SubAgentManager.class);

    private final ChatModelFactory modelFactory;
    private final AgentRunner runner;
    private final WorkerProperties props;
    private final PendingAsks asks;
    private final FsToolSupport fs;
    private final OsSandbox sandbox;
    private final PermissionGate gate;
    private final RipgrepBinary rgbin;
    private final ConfigStore configStore;
    private final java.util.concurrent.ExecutorService vt = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    public SubAgentManager(ChatModelFactory modelFactory, AgentRunner runner, WorkerProperties props,
            PendingAsks asks, FsToolSupport fs, OsSandbox sandbox, PermissionGate gate, RipgrepBinary rgbin,
            ConfigStore configStore) {
        this.modelFactory = modelFactory;
        this.runner = runner;
        this.props = props;
        this.asks = asks;
        this.fs = fs;
        this.sandbox = sandbox;
        this.gate = gate;
        this.rgbin = rgbin;
        this.configStore = configStore;
    }

    /**
     * run_agent 工具入口:返回给主 agent 的文本。
     * 同 agentId 仅在"运行中"时拒绝;已落定(完成/停止/失败)→ 复用原实体续跑
     * (原会话追加新指令,不重做已完成部分,§5.6/agent-dispatch 技能)。
     */
    public String run(TaskEntry task, String input, String title, String agentId, boolean blocking)
            throws InterruptedException {
        if (task.stopRequested) {
            return "任务已停止,未启动子 agent";
        }
        if (agentId != null && !agentId.isEmpty()) {
            Future<?> live = task.subFutures.get(agentId);
            if (live != null && !live.isDone()) {
                return "子 agent 已在运行: " + agentId + "(请先 wait_agents 等待或 stop_agent 停止)";
            }
        }
        long liveCount = task.subFutures.values().stream().filter(f -> !f.isDone()).count();
        if (liveCount >= props.getLimits().getMaxConcurrentSubs()) {
            return "[无法启动] 运行中的子 agent 数已达上限 " + props.getLimits().getMaxConcurrentSubs()
                    + ",请先用 wait_agents 等待现有子 agent 完成。";
        }
        boolean reuse = agentId != null && !agentId.isEmpty() && task.subs.containsKey(agentId);
        String id = agentId == null || agentId.isEmpty() ? dev.everyagent.worker.proto.ShortIds.subAgentId() : agentId;
        AgentEntity sub;

        // 注册/启动放在 task 监视器内,与 stopAll 互斥:
        // 要么 stopAll 先拿到锁(任务停止,这里看到 stopRequested 后放弃启动),
        // 要么这里先注册完,stopAll 随后一定能 cancel 到该 future 并立即收口。
        synchronized (task) {
            if (task.stopRequested) {
                return "任务已停止,未启动子 agent";
            }
            if (reuse) {
                sub = task.subs.get(id);
                sub.resetForRerun();
                sub.conversation.add(new UserMessage(input)); // 续跑:原会话历史 + 新指令
            } else {
                sub = buildAgent(task, id, title == null || title.isEmpty() ? "子任务" : title, input);
            }

            // FutureTask 先入册再执行:waitFor/stop/run 守卫看到的永远是当前运行,
            // 不存在 submit 与 put 之间被查询/完成的窗口。注册必须先于 running 事件,
            // 这样前端一旦看到「运行中」,stop 就一定能在 subFutures 里找到并取消它。
            java.util.concurrent.FutureTask<Void> ft = new java.util.concurrent.FutureTask<>(() -> {
                runSub(task, id, sub);
                return null;
            });
            task.subs.put(id, sub);
            // 台账同步:创建/复用即登记(冷启动恢复 + list_agents 的数据源)。
            task.agentLedger.put(id, sub.toSummary());
            task.subFutures.put(id, ft);
            task.events.agentStarted(id, sub.title, input);
            task.events.agentStatus(id, "running"); // 子 agent 开始运行(agent 列表状态机)
            vt.execute(ft);
        }

        if (!blocking) {
            return "子 agent 已启动(异步): " + id;
        }
        try {
            java.util.concurrent.Future<?> f = task.subFutures.get(id);
            f.get();
        } catch (java.util.concurrent.ExecutionException e) {
            return "子 agent 执行异常: " + e.getCause();
        } catch (java.util.concurrent.CancellationException e) {
            // 任务取消级联先 cancel 子 future 再 interrupt 主线程(TaskManager.rpcTaskCancel),
            // get() 可能先见 CancellationException——按停止收口,不得向模型泄漏异常
            awaitSettle(sub);
            return "子 agent 已停止: " + id;
        }
        awaitSettle(sub);
        return switch (sub.status) {
            case "stopped" -> "子 agent 已停止: " + id;
            case "error" -> "子 agent 执行异常: " + sub.activity().error();
            default -> sub.lastText == null || sub.lastText.isEmpty() ? "子 agent 无输出" : sub.lastText;
        };
    }

    /** 子 agent 运行体(vt 线程):任何收口路径都写工具契约终态 + error 快照,finally 置 finished。 */
    private void runSub(TaskEntry task, String id, AgentEntity sub) {
        try {
            runner.run(sub);
            // 正常完成:只有未被 stop 侧抢先置为 stopped 时才发 done(终态唯一声明)。
            if (sub.claimTerminal("completed")) {
                task.events.agentDone(id, sub.lastText, sub.usage());
                task.events.agentStatus(id, "done");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitStopped(task, sub); // stop_agent / 级联取消
        } catch (Throwable t) {
            // 根因摘要:BaseAdvisor 包装会把真实错误埋在最里层(见 RootCause)。
            log.warn("子 agent {} 异常: {}", id, RootCause.summary(t));
            log.debug("子 agent {} 异常完整堆栈", id, t);
            if (sub.claimTerminal("error")) {
                String msg = RootCause.summary(t);
                sub.updateActivity(null, null, msg);
                task.events.error(id, msg);
                task.events.agentStatus(id, "failed");
            }
        } finally {
            sub.finished = true;
            // 收口:台账刷新为终态 + 触发持久化(崩溃后冷启动可恢复台账)。
            task.agentLedger.put(id, sub.toSummary());
            task.persist();
        }
    }

    /**
     * 把子 agent 立即置为 stopped 并发终态事件(幂等)。
     * stop_agent / 任务取消级联调用;如果子线程已抢先收口(completed/error),此处不覆盖。
     */
    private void emitStopped(TaskEntry task, AgentEntity sub) {
        if (!sub.claimTerminal("stopped")) {
            return;
        }
        sub.updateActivity(null, null, "已取消");
        task.events.error(sub.agentId, "已取消");
        task.events.agentStatus(sub.agentId, "stopped");
        task.agentLedger.put(sub.agentId, sub.toSummary());
        task.persist();
    }

    /**
     * list_agents 工具入口:返回 {@code {agents:[摘要]}}(对标 old list_agent 契约)。
     * 摘要 = {agentId,title,createdAt,status,latestActivity}:status 终态为
     * completed/stopped/error,running 运行中,waiting-user 挂起等用户回答;
     * latestActivity 为最近一次 AI 返回的快照,不回灌完整历史。
     */
    public String list(TaskEntry task) throws InterruptedException {
        // 取消竞态收口:future 已取消但子线程尚未写终态的,有界等定稿,避免把已停止的报成 running
        for (AgentEntity s : task.subs.values()) {
            if (!s.finished) {
                Future<?> f = task.subFutures.get(s.agentId);
                if (f != null && f.isDone()) {
                    awaitSettle(s);
                }
            }
        }
        ObjectNode r = Json.obj();
        r.set("agents", agentsJson(task));
        return Json.write(r);
    }

    /** 合并台账 + 活实体的 agents 摘要数组:台账优先(含历史终态 + 冷启动恢复项),活实体实时覆盖。 */
    private ArrayNode agentsJson(TaskEntry task) {
        java.util.LinkedHashMap<String, ObjectNode> merged = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ObjectNode> e : task.agentLedger.entrySet()) {
            merged.put(e.getKey(), e.getValue());
        }
        for (AgentEntity s : task.subs.values()) {
            merged.put(s.agentId, summaryJson(task, s));
        }
        // 按 createdAt 稳定排序(ConcurrentHashMap 台账无遍历序保证)。
        java.util.List<ObjectNode> ordered = new java.util.ArrayList<>(merged.values());
        ordered.sort(java.util.Comparator.comparingLong(a -> a.path("createdAt").asLong(0)));
        ArrayNode agents = Json.arr();
        ordered.forEach(agents::add);
        return agents;
    }

    /**
     * wait_agents 工具入口(对标 old wait_agent 契约):
     * 传 agentId → {@code {mode:"single",waitStatus,agent}}(等待单个,
     * 超时 waitStatus="timeout" 且仍返回最新摘要供判断进度);
     * 不传 → {@code {mode:"list",waitStatus,agents}}(共享 deadline 等待全部未落定)。
     */
    public String waitFor(TaskEntry task, String agentId, Long timeoutMs) throws InterruptedException {
        long timeout = timeoutMs != null && timeoutMs > 0 ? timeoutMs : DEFAULT_WAIT_TIMEOUT_MS;
        if (agentId != null && !agentId.isEmpty()) {
            ObjectNode r = Json.obj();
            r.put("mode", "single");
            AgentEntity sub = task.subs.get(agentId);
            Future<?> f = task.subFutures.get(agentId);
            if (sub == null) {
                ObjectNode hist = task.agentLedger.get(agentId);
                if (hist != null) {
                    // 台账历史 agent(已完成/停止/冷启动恢复):直接返回终态摘要,无需等待。
                    r.put("waitStatus", "completed");
                    r.set("agent", hist);
                    return Json.write(r);
                }
                // 台账无该 agent(凭空 id):最小摘要 + 说明,waitStatus=timeout
                log.warn("wait_agents 未找到 agentId,返回最小摘要: {}", agentId);
                ObjectNode a = Json.obj();
                a.put("agentId", agentId);
                a.put("createdAt", System.currentTimeMillis());
                a.put("status", "running");
                ObjectNode la = Json.obj();
                la.put("error", "agentId 不存在(agentId 应来自 list_agents)");
                a.set("latestActivity", la);
                r.put("waitStatus", "timeout");
                r.set("agent", a);
                return Json.write(r);
            }
            if (f == null || isTerminal(sub.status)) {
                // 已落定(完成/停止/失败):直接返回最新摘要
                r.put("waitStatus", "completed");
                r.set("agent", summaryJson(task, sub));
                return Json.write(r);
            }
            boolean settled = true;
            try {
                f.get(timeout, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                settled = false;
            } catch (java.util.concurrent.ExecutionException
                    | java.util.concurrent.CancellationException ignored) {
                // 异常/stop_agent 取消由子线程收口为终态摘要,此处视为已落定
            }
            // 取消竞态:cancel 使 get() 立即返回,子线程可能尚未写终态——有界等定稿再取摘要
            awaitSettle(sub);
            r.put("waitStatus", settled ? "completed" : "timeout");
            r.set("agent", summaryJson(task, sub));
            return Json.write(r);
        }
        boolean allSettled = true;
        long deadline = System.currentTimeMillis() + timeout;
        for (Future<?> f : task.subFutures.values()) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                allSettled = false;
                break;
            }
            if (f.isDone()) {
                continue; // 已落定(含被取消)不占等待预算
            }
            try {
                f.get(remain, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                allSettled = false;
                break;
            } catch (java.util.concurrent.ExecutionException
                    | java.util.concurrent.CancellationException ignored) {
                // 异常已在子线程记录为该子 agent 的 error 事件
            }
        }
        ObjectNode r = Json.obj();
        r.put("mode", "list");
        r.put("waitStatus", allSettled ? "completed" : "timeout");
        r.set("agents", agentsJson(task));
        return Json.write(r);
    }

    public String stop(TaskEntry task, String agentId) {
        AgentEntity sub = task.subs.get(agentId);
        Future<?> f = task.subFutures.get(agentId);
        if (f == null) {
            return "子 agent 不存在: " + agentId;
        }
        if (sub != null && isTerminal(sub.status)) {
            return "子 agent 已结束(" + sub.status + "),无需停止: " + agentId;
        }
        f.cancel(true);
        // 不能只 cancel future:FutureTask 尚未开始执行(cancel 只置 CANCELLED 不跑 runSub)、
        // 或子线程未能立刻响应中断时,前端会一直看到 running。这里同步声明终态并发事件。
        if (sub != null) {
            emitStopped(task, sub);
        }
        return "已请求停止: " + agentId;
    }

    /** 子 agent 摘要(list_agents/wait_agents 共用契约,对标 old AgentSummary):字段 null 省略。 */
    private ObjectNode summaryJson(TaskEntry task, AgentEntity s) {
        ObjectNode n = Json.obj();
        n.put("agentId", s.agentId);
        if (s.title != null) {
            n.put("title", s.title);
        }
        n.put("createdAt", s.createdAt);
        n.put("status", effectiveStatus(task, s));
        AgentActivity act = s.activity();
        ObjectNode la = Json.obj();
        if (act.reasoning() != null) {
            la.put("reasoning", act.reasoning());
        }
        if (act.content() != null) {
            la.put("content", act.content());
        }
        if (act.error() != null) {
            la.put("error", act.error());
        }
        if (act.createdAt() != null) {
            la.put("createdAt", act.createdAt());
        }
        if (act.updatedAt() != null) {
            la.put("updatedAt", act.updatedAt());
        }
        n.set("latestActivity", la);
        return n;
    }

    /** 工具契约有效状态:运行中且挂起 ask → waiting-user(与 UI 事件态同词),否则实体状态。 */
    private String effectiveStatus(TaskEntry task, AgentEntity s) {
        if ("running".equals(s.status) && asks.hasPendingFor(task.taskId, s.agentId)) {
            return "waiting-user";
        }
        return s.status;
    }

    /** 工具契约终态判定。 */
    private static boolean isTerminal(String status) {
        return "completed".equals(status) || "stopped".equals(status) || "error".equals(status);
    }

    /**
     * 有界等待子 agent 收口定稿(finished 置位)。
     * 取消竞态下 future.get() 会先于子线程写终态返回,不等待会把已停止的报成 running。
     */
    private static void awaitSettle(AgentEntity sub) throws InterruptedException {
        if (sub.finished) {
            return;
        }
        long deadline = System.currentTimeMillis() + SETTLE_NUDGE_MS;
        while (!sub.finished && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    /** 任务收口前调用:等待全部子 agent(超时则级联停止,§5.6)。 */
    public void awaitAllBeforeFinish(TaskEntry task) {
        try {
            waitFor(task, null, props.getLimits().getSubWaitTimeoutMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        stopAll(task);
    }

    public void stopAll(TaskEntry task) {
        synchronized (task) {
            task.stopRequested = true; // 统一入口置位:任何全停路径都不允许再启动新子 agent
            for (Future<?> f : task.subFutures.values()) {
                f.cancel(true);
            }
            // 见 stop():cancel 不保证 runSub 会执行收口(未启动/未及时响应中断的 future 永远停在 running),
            // 这里对全部未终态实体同步声明 stopped,确保任务取消/失败/停机路径下前端状态能收口。
            for (AgentEntity sub : task.subs.values()) {
                emitStopped(task, sub);
            }
        }
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        vt.shutdownNow();
    }

    private AgentEntity buildAgent(TaskEntry task, String agentId, String title, String input) {
        // 普通模型用冻结快照;池配置(configId 指向 provider=model-pool)回查 ConfigStore 以取成员列表。
        ResolvedConfig cfg;
        if (ConfigStore.POOL_PROVIDER.equals(task.snapshot.provider())) {
            cfg = configStore.resolve(task.snapshot.configId());
        } else {
            cfg = new ResolvedConfig(task.snapshot, task.apiKey);
        }
        // 子 agent 工具集不含 run_agent 等(结构上禁止递归);tool.result 事件由 AgentRunner 统一发射
        List<ToolCallback> tools = new ArrayList<>();
        for (ToolCallback c : ToolCallbacks.from(new AskUserTool(asks, props, task, agentId))) {
            tools.add(c);
        }
        // 文件工具(file,与主线一致;子 agent 不含 run_agent 等;授权按 taskId 与主 agent 共享)
        for (ToolCallback c : ToolCallbacks.from(new FileTools(fs, task, agentId))) {
            tools.add(c);
        }
        // 真实 OS 进程命令执行器(非工具):与主线一致,授权检查 + OsSandbox 隔离
        // rg 二进制所在目录随 bash/powershell 子进程注入命令 PATH(缺失时传 null 不注入)
        java.nio.file.Path rg = rgbin.path();
        CommandExecutor exec = new CommandExecutor(sandbox, task, gate, agentId,
                rg != null ? rg.getParent() : null);
        // 平台化命令执行工具:与主线一致,按沙箱后端选方言(wsl-bwrap → bash,windows-mic → powershell)
        if (isWindows() && !sandbox.registerBashTool()) {
            tools.add(new PowerShellTool(exec).toolCallback());
        } else {
            for (ToolCallback c : ToolCallbacks.from(new BashTool(exec))) {
                tools.add(c);
            }
            // 任务级「启用 powershell」(与主线一致):bash 之外追加 PowerShellTool,
            // 命令回宿主 Windows 原生沙箱(windows-mic 语义)执行。
            if (task.powershellEnabled) {
                tools.add(new PowerShellTool(exec).toolCallback());
            }
        }
        // 模型装配:普通模型 → OpenAiChatModel;provider=model-pool → ModelPoolChatModel(自动容灾)。
        ChatModelFactory.AgentModel am = modelFactory.buildAgentModel(cfg, agentId, task.events, null);
        AgentEntity agent = new AgentEntity(task, agentId, AgentEntity.Kind.SUB, title,
                am.chatModel(), am.options(), tools);
        agent.conversation.add(new SystemMessage(SUB_SYSTEM_PROMPT));
        agent.conversation.add(new UserMessage(input));
        return agent;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
