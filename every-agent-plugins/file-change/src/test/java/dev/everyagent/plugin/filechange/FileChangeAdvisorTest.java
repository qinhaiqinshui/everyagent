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
import dev.everyagent.plugin.api.spi.ToolOutcomeLog;
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
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
 * {@link FileChangeAdvisor} 两阶段确认(主/子 agent 共享 collector)回归测试。
 *
 * <p>修复的 bug(两枚):
 * <ul>
 *   <li><b>失败写不记账(本批)</b>:模型流里看到 create_file/update_file 就立即记入 collector,
 *       而「文件已存在」「oldcontent 不唯一」「授权拒绝」等失败调用(工具抛异常,回灌
 *       [工具执行失败] 文本)也被落成假变更。修复后模型流侧只<b>暂存</b>(callId→记录),
 *       下一轮请求入口按 callId 配对 ToolResponseMessage,经 ToolOutcomeLog.toolThrew
 *       (结构化账本非消费查询)裁决:抛过异常即丢弃,正常返回才提交 collector。</li>
 *   <li><b>子 agent 共享 collector(既往)</b>:子 agent 工具调用的文件变更曾落入 per-run 私有
 *       collector,而收口守卫「只主 agent 收口」导致这些记录被静默丢弃。修复后主/子 agent 的
 *       advisor 经 execution().subjectId() 记录到 provider 的同一任务级 collector,来源按
 *       agentId==mainAgentId 标注 MAIN/SUB_AGENT;主 agent 最后一轮(无工具调用)收口暂存共享
 *       collector,轮闭合回调写出的分片含子 agent 的文件改动;子 agent 的无工具轮不收口。</li>
 * </ul>
 *
 * <p>时序契约:工具轮之后框架必递归出携带结果的下一轮请求,故每个待确认记录必经请求入口裁决;
 * 流截断等未裁决场景由收口守卫兜底(未确认一律丢弃,宁漏记勿假记)。
 *
 * <p>脚手架风格参考 {@code AdaptiveMaxTokensAdvisorTest}:手写桩链(免 Mockito),
 * §14.9 插件测试零 worker 依赖;账本用真实 {@link ToolOutcomeLog}。
 */
class FileChangeAdvisorTest {

    private static final String TASK = "t_fc";
    private static final String MAIN = "a_main";
    private static final String SUB = "a_sub";

