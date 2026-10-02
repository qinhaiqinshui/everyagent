package dev.everyagent.plugin.filechange;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.task.RoundClosedInfo;
import dev.everyagent.plugin.api.task.RoundClosedListener;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本轮文件改动收集 advisor（独立普通 advisor，<b>不继承</b> {@link StreamAdvisor}）。
 *
 * <p>位置：order = {@link Ordered#HIGHEST_PRECEDENCE} + 301，位于工具调用 advisor 内层。
 * 通过 {@link #adviseStream} 的 {@code doOnNext} 直接看到模型流。
 *
 * <p>职责：
 * <ul>
 *   <li><b>检测记录</b>：每轮检查 AI 回复的工具调用，命中 update_file / create_file 则记录。</li>
 *   <li><b>收口保存</b>：主 agent 的最后一轮（无工具调用 = run 完成）收口 collector，
 *       暂存到 provider 的共享 Map，等 RoundClosedListener 回调时写 file-changes/&lt;roundId&gt;.json。</li>
 * </ul>
 *
 * <p>per-run 物化（每 run 新建实例，状态随实例隔离），多任务并发安全。
 */
public class FileChangeAdvisor implements StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(FileChangeAdvisor.class);

    private static final String UPDATE_FILE = "update_file";
    private static final String CREATE_FILE = "create_file";

    private final AgentContext a;
    private final TaskService taskService;
    private final FileChangeAdvisorProvider provider;

    private FileChangesCollector collector;
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
        if (collector == null) {
            collector = new FileChangesCollector();
        }
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
        for (AssistantMessage.ToolCall tc : calls) {
            recordIfFileChange(collector, tc);
        }
    }

    /** 主 agent 的最后一轮收口：暂存 collector 到 provider 的共享 Map，等 RoundClosedListener 回调写文件。 */
    private void finalizeIfLastTurn() {
        if (turnHasToolCalls) {
            return; // 工具轮：将继续递归，收口延后
        }
        // 只主 agent 收口（子 agent 不收口，避免覆盖）
        ExecContext exec = a.execution();
        if (exec == null) {
            log.debug("[file-change] 收口跳过:execution()==null agent={}", a.agentId());
            return;
        }
        var taskRuntime = taskService.get(exec.subjectId());
        if (taskRuntime == null) {
            log.debug("[file-change] 收口跳过:taskService.get 无此任务 subject={}", exec.subjectId());
            return;
        }
        if (!a.agentId().equals(taskRuntime.mainAgentId())) {
            log.debug("[file-change] 收口跳过:非主 agent agent={} main={}", a.agentId(),
                    taskRuntime.mainAgentId());
            return; // 子 agent：不收口
        }
        if (collector == null || collector.isEmpty()) {
            log.debug("[file-change] 收口跳过:collector 空(本 run 无文件改动) task={} agent={}",
                    exec.subjectId(), a.agentId());
            return;
        }
        // 暂存 collector，等 RoundClosedListener 回调时按 roundId 写文件
        provider.storePendingCollector(exec.subjectId(), collector);
        log.debug("[file-change] 收口暂存 collector task={} agent={} 文件数={}",
                exec.subjectId(), a.agentId(), collector.buildSummaries().size());
        // 不置 null：如果 listener 回调晚于下一轮 adviseStream，collector 需要保持可用
    }

    private void recordIfFileChange(FileChangesCollector c, AssistantMessage.ToolCall tc) {
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
            c.onFileSaved(a.agentId(), path,
                    oldcontent == null ? "" : oldcontent,
                    content == null ? "" : content,
                    "modified");
        } else {
            String content = args.path("content").asString(null);
            c.onFileSaved(a.agentId(), path, "", content == null ? "" : content, "created");
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
