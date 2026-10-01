package dev.everyagent.plugin.aireview;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.agent.Agent;
import dev.everyagent.plugin.api.agent.AgentBuilder;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import dev.everyagent.plugin.api.event.EventLogReader;
import dev.everyagent.plugin.api.event.EventRecord;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.task.TaskRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AiAuthReviewer 单测:审议三态解析 / 非 JSON 与缺字段回退 DENY /
 * review-model 缺省用任务 configId / 总预算超时(外层 future.get 到点 → cancel +
 * DENY 或回退,审计 trace 附 reason=timeout)/ 组件不注册任何工具 / 不新建任务实体 /
 * 审计 trace persist 落盘。模型调用全部用 mock Agent 模拟预设 lastText。
 *
 * <p>约束(§14.9):插件对 worker 任何 scope 零依赖,测试桩在本类内自建——
 * {@link RecordingTaskRuntime} 复刻 worker TaskEvents 的 EmitEvent→EventRecord
 * wire 映射(payload{title,summary,content,status,data}+ext{persist,operate}),
 * 不引用 worker 的 TaskEntry/TaskStore。
 */
class AiAuthReviewerTest {

    private WorkerConfig props;
    private AgentFactory agentFactory;
    private RecordingTaskRuntime task;

    @BeforeEach
    void setUp() {
        props = mock(WorkerConfig.class);
        WorkerConfig.Permissions perms = mock(WorkerConfig.Permissions.class);
        when(props.permissions()).thenReturn(perms);
        when(perms.reviewTimeoutMs()).thenReturn(10_000L);
        when(perms.reviewDenyOnError()).thenReturn(true);
        when(perms.reviewModel()).thenReturn("");

        agentFactory = mock(AgentFactory.class);
        task = newTask("task-cfg");
        setupAgentFactoryMock();
    }

    /** 通用 AgentFactory mock:create → AgentBuilder(plugin-api) → build() 返回 mock Agent。 */
    @SuppressWarnings("unchecked")
    private void setupAgentFactoryMock() {
        AgentBuilder mockBuild = mock(AgentBuilder.class);
        when(mockBuild.title(anyString())).thenReturn(mockBuild);
        when(mockBuild.tools(any(), any())).thenReturn(mockBuild);
        when(mockBuild.systemPrompt(anyString())).thenReturn(mockBuild);
        when(mockBuild.userInput(anyString())).thenReturn(mockBuild);
        when(mockBuild.options(any())).thenReturn(mockBuild);
        when(mockBuild.build()).thenAnswer(inv -> {
            // 返回一个 mock Agent,由 reviewer(ChatModel model) 设置 lastText
            Agent agent = mock(Agent.class);
            when(agent.lastText()).thenReturn(lastTextHolder.get());
            org.mockito.Mockito.doAnswer(runInv -> {
                // 模拟 run:如果有 error,抛异常;否则正常完成
                Runnable r = runAction.get();
                if (r != null) r.run();
                return null;
            }).when(agent).run();
            return agent;
        });
        when(agentFactory.create(anyString(), anyString(), any(), any())).thenReturn(mockBuild);
    }

    private final java.util.concurrent.atomic.AtomicReference<String> lastTextHolder = new java.util.concurrent.atomic.AtomicReference<>("");
    private final java.util.concurrent.atomic.AtomicReference<Runnable> runAction = new java.util.concurrent.atomic.AtomicReference<>();

    private RecordingTaskRuntime newTask(String cfgId) {
        ModelConfig snap = new ModelConfig(cfgId, "openai-compat",
                "http://localhost:9999/v1", "task-model", null);
        return new RecordingTaskRuntime("t-1", snap);
    }

    /** 组装授权请求桩(agentId 不参与审议,置 null)。 */
    private AuthorizationHandler.AuthorizationRequest req(String grantKey, String prompt) {
        return new AuthorizationHandler.AuthorizationRequest(task, null, grantKey, prompt);
    }

    private AiAuthReviewer reviewer(String content) {
        lastTextHolder.set(content);
        runAction.set(null);
        return new AiAuthReviewer(props, agentFactory);
    }