    @Test
    void subAgentFileChangesLandInSharedCollectorAndShard() throws IOException {
        TaskService taskService = taskService();
        ToolOutcomeLog outcomes = new ToolOutcomeLog();
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(taskService);
        FileChangeAdvisor main = new FileChangeAdvisor(agent(MAIN), taskService, provider, outcomes);
        FileChangeAdvisor sub = new FileChangeAdvisor(agent(SUB), taskService, provider, outcomes);

        // 子 agent 两轮在前(真实并行链路中子 agent 必在主 agent 收口前落定;若把主 agent 的
        // 确认收口放在前面,共享 collector 会被 markPending 退役,子 agent 只能写到新实例):
        // 工具轮建 /b.md → 暂存;下一轮确认 → 记入同一共享 collector(修复点:此前落入子 agent 私有实例)
        StepVerifier.create(sub.adviseStream(request(), chain(toolCallChunk("call_s", "create_file",
                "{\"path\":\"/b.md\",\"content\":\"B\"}"))))
                .expectNextCount(1).verifyComplete();
        StepVerifier.create(sub.adviseStream(request(toolResponse("call_s", "create_file", "已创建并保存到：/b.md")),
                chain(textChunk("sub done"))))
                .expectNextCount(1).verifyComplete();
        assertEquals(1, provider.activeCollector(TASK).buildSummaries().size(),
                "子 agent 已确认的 /b.md 在共享 collector(此刻尚未收口,实例仍活跃)");

        // 主 agent 工具轮:改 /a.md → 只暂存,collector 此刻还没有主 agent 的记录
        StepVerifier.create(main.adviseStream(request(), chain(toolCallChunk("call_m", "update_file",
                "{\"path\":\"/a.md\",\"oldcontent\":\"旧\",\"content\":\"新\"}"))))
                .expectNextCount(1).verifyComplete();
        assertEquals(1, provider.activeCollector(TASK).buildSummaries().size(),
                "主 agent 工具轮只暂存不记账");
        // 主 agent 下一轮请求携带工具结果(未抛异常=成功) → 确认记入共享 collector;
        // 本轮模型继续调用非文件写工具(read_file,不暂存)→ 不收口,先断言聚合状态
        StepVerifier.create(main.adviseStream(request(toolResponse("call_m", "update_file", "已更新到：/a.md")),
                chain(toolCallChunk("call_z", "read_file", "{\"path\":\"/x\"}"))))
                .expectNextCount(1).verifyComplete();

        // 同一实例 + 来源标注:MAIN / SUB_AGENT(按写入顺序:子 agent 先确认落账)
        FileChangesCollector collector = provider.activeCollector(TASK);
        JsonNode meta = collector.buildMetadata().path("changes");
        assertEquals(2, meta.size());
        assertEquals("SUB_AGENT", meta.get(0).path("source").asString());
        assertEquals(SUB, meta.get(0).path("agentId").asString());
        assertEquals("MAIN", meta.get(1).path("source").asString());
        assertEquals(MAIN, meta.get(1).path("agentId").asString());

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
    void failedFileWriteIsNotRecorded() throws IOException {
        TaskService taskService = taskService();
        ToolOutcomeLog outcomes = new ToolOutcomeLog();
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(taskService);
        FileChangeAdvisor main = new FileChangeAdvisor(agent(MAIN), taskService, provider, outcomes);

        // 轮1:create_file(文件已存在,工具将抛异常)→ 暂存
        String args = "{\"path\":\"/t.txt\",\"content\":\"1\"}";
        StepVerifier.create(main.adviseStream(request(), chain(toolCallChunk("call_f", "create_file", args))))
                .expectNextCount(1).verifyComplete();
        assertEquals(0, provider.activeCollector(TASK).buildSummaries().size(), "暂存期不记账");
        // per-call 包装器记账「抛异常」(与真实链路同键:工具名 + 入参原串)
        outcomes.recordToolThrow("create_file", args);

        // 轮2:请求携带工具结果(回灌 [工具执行失败] 文本;文本只是礼仪,裁决走结构化账本)
        StepVerifier.create(main.adviseStream(
                request(toolResponse("call_f", "create_file", "[工具执行失败] 文件已存在,如需修改请用 update_file")),
                chain(textChunk("改用 update_file 吧"))))
                .expectNextCount(1).verifyComplete();

        // 修复点:失败调用被丢弃,不产生假变更
        assertEquals(0, provider.activeCollector(TASK).buildSummaries().size(),
                "失败的 create_file 不得记为文件变更");

        // 收口(最后一轮无工具调用):collector 空 → 不写分片
        StepVerifier.create(main.adviseStream(request(), chain(textChunk("done"))))
                .expectNextCount(1).verifyComplete();
        Path dir = Files.createTempDirectory("file-change-advisor-test");
        provider.onRoundsClosed(TASK, dir, List.of(new RoundClosedInfo("r_1", 1, 5L, 1)));
        assertFalse(Files.exists(dir.resolve("file-changes").resolve("r_1.json")),
                "全失败的 run 不产生变更分片");
    }

    @Test
    void failedThenSucceededWritesOfSameRun() throws IOException {
        TaskService taskService = taskService();
        ToolOutcomeLog outcomes = new ToolOutcomeLog();
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(taskService);
        FileChangeAdvisor main = new FileChangeAdvisor(agent(MAIN), taskService, provider, outcomes);

        // 轮1:update_file 失败(oldcontent 不唯一)→ 暂存 + per-call 包装器记账异常
        String badArgs = "{\"path\":\"/c.md\",\"oldcontent\":\"X\",\"content\":\"Y\"}";
        StepVerifier.create(main.adviseStream(request(), chain(toolCallChunk("call_b", "update_file", badArgs))))
                .expectNextCount(1).verifyComplete();
        outcomes.recordToolThrow("update_file", badArgs);
        // 轮2:请求携带失败结果 → 确认丢弃;模型看到失败文本后改参数重试(不同 arguments → 不同账本键)
        String goodArgs = "{\"path\":\"/c.md\",\"oldcontent\":\"X\\n上下文\",\"content\":\"Y\"}";
        StepVerifier.create(main.adviseStream(
                request(toolResponse("call_b", "update_file", "[工具执行失败] oldcontent 在文档中出现多次,不唯一,无法确定替换位置")),
                chain(toolCallChunk("call_g", "update_file", goodArgs))))
                .expectNextCount(1).verifyComplete();
        assertEquals(0, provider.activeCollector(TASK).buildSummaries().size(), "失败调用被丢弃");

        // 轮3:重试成功的结果到达 → 确认记账;本轮纯文本收口
        StepVerifier.create(main.adviseStream(
                request(toolResponse("call_g", "update_file", "已更新到：/c.md")),
                chain(textChunk("done"))))
                .expectNextCount(1).verifyComplete();

        // 同 run 内失败后的成功重写仍要记账(真实变更),且只记成功的那次
        Path dir = Files.createTempDirectory("file-change-advisor-test");
        provider.onRoundsClosed(TASK, dir, List.of(new RoundClosedInfo("r_1", 1, 5L, 1)));
        JsonNode changes = Json.parse(
                Files.readString(dir.resolve("file-changes").resolve("r_1.json"))).path("changes");
        assertEquals(1, changes.size(), "分片只含成功重写的一条");
        assertEquals("/c.md", changes.get(0).path("filePath").asString());
        assertEquals(1, changes.get(0).path("saveCount").asInt(), "失败的那次不计入 saveCount");
    }

    @Test
    void unconfirmedPendingDiscardedAtFinalize() throws IOException {
        TaskService taskService = taskService();
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(taskService);
        FileChangeAdvisor main = new FileChangeAdvisor(agent(MAIN), taskService, provider, new ToolOutcomeLog());

        // 轮1:create_file 暂存后,下一轮请求【不携带】工具结果(流截断/工具未执行的异常形态)
        StepVerifier.create(main.adviseStream(request(), chain(toolCallChunk("call_x", "create_file",
                "{\"path\":\"/d.md\",\"content\":\"D\"}"))))
                .expectNextCount(1).verifyComplete();
        // 轮2:空请求历史 + 纯文本 → 收口守卫应丢弃未确认记录(宁漏记勿假记)
        StepVerifier.create(main.adviseStream(request(), chain(textChunk("done"))))
                .expectNextCount(1).verifyComplete();
        assertEquals(0, provider.activeCollector(TASK).buildSummaries().size(),
                "未确认(结果未知)的调用不落账");
    }

    @Test
    void subAgentFinalTurnDoesNotFinalize() throws IOException {
        TaskService taskService = taskService();
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(taskService);
        FileChangeAdvisor sub = new FileChangeAdvisor(agent(SUB), taskService, provider, new ToolOutcomeLog());

        // 子 agent 无工具轮(其 run 的最后一轮):不收口,轮闭合无暂存 → 不写分片
        StepVerifier.create(sub.adviseStream(request(), chain(textChunk("sub done"))))
                .expectNextCount(1).verifyComplete();
        Path dir = Files.createTempDirectory("file-change-advisor-test");
        provider.onRoundsClosed(TASK, dir, List.of(new RoundClosedInfo("r_1", 1, 5L, 1)));
        assertFalse(Files.exists(dir.resolve("file-changes").resolve("r_1.json")),
                "子 agent 不收口,不产生分片");
    }

    // ---- 测试脚手架(§14.9:自建桩,不依赖 worker) ----

    private static ChatClientRequest request(Message... messages) {
        return new ChatClientRequest(new Prompt(List.of(messages)), Map.of());
    }

    /** 工具结果消息桩(id 与 toolCallChunk 的 callId 配对;构造走公开 builder)。 */
    private static ToolResponseMessage toolResponse(String callId, String name, String responseData) {
        return ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse(callId, name, responseData))).build();
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
