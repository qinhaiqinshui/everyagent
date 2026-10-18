package dev.everyagent.plugin.filechange;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.spi.ToolOutcomeLog;
import dev.everyagent.plugin.api.task.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本轮文件改动收集 advisor（独立普通 advisor，<b>不继承</b> {@link StreamAdvisor}）。
 *
 * <p>位置：order = {@link Ordered#HIGHEST_PRECEDENCE} + 301，位于工具调用 advisor 内层。
 * 通过 {@link #adviseStream} 的 {@code doOnNext} 直接看到模型流。
 *
 * <p>职责（两阶段确认,§7.15.2——失败的写不记账）：
 * <ul>
 *   <li><b>暂存（模型流侧）</b>：每轮检查 AI 回复的工具调用，命中 update_file / create_file 只
 *       <b>暂存</b>到 per-run 待确认表（callId → 记录）——此刻工具尚未执行,立即记账会把
 *       「文件已存在」「oldcontent 不唯一」「授权拒绝」等失败调用落成假变更。来源按
 *       agentId==mainAgentId 标注 MAIN/SUB_AGENT。</li>
 *   <li><b>确认（下一轮请求侧）</b>：每轮 adviseStream 入口遍历请求消息里的
 *       {@link ToolResponseMessage}，按 callId 配对待确认记录；成败经
 *       {@link ToolOutcomeLog#toolThrew}(结构化账本的非消费查询,与 tool.result 的
 *       data.status 同源,<b>不解析结果文本</b>——文案匹配会被「读到该文案源码」自指误判)
 *       判定:抛过异常即丢弃,正常返回才提交到 provider 维护的<b>任务级共享 collector</b>
 *       （主 agent 与全部子 agent 经 execution().subjectId() 定位到同一实例,子 agent 的
 *       文件改动不丢失）。工具轮之后框架必递归出携带结果的下一轮请求,故每个待确认记录
 *       必获裁决;流被取消/截断等未裁决场景由收口守卫兜底——未确认记录一律丢弃
 *       （宁漏记勿假记）。账本未装配（null）时按既有约定视为成功照记
 *       （同 WorkerToolEventAdvisor「账本空一律 success」）。</li>
 *   <li><b>收口保存</b>：主 agent 的最后一轮（无工具调用 = run 完成）收口共享 collector，
 *       暂存到 provider 的共享 Map，等 RoundClosedListener 回调时写 file-changes/&lt;roundId&gt;.json。</li>
 * </ul>
 *
 * <p>per-run 物化（每 run 新建实例，实例只持 turn 级状态与共享 collector 引用），多任务并发安全；
 * 待确认表只在实例自己的流回调链上读写（框架递归同步推进,无并发写者）。
 */
public class FileChangeAdvisor implements StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(FileChangeAdvisor.class);

    private static final String UPDATE_FILE = "update_file";
    private static final String CREATE_FILE = "create_file";

    private final AgentContext a;
    private final TaskService taskService;
    private final FileChangeAdvisorProvider provider;
    /** per-run 工具结果账本(结构化成败判据;null = 未装配,按既有约定视为成功)。 */
    private final ToolOutcomeLog outcomes;

    /** 本 turn 的任务级共享 collector（null = 无任务上下文，不收集）。 */
    private FileChangesCollector collector;
    /** 本 turn 的任务 ID（execution().subjectId()；null = 无任务上下文）。 */
    private String taskId;
    /** 本 agent 是否主 agent（agentId == taskRuntime.mainAgentId()；收口判定与来源标注共用）。 */
    private boolean mainAgent;
    private boolean turnHasToolCalls = false;

    /** 上一轮工具调用的待确认记录（callId → 记录;模型流侧暂存,下一轮请求侧裁决后移除）。 */
    private final Map<String, PendingChange> pendingByCallId = new LinkedHashMap<>();

    /** 一次待确认的文件写调用（工具名 + 入参原串 = 账本查询键,与 ToolCall.arguments() 同源）。 */
    private record PendingChange(String toolName, String arguments,
            FileChangesCollector.Source source, String path,
            String before, String after, String changeType) {
    }

    public FileChangeAdvisor(AgentContext a, TaskService taskService, FileChangeAdvisorProvider provider,
            ToolOutcomeLog outcomes) {
        this.a = a;
        this.taskService = taskService;
        this.provider = provider;
        this.outcomes = outcomes;
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
        // 先裁决上一轮暂存的工具调用（工具已执行、结果已进请求历史）,再看本轮模型流
        confirmPendingChanges(chatClientRequest);
        return streamAdvisorChain.nextStream(chatClientRequest)
                .doOnNext(this::stage)
                .doOnComplete(this::finalizeIfLastTurn);
    }

    /** 模型流侧:识别文件写工具调用,暂存待确认记录(不写 collector——工具此刻尚未执行)。 */
    private void stage(ChatClientResponse chunk) {
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
            return; // 无任务上下文：变更无归属,不暂存不收集
        }
        FileChangesCollector.Source source = mainAgent ? FileChangesCollector.Source.MAIN
                : FileChangesCollector.Source.SUB_AGENT;
        for (AssistantMessage.ToolCall tc : calls) {
            stageIfFileChange(source, tc);
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
     * 下一轮请求侧:裁决待确认记录。工具轮之后框架必递归出携带 {@link ToolResponseMessage}
     * 的下一轮请求,每个待确认记录必经此裁决——账本记「抛过异常」即丢弃(失败写不记账),
     * 否则提交共享 collector。本 run 历史/别家 callId 不在待确认表,remove 得 null 天然跳过。
     */
    private void confirmPendingChanges(ChatClientRequest request) {
        if (pendingByCallId.isEmpty() || request == null || request.prompt() == null) {
            return;
        }
        for (Message m : request.prompt().getInstructions()) {
            if (!(m instanceof ToolResponseMessage trm)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                PendingChange p = pendingByCallId.remove(r.id());
                if (p == null) {
                    continue;
                }
                boolean failed = outcomes != null && outcomes.toolThrew(p.toolName(), p.arguments());
                if (failed) {
                    log.debug("[file-change] 丢弃失败工具调用的变更记录 tool={} path={} task={}",
                            p.toolName(), p.path(), taskId);
                    continue;
                }
                collector.onFileSaved(a.agentId(), p.source(), p.path(), p.before(), p.after(), p.changeType());
            }
        }
    }

    /**
     * 主 agent 的最后一轮收口：丢弃仍未确认的记录（流被取消/工具未执行,宁漏记勿假记）,
     * 暂存共享 collector 到 provider（等 RoundClosedListener 回调写文件）并退役活跃实例——
     * 下一 run 重新建 collector,切断跨轮串扰;暂存的是实例引用,写盘时读最新状态,主 agent
     * 流收口后才落定的迟到子 agent 记录不丢。
     */
    private void finalizeIfLastTurn() {
        if (turnHasToolCalls) {
            return; // 工具轮:将继续递归,收口延后(待确认记录留给下一轮请求入口裁决)
        }
        if (!pendingByCallId.isEmpty()) {
            log.debug("[file-change] 收口丢弃未确认工具调用记录 {} 条(流截断/工具未执行) task={} agent={}",
                    pendingByCallId.size(), taskId, a.agentId());
            pendingByCallId.clear();
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

    private void stageIfFileChange(FileChangesCollector.Source source, AssistantMessage.ToolCall tc) {
        String name = tc.name();
        if (!UPDATE_FILE.equals(name) && !CREATE_FILE.equals(name)) {
            return;
        }
        String callId = tc.id();
        if (callId == null || callId.isBlank()) {
            return; // 无 callId 无法与结果配对,裁决不了——不暂存(宁漏记勿假记)
        }
        JsonNode args = parseArgs(tc.arguments());
        String path = args.path("path").asString(null);
        if (path == null || path.isEmpty()) {
            return;
        }
        if (UPDATE_FILE.equals(name)) {
            String oldcontent = args.path("oldcontent").asString(null);
            String content = args.path("content").asString(null);
            pendingByCallId.put(callId, new PendingChange(name, tc.arguments(), source, path,
                    oldcontent == null ? "" : oldcontent,
                    content == null ? "" : content,
                    "modified"));
        } else {
            String content = args.path("content").asString(null);
            pendingByCallId.put(callId, new PendingChange(name, tc.arguments(), source, path,
                    "", content == null ? "" : content, "created"));
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