    private AiAuthReviewer hangReviewer(long sleepMs) {
        lastTextHolder.set("{\"decision\":\"ALLOW\"}");
        runAction.set(() -> {
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("审议调用被中断", e);
            }
        });
        return new AiAuthReviewer(props, agentFactory);
    }

    private AiAuthReviewer failReviewer() {
        lastTextHolder.set("");
        runAction.set(() -> { throw new RuntimeException("模拟审议模型故障"); });
        return new AiAuthReviewer(props, agentFactory);
    }

    // ---- 三态解析 ----

    @Test
    void parsesAllow() {
        ReviewDecision d = reviewer("{\"decision\":\"ALLOW\",\"confidence\":0.9,\"reason\":\"安全\"}")
                .review(req("c::del", "AI 请求删除工作区外文件"));
        assertEquals(ReviewDecision.Verdict.ALLOW, d.verdict());
        assertFalse(d.fallback());
        assertEquals(0.9, d.confidence());
        assertEquals("run", d.scope());
        assertEquals("安全", d.reason());
        assertAuthTrace("ALLOW", "安全");
    }

    @Test
    void parsesDenyCaseInsensitive() {
        ReviewDecision d = reviewer("{\"decision\":\"deny\",\"confidence\":\"0.2\",\"reason\":\"危险\"}")
                .review(req("c::rm", "AI 请求 rm -rf"));
        assertEquals(ReviewDecision.Verdict.DENY, d.verdict());
        assertFalse(d.fallback());
        assertEquals(0.2, d.confidence());
        assertEquals("deny", d.scope());
        assertAuthTrace("DENY", "危险");
    }

    @Test
    void parsesEscalate() {
        ReviewDecision d = reviewer("{\"decision\":\"ESCALATE\",\"confidence\":0.5,\"reason\":\"不确定\"}")
                .review(req("p::read::x", "AI 请求读取工作区外路径"));
        assertEquals(ReviewDecision.Verdict.ESCALATE, d.verdict());
        assertFalse(d.fallback());
        assertEquals("deny", d.scope());
        assertAuthTrace("ESCALATE", "不确定");
    }

    /** 容忍 markdown 代码围栏与前导/尾随空白。 */
    @Test
    void parsesJsonInsideMarkdownFence() {
        ReviewDecision d = reviewer("```json\n  {\"decision\":\"ALLOW\",\"confidence\":0.8,\"reason\":\"ok\"}  \n```")
                .review(req("c::del", "AI 请求"));
        assertEquals(ReviewDecision.Verdict.ALLOW, d.verdict());
    }

    // ---- 非 JSON / 缺字段 → 默认 DENY(fail-closed) ----

    @Test
    void nonJsonDefaultsToDeny() {
        ReviewDecision d = reviewer("不好意思我无法判断").review(req("c::del", "AI 请求"));
        assertEquals(ReviewDecision.Verdict.DENY, d.verdict());
        assertFalse(d.fallback());
        assertTrue(d.reason().contains("非 JSON"), d.reason());
        assertAuthTrace("DENY", null);
    }

    @Test
    void emptyResponseDefaultsToDeny() {
        ReviewDecision d = reviewer("").review(req("c::del", "AI 请求"));
        assertEquals(ReviewDecision.Verdict.DENY, d.verdict());
        assertTrue(d.reason().contains("空响应"), d.reason());
        assertAuthTrace("DENY", null);
    }

    @Test
    void missingDecisionFieldDefaultsToDeny() {
        ReviewDecision d = reviewer("{\"confidence\":0.9,\"reason\":\"无结论\"}").review(req("c::del", "AI 请求"));
        assertEquals(ReviewDecision.Verdict.DENY, d.verdict());
        assertFalse(d.fallback());
        assertTrue(d.reason().contains("缺 decision"), d.reason());
    }

    @Test
    void illegalDecisionValueDefaultsToDeny() {
        ReviewDecision d = reviewer("{\"decision\":\"MAYBE\"}").review(req("c::del", "AI 请求"));
        assertEquals(ReviewDecision.Verdict.DENY, d.verdict());
        assertTrue(d.reason().contains("非法 decision"), d.reason());
    }

    // ---- 总预算超时:future.get 到点 → cancel + DENY/回退,审计 trace 附 reason=timeout ----

    @Test
    void totalBudgetTimeoutDefaultsToDeny() {
        when(props.permissions().reviewTimeoutMs()).thenReturn(100L);
        when(props.permissions().reviewDenyOnError()).thenReturn(true);
        long start = System.currentTimeMillis();
        ReviewDecision d = hangReviewer(5_000).review(req("c::del", "AI 请求"));
        assertTrue(System.currentTimeMillis() - start < 5_000, "外层总预算硬闸应提前返回");
        assertEquals(ReviewDecision.Verdict.DENY, d.verdict());
        assertFalse(d.fallback());
        assertTrue(d.reason().contains("timeout"), d.reason());
        assertEquals("DENY", authTracePayload().path("decision").asString());
        assertTrue(authTracePayload().path("reason").asString().contains("timeout"));
    }

    @Test
    void totalBudgetTimeoutFallsBackWhenDenyOnErrorFalse() {
        when(props.permissions().reviewTimeoutMs()).thenReturn(100L);
        when(props.permissions().reviewDenyOnError()).thenReturn(false);
        ReviewDecision d = hangReviewer(5_000).review(req("c::del", "AI 请求"));
        assertEquals(ReviewDecision.Verdict.DENY, d.verdict());
        assertTrue(d.fallback(), "deny-on-error=false 应返回 fallback 标记(回退人工弹窗)");
        assertTrue(d.reason().contains("timeout"), d.reason());
        assertTrue(authTracePayload().path("reason").asString().contains("timeout"));
    }

    // ---- 异常回退 ----

    @Test
    void modelErrorDeniesByDefault() {
        when(props.permissions().reviewDenyOnError()).thenReturn(true);
        ReviewDecision d = failReviewer().review(req("c::del", "AI 请求"));
        assertEquals(ReviewDecision.Verdict.DENY, d.verdict());
        assertFalse(d.fallback());
        assertTrue(d.reason().contains("error"), d.reason());
        assertAuthTrace("DENY", null);
    }

    @Test
    void modelErrorFallsBackWhenDenyOnErrorFalse() {
        when(props.permissions().reviewDenyOnError()).thenReturn(false);
        ReviewDecision d = failReviewer().review(req("c::del", "AI 请求"));
        assertEquals(ReviewDecision.Verdict.DENY, d.verdict());
        assertTrue(d.fallback(), "deny-on-error=false 应回退人工(fallback 标记)");
        assertTrue(d.reason().contains("error"), d.reason());
    }

    // ---- 组件不注册任何工具 / 不新建任务实体 / 审计 trace persist 落盘 ----

    @Test
    void doesNotCreateNewTaskEntryOrEventLog() {
        reviewer("{\"decision\":\"ALLOW\"}").review(req("c::del", "AI 请求"));
        assertTrue(task.agents().isEmpty(), "审议不复用/新建子 agent 集合");
        assertNotNull(authTracePayload(), "原任务应有 auth.review trace");
        assertTrue(authTraceEvent().ext().path("persist").asBoolean(),
                "persist=true 落盘 trace 的 ext.persist 应为 true(非瞬态)");
    }

    // ---- 辅助:读取桩内记录的 auth.review trace ----

    private JsonNode authTracePayload() {
        return authTraceEvent().payload().path("data");
    }

    private EventRecord authTraceEvent() {
        for (EventRecord r : task.log().readFrom(0, 100)) {
            if ("auth.review".equals(r.event())) {
                return r;
            }
        }
        throw new AssertionError("未找到 auth.review trace");
    }

    private void assertAuthTrace(String decision, String reason) {
        JsonNode payload = authTraceEvent().payload();
        assertEquals("AI 安全审议", payload.path("title").asString());
        // summary = verdict + " — " + reason(reason 非空时)
        assertTrue(payload.path("summary").asString().contains(decision), payload.path("summary").asString());
        assertEquals(decision, authTracePayload().path("decision").asString());
        assertEquals(task.taskId(), authTracePayload().path("taskId").asString());
        assertTrue(authTracePayload().path("agentId").asString().startsWith("review"));
        assertTrue(authTracePayload().has("prompt"));
        assertTrue(authTracePayload().has("grantKey"));
        assertTrue(authTracePayload().has("scope"));
        assertTrue(authTracePayload().has("confidence"));
        assertTrue(authTracePayload().has("reason"));
        if (reason != null) {
            assertEquals(reason, authTracePayload().path("reason").asString());
        }
        assertTrue(authTraceEvent().ext().path("persist").asBoolean(),
                "审议结论 persist=true 落盘,ext.persist 应为 true");
    }

    // ---- 自建等价桩(实现 plugin-api 接口;§14.9) ----

    /**
     * TaskRuntime 记录桩(原借 worker TaskEntry + TaskEvents):
     * events() 发射的 EmitEvent 按 wire 口径复刻为 EventRecord(event=kind,
     * payload{title,summary,content,status,data},ext{persist,operate}),
     * log() 只读回放同一列表,供测试断言审计 trace。
     */
    private static final class RecordingTaskRuntime implements TaskRuntime {
        private final String taskId;
        private final ModelConfig snapshot;
        private final Map<String, AgentContext> agentsMap = new HashMap<>();
        private final List<EventRecord> records = new CopyOnWriteArrayList<>();

        RecordingTaskRuntime(String taskId, ModelConfig snapshot) {
            this.taskId = taskId;
            this.snapshot = snapshot;
        }

        private final EventEmitter emitter = e -> {
            ObjectNode payload = Json.obj();
            if (e.title() != null && !e.title().isEmpty()) {
                payload.put("title", e.title());
            }
            if (e.summary() != null && !e.summary().isEmpty()) {
                payload.put("summary", e.summary());
            }
            if (e.content() != null && !e.content().isEmpty()) {
                payload.put("content", e.content());
            }
            if (e.status() != null && !e.status().isEmpty()) {
                payload.put("status", e.status());
            }
            if (e.data() != null) {
                payload.set("data", Json.toJson(e.data()));
            }
            ObjectNode ext = Json.obj();
            ext.put("persist", e.persist());
            ext.put("operate", e.mode() == EmitEvent.Mode.APPEND ? "append" : "replace");
            records.add(new EventRecord(e.id(), System.currentTimeMillis(), e.kind(),
                    e.agentId(), payload, ext));
            return e.id();
        };

        private final EventLogReader reader = new EventLogReader() {
            @Override public List<EventRecord> readFrom(int from, int max) {
                if (from < 0 || from >= records.size()) {
                    return List.of();
                }
                return List.copyOf(records.subList(from, Math.min(from + max, records.size())));
            }
            @Override public List<EventRecord> readAfterSeq(long afterSeq, int max) {
                return records.stream().filter(r -> r.seq() > afterSeq)
                        .limit(max).toList();
            }
            @Override public void addListener(Listener listener) { }
            @Override public void removeListener(Listener listener) { }
        };

        @Override public String taskId() { return taskId; }
        @Override public String status() { return "running"; }
        @Override public boolean terminal() { return false; }
        @Override public Map<String, Object> metadata() { return new HashMap<>(); }
        @Override public Path taskDir() { return Path.of("workspaces", "defaultworkspace", "tasks", taskId); }
        @Override public String workspaceRoot() { return "ws"; }
        @Override public String workspaceId() { return "defaultworkspace"; }
        @Override public String mainAgentId() { return "main-agent"; }
        @Override public ModelConfig snapshot() { return snapshot; }
        @Override public EventEmitter events() { return emitter; }
        @Override public Map<String, AgentContext> agents() { return agentsMap; }
        @Override public AgentContext main() { return null; }
        @Override public EventLogReader log() { return reader; }
        @Override public ObjectNode summaryJson() { return Json.obj(); }
        @Override public dev.everyagent.plugin.api.task.FileChangesCollector fileChanges() { return null; }
        @Override public void fileChanges(dev.everyagent.plugin.api.task.FileChangesCollector c) { }
        @Override public JsonNode fileChangesLight() { return null; }
        @Override public void fileChangesLight(JsonNode light) { }
        @Override public JsonNode fileChangesFull() { return null; }
        @Override public void fileChangesFull(JsonNode full) { }
        @Override public long startedAt() { return 0; }
        @Override public long endedAt() { return 0; }
        @Override public void touch() { }
        @Override public void truncateLogAfter(long targetSeq) {
            records.removeIf(r -> r.seq() >= targetSeq);
        }
    }
}
