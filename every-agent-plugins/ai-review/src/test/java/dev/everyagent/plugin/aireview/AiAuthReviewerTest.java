package dev.everyagent.plugin.aireview;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.agent.Agent;
import dev.everyagent.plugin.api.agent.AgentBuilder;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.event.EventRecord;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiAuthReviewer 单测:审议三态解析 / 非 JSON 与缺字段回退 DENY /
 * review-model 缺省用任务 configId / 总预算超时(外层 future.get 到点 → cancel +
 * DENY 或回退,审计 trace 附 reason=timeout)/ 组件不注册任何工具 / 不新建 TaskEntry/EventLog /
 * 审计 trace persist 落盘。模型调用全部用 mock Agent 模拟预设 lastText。
 */
class AiAuthReviewerTest {

    private WorkerConfig props;
    private AgentFactory agentFactory;
    private TaskStore taskStore;
    private TaskEntry task;

    @BeforeEach
    void setUp() {
        props = mock(WorkerConfig.class);
        WorkerConfig.Permissions perms = mock(WorkerConfig.Permissions.class);
        when(props.permissions()).thenReturn(perms);
        when(perms.reviewTimeoutMs()).thenReturn(10_000L);
        when(perms.reviewDenyOnError()).thenReturn(true);
        when(perms.reviewModel()).thenReturn("");

        agentFactory = mock(AgentFactory.class);
        taskStore = mock(TaskStore.class);
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
    private final java.util.concurrent.atomic.AtomicReference<String> currentPreset = new java.util.concurrent.atomic.AtomicReference<>();

    private TaskEntry newTask(String cfgId) {
        ModelConfig snap = new ModelConfig(cfgId, "openai-compat",
                "http://localhost:9999/v1", "task-model", null);
        return new TaskEntry("t-1", "任务", snap, "ws", "defaultworkspace", "main-agent", 10_000);
    }

    private AiAuthReviewer reviewer(String content) {
        lastTextHolder.set(content);
        currentPreset.set(content);
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
                .review(task, "c::del", "AI 请求删除工作区外文件");
        assertEquals(ReviewDecision.Verdict.ALLOW, d.verdict());
        assertFalse(d.fallback());
        assertEquals(0.9, d.confidence());
        assertEquals("run", d.scope());
        assertEquals("安全", d.reason());
        assertAuthTrace("ALLOW", true);
    }

    @Test
    void parsesDenyCaseInsensitive() {
        ReviewDecision d = reviewer("{\"decision\":\"deny\",\"confidence\":\"0.2\",\"reason\":\"危险\"}")
                .review(task, "c::rm", "AI 请求 rm -rf");
        assertEquals(ReviewDecision.Verdict.DENY, d.verdict());
        assertFalse(d.fallback());
        assertEquals(0.2, d.confidence());
        assertEquals("deny", d.scope());
        assertAuthTrace("DENY", true);
    }

    @Test
    void parsesEscalate() {
        ReviewDecision d = reviewer("{\"decision\":\"ESCALATE\",\"confidence\":0.5,\"reason\":\"不确定\"}")
                .review(task, "p::read::x", "AI 请求读取工作区外路径");
        assertEquals(ReviewDecision.Verdict.ESCALATE, d.verdict());
        assertFalse(d.fallback());
        assertEquals("deny", d.scope());
        assertAuthTrace("ESCALATE", true);
    }

    /** 容忍 markdown 代码围栏与前导/尾随空白。 */
    @Test
    void parsesJsonInsideMarkdownFence() {
        ReviewDecision d = reviewer("```json\n  {\"decision\":\"ALLOW\",\"confidence\":0.8,\"reason\":\"ok\"}  \n```")
                .review(task, "c::del", "AI 请求");
        assertEquals(ReviewDecision.Verdict.ALLOW, d.verdict());
    }

    // ---- 非 JSON / 缺字段 → 默认 DENY(fail-closed) ----

    @Test
    void nonJsonDefaultsToDeny() {
        ReviewDecision d = reviewer("不好意思我无法判断").review(task, "c::del", "AI 请求");
        assertEquals(ReviewDecision.Verdict.DENY, d.verdict());
        assertFalse(d.fallback());
        assertTrue(d.reason().contains("非 JSON"), d.reason());
        assertAuthTrace("DENY", true);
    }

    @Test
    void emptyResponseDefaultsToDeny() {
        ReviewDecision d = reviewer("").review(task, "c::del", "AI 请求");
        assertEquals(ReviewDecision.Verdict.DENY, d.verdict());
        assertTrue(d.reason().contains("空响应"), d.reason());
        assertAuthTrace("DENY", true);
    }

    @Test
    void missingDecisionFieldDefaultsToDeny() {
        ReviewDecision d = reviewer("{\"confidence\":0.9,\"reason\":\"无结论\"}")
                .review(task, "c::del", "AI 请求");
        assertEquals(ReviewDecision.Verdict.DENY, d.verdict());
        assertFalse(d.fallback());
        assertTrue(d.reason().contains("缺 decision"), d.reason());
    }

    @Test
    void illegalDecisionValueDefaultsToDeny() {
        ReviewDecision d = reviewer("{\"decision\":\"MAYBE\"}").review(task, "c::del", "AI 请求");
        assertEquals(ReviewDecision.Verdict.DENY, d.verdict());
        assertTrue(d.reason().contains("非法 decision"), d.reason());
    }

    // ---- 总预算超时:future.get 到点 → cancel + DENY/回退,审计 trace 附 reason=timeout ----

    @Test
    void totalBudgetTimeoutDefaultsToDeny() {
        when(props.permissions().reviewTimeoutMs()).thenReturn(100L);
        when(props.permissions().reviewDenyOnError()).thenReturn(true);
        long start = System.currentTimeMillis();
        ReviewDecision d = hangReviewer(5_000).review(task, "c::del", "AI 请求");
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
        ReviewDecision d = hangReviewer(5_000).review(task, "c::del", "AI 请求");
        assertEquals(ReviewDecision.Verdict.DENY, d.verdict());
        assertTrue(d.fallback(), "deny-on-error=false 应返回 fallback 标记(回退人工弹窗)");
        assertTrue(d.reason().contains("timeout"), d.reason());
        assertTrue(authTracePayload().path("reason").asString().contains("timeout"));
    }

    // ---- 异常回退 ----

    @Test
    void modelErrorDeniesByDefault() {
        when(props.permissions().reviewDenyOnError()).thenReturn(true);
        ReviewDecision d = failReviewer().review(task, "c::del", "AI 请求");
        assertEquals(ReviewDecision.Verdict.DENY, d.verdict());
        assertFalse(d.fallback());
        assertTrue(d.reason().contains("error"), d.reason());
        assertAuthTrace("DENY", true);
    }

    @Test
    void modelErrorFallsBackWhenDenyOnErrorFalse() {
        when(props.permissions().reviewDenyOnError()).thenReturn(false);
        ReviewDecision d = failReviewer().review(task, "c::del", "AI 请求");
        assertEquals(ReviewDecision.Verdict.DENY, d.verdict());
        assertTrue(d.fallback(), "deny-on-error=false 应回退人工(fallback 标记)");
        assertTrue(d.reason().contains("error"), d.reason());
    }

    // ---- 组件不注册任何工具 / 不新建 TaskEntry/EventLog ----

    @Test
    void doesNotCreateNewTaskEntryOrEventLog() throws Exception {
        reviewer("{\"decision\":\"ALLOW\"}").review(task, "c::del", "AI 请求");
        verify(taskStore, never()).track(any(), any(), any(), any());
        assertTrue(task.agents.isEmpty(), "审议不复用/新建子 agent 集合");
        assertNotNull(authTracePayload(), "原任务应有 auth.review trace");
        assertNull(authTraceEvent().ext(), "persist=true 的 trace 无 ext.persist=false 瞬态标记");
    }

    // ---- 辅助 ----

    private JsonNode authTracePayload() {
        return authTraceEvent().payload().path("metadata");
    }

    private EventRecord authTraceEvent() {
        for (int i = 0; i < task.log.size(); i++) {
            var r = task.log.readFrom(i, 1).get(0);
            if ("task.trace".equals(r.event())
                    && "auth.review".equals(r.payload().path("kind").asString())) {
                return r;
            }
        }
        throw new AssertionError("未找到 auth.review trace");
    }

    private void assertAuthTrace(String decision, boolean persist) {
        JsonNode payload = authTraceEvent().payload();
        assertEquals("AI 安全审议", payload.path("title").asString());
        assertTrue(payload.path("summary").asString().contains("审议结果：" + decision));
        assertEquals(decision, payload.path("metadata").path("decision").asString());
        assertEquals(task.taskId, payload.path("metadata").path("taskId").asString());
        assertTrue(payload.path("metadata").path("agentId").asString().startsWith("review"));
        assertTrue(payload.path("metadata").has("prompt"));
        assertTrue(payload.path("metadata").has("grantKey"));
        assertTrue(payload.path("metadata").has("scope"));
        assertTrue(payload.path("metadata").has("confidence"));
        assertTrue(payload.path("metadata").has("reason"));
        if (persist) {
            assertNull(authTraceEvent().ext(), "persist=true 应无瞬态标记");
        }
    }
}
