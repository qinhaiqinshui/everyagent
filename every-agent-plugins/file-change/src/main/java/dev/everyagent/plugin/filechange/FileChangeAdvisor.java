package dev.everyagent.plugin.filechange;

import dev.everyagent.contract.json.Json;
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
import tools.jackson.databind.JsonNode;

/**
 * 本轮文件改动收集 advisor（独立普通 advisor，<b>不继承</b> {@link StreamAdvisor}）。
 *
 * <p>位置：order = {@link Ordered#HIGHEST_PRECEDENCE} + 301，位于工具调用 advisor 内层。
 * 通过 {@link #adviseStream} 的 {@code doOnNext} 直接看到模型流。
 *
 * <p>职责：
 * <ul>
 *   <li><b>检测记录</b>：每轮检查 AI 回复的工具调用，命中 update_file / create_file 则记录到
 *       provider 维护的<b>任务级共享 collector</b>（主 agent 与全部子 agent 经 execution().subjectId()
 *       定位到同一实例，子 agent 的文件改动不丢失）；来源按 agentId==mainAgentId 标注 MAIN/SUB_AGENT。</li>
 *   <li><b>收口保存</b>：主 agent 的最后一轮（无工具调用 = run 完成）收口共享 collector，
 *       暂存到 provider 的共享 Map，等 RoundClosedListener 回调时写 file-changes/&lt;roundId&gt;.json。</li>
 * </ul>
 *
 * <p>per-run 物化（每 run 新建实例，实例只持 turn 级状态与共享 collector 引用），多任务并发安全。
 */
public class FileChangeAdvisor implements StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(FileChangeAdvisor.class);

    private static final String UPDATE_FILE = "update_file";
    private static final String CREATE_FILE = "create_file";

    private final AgentContext a;
    private final TaskService taskService;
    private final FileChangeAdvisorProvider provider;

    /** 本 turn 的任务级共享 collector（null = 无任务上下文，不收集）。 */
    private FileChangesCollector collector;
    /** 本 turn 的任务 ID（execution().subjectId()；null = 无任务上下文）。 */
    private String taskId;
    /** 本 agent 是否主 agent（agentId == taskRuntime.mainAgentId()；收口判定与来源标注共用）。 */
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
        // 任务级共享 collector：主 agent 与子 agent 记录到同一实例（子 agent 的改动不落孤立实例）
        collector = taskId == null ? null : provider.activeCollector(taskId);
        turnHasToolCalls = false;
        return streamAdvisorChain.nextStream(chatClientRequest)
                .doOnNext(this::record)
                .doOnComplete(this::finalizeIfLastTurn);
    }

    private void record(ChatClientResponse chunk) {
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
        if (collector == null) {
            return; // 无任务上下文：变更无归属，不收集
        }
        FileChangesCollector.Source source = mainAgent ? FileChangesCollector.Source.MAIN
                : FileChangesCollector.Source.SUB_AGENT;
        for (AssistantMessage.ToolCall tc : calls) {
            recordIfFileChange(collector, source, tc);
        }
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
     * 主 agent 的最后一轮收口：暂存共享 collector 到 provider（等 RoundClosedListener 回调写文件）
     * 并退役活跃实例——下一 run 重新建 collector，切断跨轮串扰；暂存的是实例引用，写盘时读
     * 最新状态，主 agent 流收口后才落定的迟到子 agent 记录不丢。
     */
    private void finalizeIfLastTurn() {
        if (turnHasToolCalls) {
            return; // 工具轮：将继续递归，收口延后
        }
        // 只主 agent 收口（子 agent 不收口；其记录已在共享 collector 里，由主 agent 统一收口）
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

    private void recordIfFileChange(FileChangesCollector c, FileChangesCollector.Source source,
            AssistantMessage.ToolCall tc) {
        String name = tc.name();
        if (!UPDATE_FILE.equals(name) && !CREATE_FILE.equals(name)) {
            return;
        }
        JsonNode args = parseArgs(tc.arguments());
        String path = args.path("path").asString(null);
        if (path == null || path.isEmpty()) {
            return;
        }
        if (UPDATE_FILE.equals(name)) {
            String oldcontent = args.path("oldcontent").asString(null);
            String content = args.path("content").asString(null);
            c.onFileSaved(a.agentId(), source, path,
                    oldcontent == null ? "" : oldcontent,
                    content == null ? "" : content,
                    "modified");
        } else {
            String content = args.path("content").asString(null);
            c.onFileSaved(a.agentId(), source, path, "", content == null ? "" : content, "created");
        }
    }

    private static JsonNode parseArgs(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Json.obj();
        }
        try {
            return Json.parse(arguments);
        } catch (RuntimeException e) {
            return Json.obj();
        }
    }
}
