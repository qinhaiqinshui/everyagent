package dev.everyagent.plugin.filechange;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.agent.AgentActivity;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.event.Usage;
import dev.everyagent.plugin.api.event.EventLogReader;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.task.RoundClosedInfo;
import dev.everyagent.plugin.api.task.StoredTaskInfo;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.task.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileChangeAdvisor} 主/子 agent 共享 collector 回归测试。
 *
 * <p>修复的 bug:子 agent 工具调用的文件变更曾落入 per-run 私有 collector,而收口守卫
 * 「只主 agent 收口」导致这些记录被静默丢弃。修复后:
 * <ul>
 *   <li>主/子 agent 的 advisor 经 execution().subjectId() 记录到 provider 的同一任务级
 *       collector,来源按 agentId==mainAgentId 标注 MAIN/SUB_AGENT;</li>
 *   <li>主 agent 最后一轮(无工具调用)收口暂存共享 collector,轮闭合回调写出的分片
 *       含子 agent 的文件改动;</li>
 *   <li>子 agent 的无工具轮不收口(不产生分片)。</li>
 * </ul>
 *
 * <p>脚手架风格参考 {@code AdaptiveMaxTokensAdvisorTest}:手写桩链(免 Mockito),
 * §14.9 插件测试零 worker 依赖。
 */
class FileChangeAdvisorTest {

    private static final String TASK = "t_fc";
    private static final String MAIN = "a_main";
    private static final String SUB = "a_sub";

    @Test
    void subAgentFileChangesLandInSharedCollectorAndShard() throws IOException {
        TaskService taskService = taskService();
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(taskService);
        FileChangeAdvisor main = new FileChangeAdvisor(agent(MAIN), taskService, provider);
        FileChangeAdvisor sub = new FileChangeAdvisor(agent(SUB), taskService, provider);

        // 主 agent 工具轮:改 /a.md → 记录进共享 collector
        StepVerifier.create(main.adviseStream(request(), chain(toolCallChunk("update_file",
                "{\"path\":\"/a.md\",\"oldcontent\":\"旧\",\"content\":\"新\"}"))))
                .expectNextCount(1).verifyComplete();
        // 子 agent 工具轮:建 /b.md → 记录进同一共享 collector(修复点:此前落入子 agent 私有实例)
        StepVerifier.create(sub.adviseStream(request(), chain(toolCallChunk("create_file",
                "{\"path\":\"/b.md\",\"content\":\"B\"}"))))
                .expectNextCount(1).verifyComplete();

        // 同一实例 + 来源标注:MAIN / SUB_AGENT
        FileChangesCollector collector = provider.activeCollector(TASK);
        JsonNode meta = collector.buildMetadata().path("changes");
        assertEquals(2, meta.size());
        assertEquals("MAIN", meta.get(0).path("source").asString());
        assertEquals(MAIN, meta.get(0).path("agentId").asString());
        assertEquals("SUB_AGENT", meta.get(1).path("source").asString());
        assertEquals(SUB, meta.get(1).path("agentId").asString());

        // 主 agent 最后一轮(无工具调用)收口 → 轮闭合写分片,含子 agent 的 /b.md
        StepVerifier.create(main.adviseStream(request(), chain(textChunk("done"))))
                .expectNextCount(1).verifyComplete();
        Path dir = Files.createTempDirectory("file-change-advisor-test");
        provider.onRoundsClosed(TASK, dir, List.of(new RoundClosedInfo("r_1", 1, 5L, 1)));
        JsonNode changes = Json.parse(
                Files.readString(dir.resolve("file-changes").resolve("r_1.json"))).path("changes");
        assertEquals(2, changes.size(), "分片应含主+子 agent 的文件变更");
        assertTrue(changes.toString().contains("/a.md"), "含主 agent 的 /a.md");
        assertTrue(changes.toString().contains("/b.md"), "含子 agent 的 /b.md(修复点)");
    }

    @Test
    void subAgentFinalTurnDoesNotFinalize() throws IOException {
        TaskService taskService = taskService();
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(taskService);
        FileChangeAdvisor sub = new FileChangeAdvisor(agent(SUB), taskService, provider);

        // 子 agent 无工具轮(其 run 的最后一轮):不收口,轮闭合无暂存 → 不写分片
        StepVerifier.create(sub.adviseStream(request(), chain(textChunk("sub done"))))
                .expectNextCount(1).verifyComplete();
        Path dir = Files.createTempDirectory("file-change-advisor-test");
        provider.onRoundsClosed(TASK, dir, List.of(new RoundClosedInfo("r_1", 1, 5L, 1)));
        assertFalse(Files.exists(dir.resolve("file-changes").resolve("r_1.json")),
                "子 agent 不收口,不产生分片");
    }

    // ---- 测试脚手架(§14.9:自建桩,不依赖 worker) ----

    private static ChatClientRequest request() {
        return new ChatClientRequest(new Prompt(List.of()), Map.of());
    }

