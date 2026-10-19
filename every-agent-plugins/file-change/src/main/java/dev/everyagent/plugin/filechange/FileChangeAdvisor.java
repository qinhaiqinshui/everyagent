package dev.everyagent.plugin.filechange;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.task.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * 本轮文件改动<b>收口</b> advisor(独立普通 advisor,位于工具调用 advisor 内层的模型流侧)。
 *
 * <p>位置:order = {@link Ordered#HIGHEST_PRECEDENCE} + 301,位于工具调用 advisor 内层。
 * 通过 {@link #adviseStream} 的 {@code doOnNext} 直接看到模型流。
 *
 * <p>职责只剩<b>收口侧</b>(写侧记账已迁 per-call 工具执行拦截链上行节点
 * {@link FileChangeRecordInterceptor},架构 §7.15.2——update_file/create_file 调用完成即按
 * callId+成败一阶段记账,旧「模型流暂存 pendingByCallId → 下一轮请求侧配对 ToolResponseMessage
 * 确认」两阶段已删):
 * <ul>
 *   <li><b>工具轮识别</b>:模型流出现 toolCalls 即记 {@code turnHasToolCalls}——工具轮框架
 *       必继续递归,收口延后到之后的无工具轮(run 完成)。</li>
 *   <li><b>收口保存</b>:主 agent 的最后一轮(无工具调用 = run 完成)收口 provider 维护的
 *       <b>任务级共享 collector</b>——暂存到 provider 的共享 Map,等 RoundClosedListener
 *       回调时写 file-changes/&lt;roundId&gt;.json 并退役活跃实例(下一 run 重新建 collector,
 *       切断跨轮串扰;暂存的是实例引用,写盘时读最新状态,主 agent 流收口后才落定的迟到
 *       子 agent 记录不丢)。子 agent 不收口(其记录已在共享 collector 里,由主 agent 统一
 *       收口);流被取消/截断的 run 不收口,残留活跃实例由轮闭合回调清理(宁漏记)。</li>
 * </ul>
 *
 * <p>per-run 物化(每 run 新建实例,实例只持 turn 级状态与共享 collector 引用),多任务并发安全。
 */
public class FileChangeAdvisor implements StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(FileChangeAdvisor.class);

    private final AgentContext a;
    private final TaskService taskService;
    private final FileChangeAdvisorProvider provider;

    /** 本 turn 的任务级共享 collector（null = 无任务上下文，不收集）。 */
    private FileChangesCollector collector;
    /** 本 turn 的任务 ID（execution().subjectId()；null = 无任务上下文）。 */
    private String taskId;
    /** 本 agent 是否主 agent（agentId == taskRuntime.mainAgentId()；收口判定用）。 */
    private boolean mainAgent;
    private boolean turnHasToolCalls = false;

    public FileChangeAdvisor(AgentContext a, TaskService taskService, FileChangeAdvisorProvider provider) {
        this.a = a;
        this.taskService = taskService;
        this.provider = provider;
    }

    @Override
    public String getName() {
        return "File Change Advisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 301;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
            StreamAdvisorChain streamAdvisorChain) {
        ExecContext exec = a.execution();
        taskId = exec == null ? null : exec.subjectId();
        mainAgent = resolveMainAgent();
        // 任务级共享 collector:主 agent 与子 agent 收口定位到同一实例
        // (记账在链上行 FileChangeRecordInterceptor,记入的也是这同一实例)
        collector = taskId == null ? null : provider.activeCollector(taskId);
        turnHasToolCalls = false;
        return streamAdvisorChain.nextStream(chatClientRequest)
                .doOnNext(this::trackToolCalls)
                .doOnComplete(this::finalizeIfLastTurn);
    }

    /** 模型流侧:只识别工具轮(收口延后);记账在链上行 FileChangeRecordInterceptor。 */
    private void trackToolCalls(ChatClientResponse chunk) {
        ChatResponse cr = chunk.chatResponse();
        if (cr == null || cr.getResult() == null) {
            return;
        }
        AssistantMessage out = cr.getResult().getOutput();
        if (out == null) {
            return;
        }
        var calls = out.getToolCalls();
        if (calls == null || calls.isEmpty()) {
            return;
        }
        turnHasToolCalls = true;
    }

    /** 本 agent 是否主 agent（agentId == taskRuntime.mainAgentId()；任务查不到视为非主，不收口）。 */
    private boolean resolveMainAgent() {
        if (taskId == null) {
            return false;
        }
        var taskRuntime = taskService.get(taskId);
        return taskRuntime != null && a.agentId().equals(taskRuntime.mainAgentId());
    }

    /**
     * 主 agent 的最后一轮收口:暂存共享 collector 到 provider(等 RoundClosedListener 回调写文件)
     * 并退役活跃实例——下一 run 重新建 collector,切断跨轮串扰;暂存的是实例引用,写盘时读最新
     * 状态,主 agent 流收口后才落定的迟到子 agent 记录不丢。只主 agent 收口(子 agent 不收口,
     * 其记录已在共享 collector 里,由主 agent 统一收口)。
     */
    private void finalizeIfLastTurn() {
        if (turnHasToolCalls) {
            return; // 工具轮:将继续递归,收口延后
        }
        if (taskId == null) {
            log.debug("[file-change] 收口跳过:execution()==null agent={}", a.agentId());
            return;
        }
        if (!mainAgent) {
            log.debug("[file-change] 收口跳过:非主 agent agent={} task={}", a.agentId(), taskId);
            return;
        }
        FileChangesCollector c = collector;
        if (c == null || c.isEmpty()) {
            log.debug("[file-change] 收口跳过:collector 空(本 run 无文件改动) task={} agent={}",
                    taskId, a.agentId());
            return;
        }
        // 暂存共享 collector，等 RoundClosedListener 回调时按 roundId 写文件
        provider.markPending(taskId, c);
        log.debug("[file-change] 收口暂存 collector task={} agent={} 文件数={}",
                taskId, a.agentId(), c.buildSummaries().size());
    }
}
