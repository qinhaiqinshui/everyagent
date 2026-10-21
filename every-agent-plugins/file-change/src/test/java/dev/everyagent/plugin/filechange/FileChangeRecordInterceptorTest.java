package dev.everyagent.plugin.filechange;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.api.spi.ToolExecutionChain;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import dev.everyagent.plugin.api.spi.ToolOutcomeLog;
import dev.everyagent.plugin.api.task.StoredTaskInfo;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.task.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileChangeRecordInterceptor} per-call 链上行一阶段记账测试(架构 §7.15.2):
 * <ul>
 *   <li>成功调用(前缀无 ∧ callId 账 false/无账)→ 解析入参写任务级共享 collector,
 *       Source 按 agentId==mainAgentId 标注 MAIN/SUB_AGENT;</li>
 *   <li>失败调用不记账:①结果串失败前缀(文件工具失败=抛异常→处理器产出前缀文本);
 *       ②callId 账记 true(发射点 statusOf 判定落账);</li>
 *   <li>非文件写工具放行不记账;无任务上下文/无 agent 归属不收集;主体已收口不记账(宁漏记)。</li>
 * </ul>
 *
 * <p>脚手架按 §14.9 插件测试零 worker 依赖:ToolExecutionContext 手写桩,TaskRuntime 动态代理。
 */
class FileChangeRecordInterceptorTest {

    private static final String TASK = "t_fc";
    private static final String MAIN = "a_main";
    private static final String SUB = "a_sub";
    private static final String FAILURE_PREFIX = ExecResults.TOOL_FAILURE_PREFIX;

    private final ToolOutcomeLog outcomes = new ToolOutcomeLog();
    private final TaskService taskService = taskService(MAIN);
    private final FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(taskService);
    private final FileChangeRecordInterceptor interceptor =
            new FileChangeRecordInterceptor(taskService, provider);

    @Test
    void successfulWritesRecordedWithMainAndSubAgentSource() throws Exception {
        // 主 agent update_file 成功(callId 账记 false)
        outcomes.recordCallOutcome("c_m", false);
        interceptor.invoke(ctx("c_m", "update_file",
                "{\"path\":\"/a.md\",\"oldcontent\":\"旧\",\"content\":\"新\"}", MAIN, TASK, outcomes),
                ok("已更新到：/a.md"));
        // 子 agent create_file 成功(无账,结果无前缀 → 按成功)
        interceptor.invoke(ctx("c_s", "create_file",
                "{\"path\":\"/b.md\",\"content\":\"B\"}", SUB, TASK, outcomes),
                ok("已创建并保存到：/b.md"));

        FileChangesCollector collector = provider.activeCollector(TASK);
        assertEquals(2, collector.buildSummaries().size(), "两条成功写各记一条");
        JsonNode meta = collector.buildMetadata().path("changes");
        assertEquals("MAIN", meta.get(0).path("source").asString(), "主 agent 改动标 MAIN");
        assertEquals(MAIN, meta.get(0).path("agentId").asString());
        assertEquals("SUB_AGENT", meta.get(1).path("source").asString(), "子 agent 改动标 SUB_AGENT");
        assertEquals(SUB, meta.get(1).path("agentId").asString());
        // update_file 落 modified + before/after,create_file 落 created + before 为空
        assertEquals("/a.md", collector.buildSummaries().get(0).filePath());
        assertEquals("updated", collector.buildSummaries().get(0).changeType());
        assertEquals("旧", collector.buildSummaries().get(0).beforeContent());
        assertEquals("新", collector.buildSummaries().get(0).afterContent());
        assertEquals("created", collector.buildSummaries().get(1).changeType());
        assertEquals("", collector.buildSummaries().get(1).beforeContent());
    }

    @Test
    void failedByFailurePrefixIsNotRecorded() throws Exception {
        // 文件工具失败 = 抛异常 → 异常处理器产出前缀文本(文件已存在)
        interceptor.invoke(ctx("c_f", "create_file",
                "{\"path\":\"/t.txt\",\"content\":\"1\"}", MAIN, TASK, outcomes),
                ok(FAILURE_PREFIX + " 文件已存在,如需修改请用 update_file"));

        assertTrue(provider.activeCollector(TASK).buildSummaries().isEmpty(),
                "失败(前缀)的 create_file 不得记为文件变更");
    }

    @Test
    void failedByCallIdAccountIsNotRecorded() throws Exception {
        // callId 账记 true(发射点 statusOf 判定失败)——结果串无前缀也不记账
        outcomes.recordCallOutcome("c_b", true);
        interceptor.invoke(ctx("c_b", "update_file",
                "{\"path\":\"/c.md\",\"oldcontent\":\"X\",\"content\":\"Y\"}", MAIN, TASK, outcomes),
                ok("已更新到：/c.md"));

        assertTrue(provider.activeCollector(TASK).buildSummaries().isEmpty(),
                "callId 账记失败的调用不得记账");
    }

    @Test
    void failedThenSucceededRetryRecordsOnlySuccess() throws Exception {
        // 第一次失败(oldcontent 不唯一 → 前缀文本),模型改参重试成功
        interceptor.invoke(ctx("c_1", "update_file",
                "{\"path\":\"/c.md\",\"oldcontent\":\"X\",\"content\":\"Y\"}", MAIN, TASK, outcomes),
                ok(FAILURE_PREFIX + " oldcontent 在文档中出现多次,不唯一"));
        outcomes.recordCallOutcome("c_2", false);
        interceptor.invoke(ctx("c_2", "update_file",
                "{\"path\":\"/c.md\",\"oldcontent\":\"X\\n上下文\",\"content\":\"Y\"}", MAIN, TASK, outcomes),
                ok("已更新到：/c.md"));

        var summaries = provider.activeCollector(TASK).buildSummaries();
        assertEquals(1, summaries.size(), "只记成功的那次");
        assertEquals("/c.md", summaries.get(0).filePath());
        assertEquals(1, summaries.get(0).saveCount(), "失败的那次不计入 saveCount");
    }