    /** 单源桩链:nextStream 返回按序携带给定 chunk 的 Flux。 */
    private static StreamAdvisorChain chain(ChatClientResponse... chunks) {
        Flux<ChatClientResponse> source = Flux.just(chunks);
        return new StreamAdvisorChain() {
            @Override
            public Flux<ChatClientResponse> nextStream(ChatClientRequest chatClientRequest) {
                return source;
            }

            @Override
            public List<StreamAdvisor> getStreamAdvisors() {
                return List.of();
            }

            @Override
            public StreamAdvisorChain copy(StreamAdvisor advisor) {
                return this;
            }
        };
    }

    /** 构造带单个工具调用的 chunk。 */
    private static ChatClientResponse toolCallChunk(String name, String arguments) {
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall("call_1", "function", name, arguments);
        AssistantMessage msg = AssistantMessage.builder().content("").toolCalls(List.of(tc)).build();
        Generation gen = new Generation(msg,
                ChatGenerationMetadata.builder().finishReason("tool_calls").build());
        return new ChatClientResponse(new ChatResponse(List.of(gen)), Map.of());
    }

    /** 构造纯文本 chunk(finish_reason=stop,无工具调用)。 */
    private static ChatClientResponse textChunk(String text) {
        AssistantMessage msg = AssistantMessage.builder().content(text).build();
        Generation gen = new Generation(msg,
                ChatGenerationMetadata.builder().finishReason("stop").build());
        return new ChatClientResponse(new ChatResponse(List.of(gen)), Map.of());
    }

    private static AgentContext agent(String agentId) {
        return new StubAgentContext(agentId, agentId, runtime());
    }

    private static TaskService taskService() {
        TaskRuntime runtime = runtime();
        return new TaskService() {
            @Override
            public TaskRuntime get(String taskId) {
                return TASK.equals(taskId) ? runtime : null;
            }

            @Override
            public StoredTaskInfo diskEntry(String taskId) {
                return null;
            }

            @Override
            public void publishUpdated(String taskId) {
            }
        };
    }

    /** TaskRuntime 最小桩:FileChangeAdvisor 只读 taskId/mainAgentId(经 TaskService.get)。 */
    private static TaskRuntime runtime() {
        return new StubTaskRuntime();
    }

    private static final class StubTaskRuntime implements TaskRuntime {
        @Override public String taskId() { return TASK; }
        @Override public String status() { return "running"; }
        @Override public Path taskDir() { return Path.of("target", "file-change-test", TASK); }
        @Override public String workspaceRoot() { return "/tmp"; }
        @Override public String workspaceId() { return "w_1"; }
        @Override public String mainAgentId() { return MAIN; }
        @Override public ModelConfig snapshot() {
            return new ModelConfig("cfg", "openai", "http://localhost", "test-model", null);
        }
        @Override public EventEmitter events() { return e -> e.id(); }
        @Override public AgentContext main() { return null; }
        @Override public EventLogReader log() { return null; }
        @Override public long startedAt() { return 0; }
        @Override public long endedAt() { return 0; }
        @Override public void touch() { }
        @Override public ObjectNode summaryJson() { return Json.obj(); }
        @Override public void truncateLogAfter(long targetSeq) { }
        @Override public AgentFactory agentFactory() { return null; }
        @Override public Map<String, Object> metadata() { return new HashMap<>(); }
        @Override public boolean terminal() { return false; }
        @Override public dev.everyagent.plugin.api.interaction.InteractionService interaction() { return null; }
        @Override public Map<String, AgentContext> agents() { return new HashMap<>(); }
    }

    /** AgentContext 最小桩:advisor 只读 agentId/execution。 */
    private static final class StubAgentContext implements AgentContext {
        private final String agentId;
        private final String title;
        private final ExecContext task;

        StubAgentContext(String agentId, String title, ExecContext task) {
            this.agentId = agentId;
            this.title = title;
            this.task = task;
        }

        @Override public String agentId() { return agentId; }
        @Override public String title() { return title; }
        @Override public long createdAt() { return 0; }
        @Override public ExecContext execution() { return task; }
        @Override public EventEmitter emitter() { return e -> e.id(); }
        @Override public String status() { return "running"; }
        @Override public boolean finished() { return false; }
        @Override public void finished(boolean finished) { }
        @Override public String lastText() { return ""; }
        @Override public List<Message> conversation() { return new ArrayList<>(); }
        @Override public ChatModel chatModel() { return null; }
        @Override public String currentModel() { return null; }
        @Override public Usage lastRound() { return null; }
        @Override public String lastModel() { return ""; }
        @Override public Usage usage() { return null; }
        @Override public AgentActivity activity() { return null; }
        @Override public void updateActivity(String reasoning, String content, String error) { }
        @Override public void resetForRerun() { }
        @Override public boolean claimTerminal(String status) { return true; }
    }
}
