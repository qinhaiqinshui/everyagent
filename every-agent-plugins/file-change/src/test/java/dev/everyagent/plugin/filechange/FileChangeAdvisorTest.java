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
import dev.everyagent.plugin.api.spi.FileWriteListener;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileChangeAdvisor}(收口侧)+ {@link FileChangeRecorder}(文件写监听一阶段
 * 记账)回归测试,架构 §7.15.2。
 *
 * <p>修复的 bug(三枚,既往两阶段确认时代引入,一阶段化后口径不变):
 * <ul>
 *   <li><b>失败写不记账</b>:「文件已存在」「oldcontent 不唯一」「授权拒绝」等失败调用
 *       (工具抛异常,回灌 [工具执行失败] 前缀文本)不落成假变更——写盘成功才回调监听器,
 *       失败即无回调(见 {@link FileChangeRecorderTest})。</li>
 *   <li><b>子 agent 共享 collector</b>:主/子 agent 经写监听记账到 provider 的
 *       同一任务级 collector,来源按 agentId==mainAgentId 标注 MAIN/SUB_AGENT,主 agent
 *       最后一轮(无工具调用)统一收口,轮闭合分片含子 agent 的文件改动。</li>
 *   <li><b>before/after 必须是文件级全文</b>:update_file 入参 oldcontent/content 只是
 *       被替换的片段,同文件多次写聚合「首条 before + 末条 after」若取入参片段会把两段
 *       不相干片段拼 diff(见 {@link FileChangeRecorderTest#sameFileTwoWritesAggregateFullSnapshots})。</li>
 * </ul>
 *
 * <p>收口时序契约:工具轮(模型流含 toolCalls)框架必继续递归,收口延后;主 agent 末轮
 * (无 toolCalls)收口暂存;流被取消/截断的 run 不收口,残留活跃实例由轮闭合回调清理
 * (宁漏记)。
 *
 * <p>脚手架风格参考 {@code AdaptiveMaxTokensAdvisorTest}:手写桩链(免 Mockito),
 * §14.9 插件测试零 worker 依赖。
 */
class FileChangeAdvisorTest {

    private static final String TASK = "t_fc";
    private static final String MAIN = "a_main";
    private static final String SUB = "a_sub";

    @Test
    void chainUpstreamRecordThenMainFinalizeWritesShardWithSubAgentChanges() throws Exception {
        TaskService taskService = taskService();
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(taskService);
        FileChangeRecorder recorder = new FileChangeRecorder(taskService, provider);
        ExecContext exec = runtime();
        FileChangeAdvisor main = new FileChangeAdvisor(agent(MAIN), taskService, provider);
        FileChangeAdvisor sub = new FileChangeAdvisor(agent(SUB), taskService, provider);

        // 子 agent 两轮在前(真实并行链路中子 agent 必在主 agent 收口前落定):
        // create /b.md 工具轮(模型流含 toolCalls → 子 agent 不收口,递归继续)
        StepVerifier.create(sub.adviseStream(request(), chain(toolCallChunk("call_s", "create_file",
                "{\"path\":\"/b.md\",\"content\":\"B\"}"))))
                .expectNextCount(1).verifyComplete();
        // 工具真实执行完毕(写盘成功) → 写锁内回调监听器一阶段记账(落共享 collector)
        recorder.onFileWritten(exec, SUB, FileWriteListener.TOOL_CREATE_FILE, "/b.md", null, "B");
        assertEquals(1, provider.activeCollector(TASK).buildSummaries().size(),
                "子 agent 成功的 /b.md 已记入共享 collector");

        // 主 agent 工具轮:update /a.md(只识别工具轮,记账在工具写盘回调;此刻工具尚未执行,
        // collector 还没有主 agent 的记录)
        StepVerifier.create(main.adviseStream(request(), chain(toolCallChunk("call_m", "update_file",
                "{\"path\":\"/a.md\",\"oldcontent\":\"旧片段\",\"content\":\"新片段\"}"))))
                .expectNextCount(1).verifyComplete();
        assertEquals(1, provider.activeCollector(TASK).buildSummaries().size(),
                "模型流时刻工具未执行,不记账");
        FileChangesCollector active = provider.activeCollector(TASK);
        // 工具执行完毕(写盘成功,携带写前/写后全文) → 记账(MAIN);随后主 agent 末轮(纯文本)收口
        recorder.onFileWritten(exec, MAIN, FileWriteListener.TOOL_UPDATE_FILE, "/a.md",
                "文件全文(旧)", "文件全文(新)");
        assertSame(active, provider.activeCollector(TASK), "收口前共享实例不变(工具轮不收口)");
        StepVerifier.create(main.adviseStream(request(), chain(textChunk("done"))))
                .expectNextCount(1).verifyComplete();

        // 收口退役活跃实例:新取的是空 collector(切断跨轮串扰),变更在暂存实例里等轮闭合落盘
        JsonNode meta = provider.activeCollector(TASK).buildMetadata().path("changes");
        assertEquals(0, meta.size(), "收口退役活跃实例后新取的是空 collector(切断跨轮串扰)");
        Path dir = Files.createTempDirectory("file-change-advisor-test");
        provider.onRoundsClosed(TASK, dir, List.of(new RoundClosedInfo("r_1", 1, 5L, 1)));
        JsonNode changes = Json.parse(
                Files.readString(dir.resolve("file-changes").resolve("r_1.json"))).path("changes");
        assertEquals(2, changes.size(), "分片应含主+子 agent 的文件变更");
        assertTrue(changes.toString().contains("/a.md"), "含主 agent 的 /a.md");
        assertTrue(changes.toString().contains("/b.md"), "含子 agent 的 /b.md(修复点)");
    }

    @Test
    void failedWriteLeavesEmptyRunAndNoShard() throws Exception {
        TaskService taskService = taskService();
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(taskService);
        FileChangeAdvisor main = new FileChangeAdvisor(agent(MAIN), taskService, provider);

        // create_file 失败(文件已存在 → 抛异常,无写盘):监听器不回调,不产生假变更
        StepVerifier.create(main.adviseStream(request(), chain(toolCallChunk("call_f", "create_file",
                "{\"path\":\"/t.txt\",\"content\":\"1\"}"))))
                .expectNextCount(1).verifyComplete();

        assertEquals(0, provider.activeCollector(TASK).buildSummaries().size(),
                "失败的 create_file 不得记为文件变更");
        // 主 agent 末轮收口:collector 空 → 跳过;轮闭合无分片
        StepVerifier.create(main.adviseStream(request(), chain(textChunk("改用 update_file 吧"))))
                .expectNextCount(1).verifyComplete();
        Path dir = Files.createTempDirectory("file-change-advisor-test");
        provider.onRoundsClosed(TASK, dir, List.of(new RoundClosedInfo("r_1", 1, 5L, 1)));
        assertFalse(Files.exists(dir.resolve("file-changes").resolve("r_1.json")),
                "全失败的 run 不产生变更分片");
    }

    @Test
    void toolTurnDoesNotFinalize() throws Exception {
        TaskService taskService = taskService();
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(taskService);
        FileChangeAdvisor main = new FileChangeAdvisor(agent(MAIN), taskService, provider);

        // 工具轮(模型流含 toolCalls):收口延后,共享实例保持活跃(同 run 后续轮继续记账)
        StepVerifier.create(main.adviseStream(request(), chain(toolCallChunk("call_1", "read_file",
                "{\"path\":\"/x\"}"))))
                .expectNextCount(1).verifyComplete();
        FileChangesCollector active = provider.activeCollector(TASK);
        StepVerifier.create(main.adviseStream(request(), chain(toolCallChunk("call_2", "update_file",
                "{\"path\":\"/a.md\",\"oldcontent\":\"x\",\"content\":\"y\"}"))))
                .expectNextCount(1).verifyComplete();
        assertSame(active, provider.activeCollector(TASK), "工具轮不收口,实例跨轮内稳定");
    }

    @Test
    void subAgentFinalTurnDoesNotFinalize() throws Exception {
        TaskService taskService = taskService();
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(taskService);
        FileChangeAdvisor sub = new FileChangeAdvisor(agent(SUB), taskService, provider);
        FileChangeRecorder recorder = new FileChangeRecorder(taskService, provider);

        // 子 agent 记账后自己的末轮(无工具轮):不收口,轮闭合无暂存 → 不写分片
        recorder.onFileWritten(runtime(), SUB, FileWriteListener.TOOL_CREATE_FILE, "/d.md", null, "D");
        StepVerifier.create(sub.adviseStream(request(), chain(textChunk("sub done"))))
                .expectNextCount(1).verifyComplete();
        Path dir = Files.createTempDirectory("file-change-advisor-test");
        provider.onRoundsClosed(TASK, dir, List.of(new RoundClosedInfo("r_1", 1, 5L, 1)));
        assertFalse(Files.exists(dir.resolve("file-changes").resolve("r_1.json")),
                "子 agent 不收口,不产生分片(记录留给主 agent 统一收口)");
    }

    @Test
    void cancelledRunNeverFinalizesAndRoundCloseCleansUp() throws Exception {
        TaskService taskService = taskService();
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(taskService);
        FileChangeRecorder recorder = new FileChangeRecorder(taskService, provider);

        // 已记账但流被取消(无末轮收口):残留活跃实例由轮闭合回调清理,不写分片(宁漏记)
        recorder.onFileWritten(runtime(), MAIN, FileWriteListener.TOOL_CREATE_FILE, "/e.md", null, "E");
        assertEquals(1, provider.activeCollector(TASK).buildSummaries().size(), "写盘成功已记账");
        Path dir = Files.createTempDirectory("file-change-advisor-test");
        provider.onRoundsClosed(TASK, dir, List.of(new RoundClosedInfo("r_1", 1, 5L, 1)));
        assertFalse(Files.exists(dir.resolve("file-changes").resolve("r_1.json")),
                "未收口的 run 不写分片");
        assertEquals(0, provider.activeCollector(TASK).buildSummaries().size(),
                "轮闭合清理残留活跃实例(防跨轮串数据)");
    }

    // ---- 测试脚手架(§14.9:自建桩,不依赖 worker) ----

    private static ChatClientRequest request(Message... messages) {
        return new ChatClientRequest(new Prompt(List.of(messages)), Map.of());
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
    private static ChatClientResponse toolCallChunk(String callId, String name, String arguments) {
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(callId, "function", name, arguments);
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