    @Test
    void nonFileWriteToolsAreIgnored() throws Exception {
        interceptor.invoke(ctx("c_r", "read_file", "{\"path\":\"/x\"}", MAIN, TASK, outcomes), ok("内容"));
        interceptor.invoke(ctx("c_p", "powershell", "{\"command\":\"echo hi\"}", MAIN, TASK, outcomes), ok("hi"));
        // 未装配账本(null)也照常放行不记账
        interceptor.invoke(ctx("c_n", "read_file", "{\"path\":\"/y\"}", MAIN, TASK, null), ok("y"));

        assertTrue(provider.activeCollector(TASK).buildSummaries().isEmpty(), "非文件写工具不记账");
    }

    @Test
    void withoutTaskContextOrAgentIdNothingCollected() throws Exception {
        interceptor.invoke(ctx("c_1", "create_file", "{\"path\":\"/a\",\"content\":\"A\"}", MAIN, null, outcomes),
                ok("ok"));
        interceptor.invoke(ctx("c_2", "create_file", "{\"path\":\"/b\",\"content\":\"B\"}", null, TASK, outcomes),
                ok("ok"));

        assertTrue(provider.activeCollector(TASK).buildSummaries().isEmpty(),
                "无任务上下文(裸用)不收集");
    }

    @Test
    void terminalSubjectSkipsRecording() throws Exception {
        StubCallContext c = ctx("c_1", "create_file",
                "{\"path\":\"/d.md\",\"content\":\"D\"}", MAIN, TASK, outcomes);
        c.setTerminal(true);
        interceptor.invoke(c, ok("已创建并保存到：/d.md"));

        assertTrue(provider.activeCollector(TASK).buildSummaries().isEmpty(),
                "主体已收口(轮已闭合)不再记账,宁漏记不造孤儿 collector");
    }

    @Test
    void resultStringPassesThroughUnchanged() throws Exception {
        String out = interceptor.invoke(ctx("c_1", "update_file",
                "{\"path\":\"/a.md\",\"oldcontent\":\"x\",\"content\":\"y\"}", MAIN, TASK, outcomes),
                ok("已更新到：/a.md"));
        assertEquals("已更新到：/a.md", out, "记账节点不改写结果串");
    }

    // ---- 测试脚手架(§14.9:自建桩,不依赖 worker) ----

    private static ToolExecutionChain ok(String result) {
        return c -> result;
    }

    /** per-call ctx 最小桩:拦截器只读 toolCall/arguments/subjectId/agentId/toolOutcomes/terminal。 */
    private static StubCallContext ctx(String callId, String name, String arguments,
            String agentId, String subjectId, ToolOutcomeLog outcomes) {
        return new StubCallContext(callId, name, arguments, agentId, subjectId, outcomes);
    }

    private static final class StubCallContext implements ToolExecutionContext {
        private final org.springframework.ai.chat.messages.AssistantMessage.ToolCall toolCall;
        private final String agentId;
        private final String subjectId;
        private final ToolOutcomeLog outcomes;
        private boolean terminal;

        StubCallContext(String callId, String name, String arguments,
                String agentId, String subjectId, ToolOutcomeLog outcomes) {
            this.toolCall = new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                    callId, "function", name, arguments);
            this.agentId = agentId;
            this.subjectId = subjectId;
            this.outcomes = outcomes;
        }

        void setTerminal(boolean v) {
            this.terminal = v;
        }

        @Override public Prompt prompt() { return null; }
        @Override public ChatResponse chatResponse() { return null; }
        @Override public java.util.List<org.springframework.ai.chat.messages.AssistantMessage.ToolCall> toolCalls() {
            return java.util.List.of(toolCall);
        }
        @Override public org.springframework.ai.chat.messages.AssistantMessage.ToolCall toolCall() {
            return toolCall;
        }
        @Override public ToolCallback toolCallback() { return null; }
        @Override public String arguments() { return toolCall.arguments(); }
        @Override public void arguments(String arguments) { }
        @Override public void synthesize(String result) { }
        @Override public String synthesizedResult() { return null; }
        @Override public long startedAt() { return 0; }
        @Override public void startedAt(long epochMs) { }
        @Override public ToolOutcomeLog toolOutcomes() { return outcomes; }
        @Override public String agentId() { return agentId; }
        @Override public String subjectId() { return subjectId; }
        @Override public String workspaceRoot() { return "/tmp"; }
        @Override public String workspaceId() { return "w_1"; }
        @Override public ModelConfig snapshot() { return null; }
        @Override public EventEmitter emitter() { return e -> e.id(); }
        @Override public AgentFactory agentFactory() { return null; }
        @Override public Map<String, Object> metadata() { return Map.of(); }
        @Override public Path dataDir() { return null; }
        @Override public boolean terminal() { return terminal; }
        @Override public InteractionService interaction() { return null; }
        @Override public Map<String, AgentContext> agents() { return Map.of(); }
    }

    private static TaskService taskService(String mainAgentId) {
        TaskRuntime runtime = (TaskRuntime) Proxy.newProxyInstance(
                FileChangeRecordInterceptorTest.class.getClassLoader(),
                new Class<?>[] { TaskRuntime.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "mainAgentId" -> mainAgentId;
                    case "taskId", "subjectId" -> TASK;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
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
}
