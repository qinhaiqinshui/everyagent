package dev.everyagent.plugin.aireview;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.agent.Agent;
import dev.everyagent.plugin.api.agent.AgentBuilder;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import dev.everyagent.plugin.api.event.EventRecord;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AiAuthReviewer 单测:审议三态解析 / <b>解析失败全集 → 重试一次 → 仍失败 ESCALATE</b>(不再 DENY)/
 * review-model 缺省用任务 configId / 总预算超时(外层 future.get 到点 → cancel +
 * DENY 或回退,审计 trace 附 reason=timeout)/ 组件不注册任何工具 / 不新建任务实体 /
 * 审计 trace persist 落盘、以审议 agent 自身身份发射。模型调用全部用 mock Agent 模拟预设输出。
 *
 * <p>约束(§14.9):插件对 worker 任何 scope 零依赖,测试桩在本类内自建——
 * {@link RecordingExecContext} 复刻 worker TaskEvents 的 EmitEvent→EventRecord
 * wire 映射(payload{title,summary,content,status,data}+ext{persist,operate}),
 * 不引用 worker 的 TaskEntry/TaskStore。
 */
class AiAuthReviewerTest {

    private WorkerConfig props;
    private AgentFactory agentFactory;
    private RecordingExecContext task;

    @BeforeEach
    void setUp() {
        props = mock(WorkerConfig.class);
        WorkerConfig.Permissions perms = mock(WorkerConfig.Permissions.class);
        when(props.permissions()).thenReturn(perms);
        when(perms.reviewTimeoutMs()).thenReturn(10_000L);
        when(perms.reviewDenyOnError()).thenReturn(true);
        when(perms.reviewModel()).thenReturn("");

        // 绑定工厂 mock:经 ctx.agentFactory() 槽位提供给 reviewer(§8.3)
        agentFactory = mock(AgentFactory.class);
        task = newTask("task-cfg");
        setupAgentFactoryMock();
    }

    /** 通用 AgentFactory mock:create → AgentBuilder(plugin-api) → build() 返回 mock Agent。 */
    @SuppressWarnings("unchecked")
    private void setupAgentFactoryMock() {
        AgentBuilder mockBuild = mock(AgentBuilder.class);
        // create(agentId[, configId]) 记下本次 agentId:worker 的 AgentBuilder.build() 会
        // 自动把实体注册进 exec.agents()(§7.20.1),测试桩必须复刻这个行为。
        when(agentFactory.create(anyString(), any())).thenAnswer(inv -> {
            lastCreatedAgentId.set(inv.getArgument(0));
            return mockBuild;
        });
        when(mockBuild.title(anyString())).thenReturn(mockBuild);
        when(mockBuild.creator(anyString())).thenReturn(mockBuild);
        when(mockBuild.tools(any(), any())).thenReturn(mockBuild);
        // 复刻 worker AgentBuilder 的会话语义:systemPrompt/userInput 在 build() 时进会话内存。
        // 桩若不落会话,「复用续跑前 assistant 已在场」这条不变量就测不出来(审议会话跨请求
        // 复用,assistant 回写由 AiAuthReviewer.doReview 负责,不是 build() 的活)。
        when(mockBuild.systemPrompt(anyString())).thenAnswer(inv -> {
            pendingSystemPrompt.set(inv.getArgument(0));
            return mockBuild;
        });
        when(mockBuild.userInput(anyString())).thenAnswer(inv -> {
            pendingUserInput.set(inv.getArgument(0));
            return mockBuild;
        });
        when(mockBuild.options(any())).thenReturn(mockBuild);
        when(mockBuild.build()).thenAnswer(inv -> {
            // 返回一个 mock Agent,由 reviewer(ChatModel model) 设置 lastText
            String id = lastCreatedAgentId.get() == null ? "review-agent" : lastCreatedAgentId.get();
            Agent agent = mock(Agent.class);
            when(agent.agentId()).thenReturn(id);
            when(agent.title()).thenReturn("AI 安全审议");
            when(agent.creator()).thenReturn("ai-review");
            when(agent.lastText()).thenAnswer(ltInv -> lastTextHolder.get());
            List<org.springframework.ai.chat.messages.Message> conversation = new ArrayList<>();
            if (pendingSystemPrompt.get() != null) {
                conversation.add(new org.springframework.ai.chat.messages.SystemMessage(
                        pendingSystemPrompt.get()));
            }
            if (pendingUserInput.get() != null) {
                conversation.add(new org.springframework.ai.chat.messages.UserMessage(
                        pendingUserInput.get()));
            }
            when(agent.conversation()).thenReturn(conversation);
            org.mockito.Mockito.doAnswer(runInv -> {
                // 模拟 run:按脚本逐轮推进本轮输出(scriptedTexts 非空时逐轮取;空则沿用 lastTextHolder),
                // 再执行 runAction(挂起/抛错桩用)——复刻「run() 产出 lastText()」的真实语义。
                java.util.List<String> script = scriptedTexts.get();
                if (!script.isEmpty()) {
                    int i = runIndex.getAndIncrement();
                    lastTextHolder.set(script.get(Math.min(i, script.size() - 1)));
                }
                Runnable r = runAction.get();
                if (r != null) r.run();
                return null;
            }).when(agent).run();
            // 复刻 worker AgentEntity 的 agent 层包装 emitter:emit 时把 EmitEvent.agentId 填为本 agent 的
            // agentId(review-<subjectId>)——emitAuthTrace 以「审议 agent 自身身份」发射结果 trace。
            when(agent.emitter()).thenAnswer(emInv -> (EventEmitter) e -> {
                String filled = (e.agentId() == null || e.agentId().isEmpty()) ? id : e.agentId();
                task.records.add(toRecord(e, filled));
                return e.id();
            });
            // build() 自动注册(与 worker AgentBuilder 同语义)
            task.agents().put(id, agent);
            return agent;
        });
    }

    private final java.util.concurrent.atomic.AtomicReference<String> lastCreatedAgentId =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** 桩内暂存 build() 的 system/user 入参(复刻 worker AgentBuilder 的会话装配语义)。 */
    private final java.util.concurrent.atomic.AtomicReference<String> pendingSystemPrompt =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<String> pendingUserInput =
            new java.util.concurrent.atomic.AtomicReference<>();

    private final java.util.concurrent.atomic.AtomicReference<String> lastTextHolder = new java.util.concurrent.atomic.AtomicReference<>("");
    private final java.util.concurrent.atomic.AtomicReference<Runnable> runAction = new java.util.concurrent.atomic.AtomicReference<>();

    /** 逐轮输出脚本(非空 → 每次 run() 逐轮取,超出重复末条);重试用例:首轮失败、次轮成功。 */
    private final AtomicReference<List<String>> scriptedTexts = new AtomicReference<>(List.of());
    /** 脚本推进下标(每次 run() 递增)。 */
    private final AtomicInteger runIndex = new AtomicInteger(0);

    private RecordingExecContext newTask(String cfgId) {
        ModelConfig snap = new ModelConfig(cfgId, "openai-compat",
                "http://localhost:9999/v1", "task-model", null);
        return new RecordingExecContext("t-1", snap, agentFactory);
    }

    /** 组装授权请求桩(agentId 不参与审议,置 null)。 */
    private AuthorizationHandler.AuthorizationRequest req(String grantKey, String prompt) {
        return new AuthorizationHandler.AuthorizationRequest(task, null, grantKey, prompt);
    }

    private AiAuthReviewer reviewer(String content) {
        scriptedTexts.set(List.of());
        runIndex.set(0);
        lastTextHolder.set(content);
        runAction.set(null);
        return new AiAuthReviewer(props);
    }

    /** 逐轮输出脚本:第一次 run() 用 texts[0]、第二次用 texts[1]…(重试用例)。 */
    private AiAuthReviewer sequenceReviewer(String... texts) {
        scriptedTexts.set(List.of(texts));
        runIndex.set(0);
        lastTextHolder.set(texts[0]);
        runAction.set(null);
        return new AiAuthReviewer(props);
    }

    private AiAuthReviewer hangReviewer(long sleepMs) {
        scriptedTexts.set(List.of());
        runIndex.set(0);
        lastTextHolder.set("{\"decision\":\"ALLOW\"}");
        runAction.set(() -> {
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("审议调用被中断", e);
            }
        });
        return new AiAuthReviewer(props);
    }

    private AiAuthReviewer failReviewer() {
        scriptedTexts.set(List.of());
        runIndex.set(0);
        lastTextHolder.set("");
        runAction.set(() -> { throw new RuntimeException("模拟审议模型故障"); });
        return new AiAuthReviewer(props);
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

    // ---- 解析失败全集(空响应/非 JSON/多决策对象/缺或非法 decision)→ 重试一次 → 仍失败 ESCALATE ----

    @Test
    void nonJsonEscalatesAfterRetry() {
        ReviewDecision d = reviewer("不好意思我无法判断").review(req("c::del", "AI 请求"));
        assertEquals(ReviewDecision.Verdict.ESCALATE, d.verdict());
        assertFalse(d.fallback());
        assertTrue(d.reason().contains("已重试"), d.reason());
        assertAuthTrace("ESCALATE", null);
    }

    @Test
    void emptyResponseEscalatesAfterRetry() {
        ReviewDecision d = reviewer("").review(req("c::del", "AI 请求"));
        assertEquals(ReviewDecision.Verdict.ESCALATE, d.verdict());
        assertTrue(d.reason().contains("已重试"), d.reason());
        assertAuthTrace("ESCALATE", null);
    }

    @Test
    void missingDecisionFieldEscalatesAfterRetry() {
        ReviewDecision d = reviewer("{\"confidence\":0.9,\"reason\":\"无结论\"}").review(req("c::del", "AI 请求"));
        assertEquals(ReviewDecision.Verdict.ESCALATE, d.verdict());
        assertFalse(d.fallback());
        assertTrue(d.reason().contains("已重试"), d.reason());
    }

    @Test
    void illegalDecisionValueEscalatesAfterRetry() {
        ReviewDecision d = reviewer("{\"decision\":\"MAYBE\"}").review(req("c::del", "AI 请求"));
        assertEquals(ReviewDecision.Verdict.ESCALATE, d.verdict());
        assertTrue(d.reason().contains("已重试"), d.reason());
    }

    // ---- 解析失败 → 补回 assistant + 追加「纠正」user → 重试一次:成功则按本轮结论(ALLOW) ----

    @Test
    void parseFailureThenRetrySucceedsAllows() {
        AiAuthReviewer r = sequenceReviewer("我无法判断",
                "{\"decision\":\"ALLOW\",\"confidence\":0.9,\"reason\":\"安全\"}");
        ReviewDecision d = r.review(req("c::del", "AI 请求删除工作区外文件"));
        assertEquals(ReviewDecision.Verdict.ALLOW, d.verdict());
        assertFalse(d.fallback());
        assertEquals(0.9, d.confidence());
        assertEquals("安全", d.reason());
        assertAuthTrace("ALLOW", "安全");
        // 重试后的会话严格 user→assistant→user(纠正)→assistant,共 5 条。
        assertRetryConversation("我无法判断",
                "{\"decision\":\"ALLOW\",\"confidence\":0.9,\"reason\":\"安全\"}");
    }

    // ---- 两次都解析失败 → ESCALATE(绝不 DENY) ----

    @Test
    void parseFailureTwiceEscalatesNotDeny() {
        AiAuthReviewer r = sequenceReviewer("非JSON-A", "非JSON-B");
        ReviewDecision d = r.review(req("c::del", "AI 请求"));
        assertEquals(ReviewDecision.Verdict.ESCALATE, d.verdict());
        assertNotEquals(ReviewDecision.Verdict.DENY, d.verdict(), "两次解析失败绝不 DENY");
        assertFalse(d.fallback());
        assertTrue(d.reason().contains("已重试"), d.reason());
        assertAuthTrace("ESCALATE", null);
        assertRetryConversation("非JSON-A", "非JSON-B");
    }

    /** 断言重试后会话为 [system, user1, assistant1, user2(纠正), assistant2](严格交替,§7.9)。 */
    private void assertRetryConversation(String firstVerdict, String secondVerdict) {
        Agent reviewAgent = (Agent) task.agents().get("review-t-1");
        assertNotNull(reviewAgent, "重试路径也应已创建审议 agent");
        List<org.springframework.ai.chat.messages.Message> conv = reviewAgent.conversation();
        assertEquals(5, conv.size(), "会话应为 [system, user1, assistant1, user2(纠正), assistant2]");
        assertInstanceOf(org.springframework.ai.chat.messages.SystemMessage.class, conv.get(0));
        assertInstanceOf(org.springframework.ai.chat.messages.UserMessage.class, conv.get(1));
        assertEquals(firstVerdict, assertInstanceOf(AssistantMessage.class, conv.get(2)).getText(),
                "首轮输出应原样补回为 assistant 轮");
        org.springframework.ai.chat.messages.UserMessage correction =
                assertInstanceOf(org.springframework.ai.chat.messages.UserMessage.class, conv.get(3));
        assertTrue(correction.getText().contains("合法 JSON"), "纠正轮应要求模型只输出合法 JSON");
        assertEquals(secondVerdict, assertInstanceOf(AssistantMessage.class, conv.get(4)).getText(),
                "重试轮输出同样补回为 assistant 轮");
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
    void registersFixedReviewAgentInContextAgents() {
        reviewer("{\"decision\":\"ALLOW\"}").review(req("c::del", "AI 请求"));
        // §8.3:审议 agent 以固定 agentId 注册进 ctx.agents()(不新建任务实体/事件日志)
        assertTrue(task.agents().containsKey("review-t-1"), "审议 agent 应注册为 review-<subjectId>");
        assertNotNull(authTracePayload(), "原任务应有 auth.review trace");
        assertTrue(authTraceEvent().ext().path("persist").asBoolean(),
                "persist=true 落盘 trace 的 ext.persist 应为 true(非瞬态)");
    }

    /** 结果 trace 以「审议 agent 自身身份」发射(§7.9):去除了「主体级 emitter + 手写子 agent id」的混用。 */
    @Test
    void reviewTraceEmittedWithReviewAgentIdentity() {
        reviewer("{\"decision\":\"ALLOW\"}").review(req("c::del", "AI 请求"));
        assertEquals("review-t-1", authTraceEvent().agentId(),
                "auth.review trace 应挂审议 agent 自身 agentId(包装层自填,插件传 null)");
    }

    // ---- 固定 per-task agentId 复用会话(§8.3):两次 review 同一 ctx,第二次命中注册表续跑 ----

    @Test
    void secondReviewReusesRegisteredAgentNotRecreating() {
        AiAuthReviewer r = reviewer("{\"decision\":\"ALLOW\"}");
        r.review(req("c::del", "第一次授权请求"));
        org.mockito.Mockito.verify(agentFactory).create(org.mockito.ArgumentMatchers.eq("review-t-1"), org.mockito.ArgumentMatchers.isNull());
        Agent first = (Agent) task.agents().get("review-t-1");
        assertNotNull(first, "首次审议应创建并注册 review-t-1");

        lastTextHolder.set("{\"decision\":\"DENY\",\"reason\":\"危险\"}");
        r.review(req("c::rm", "第二次授权请求"));

        // 第二次:命中 agents().get → resetForRerun + conversation().add 续跑,不再新建
        org.mockito.Mockito.verify(agentFactory, org.mockito.Mockito.times(1))
                .create(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(first, org.mockito.Mockito.times(1)).resetForRerun();
        // 会话必须 user/assistant 严格交替:[system, user1, assistant1, user2, assistant2]。
        // 缺 assistant 回写时审议员会看到两条连续未答复的 user,把历史请求一并作答
        // (多对象输出 → 解析失败 fail-closed 误拒 / 旧结论被当本轮结论用)。
        List<org.springframework.ai.chat.messages.Message> conv = first.conversation();
        assertEquals(5, conv.size(), "会话应为 [system, user1, assistant1, user2, assistant2]");
        assertInstanceOf(org.springframework.ai.chat.messages.SystemMessage.class, conv.get(0));
        assertInstanceOf(org.springframework.ai.chat.messages.UserMessage.class, conv.get(1));
        AssistantMessage firstVerdict = assertInstanceOf(AssistantMessage.class, conv.get(2));
        assertEquals("{\"decision\":\"ALLOW\"}", firstVerdict.getText(),
                "第一轮审议结论应原样回写为 assistant 轮(审议员下一轮才看得见既往决策)");
        assertInstanceOf(org.springframework.ai.chat.messages.UserMessage.class, conv.get(3));
        AssistantMessage secondVerdict = assertInstanceOf(AssistantMessage.class, conv.get(4));
        assertEquals("{\"decision\":\"DENY\",\"reason\":\"危险\"}", secondVerdict.getText(),
                "第二轮审议结论同样回写");
        assertSame(first, task.agents().get("review-t-1"), "注册表应仍指向同一审议 agent 实例");
    }

    /** 审议调用失败(异常)路径同样回写占位 assistant 轮,不破坏 user/assistant 交替。 */
    @Test
    void failedReviewStillAppendsPlaceholderAssistantTurn() {
        when(props.permissions().reviewDenyOnError()).thenReturn(true);
        failReviewer().review(req("c::del", "AI 请求"));

        Agent reviewAgent = (Agent) task.agents().get("review-t-1");
        assertNotNull(reviewAgent, "异常路径也应已创建审议 agent");
        List<org.springframework.ai.chat.messages.Message> conv = reviewAgent.conversation();
        assertEquals(3, conv.size(), "会话应为 [system, user1, assistant占位]");
        AssistantMessage placeholder = assertInstanceOf(AssistantMessage.class, conv.get(2));
        assertEquals("(本轮审议未产出结论)", placeholder.getText(),
                "空结论路径回写占位轮,防下一轮审议员再看到连续未答复的 user");
    }

    // ---- 多决策对象/对象数组(模型把历史请求一并作答)→ 解析失败 → 重试 → 仍失败 ESCALATE,绝不取首段当本轮结论 ----

    @Test
    void multipleDecisionObjectsEscalatesAfterRetry() {
        ReviewDecision d = reviewer("""
                {"decision":"ALLOW","reason":"读取 win.ini 安全"} {"decision":"ALLOW","reason":"写入 Temp 安全"}
                """).review(req("p::write::C:\\\\Users\\\\haigui\\\\AppData\\\\Local\\\\Temp",
                "AI 请求写入工作区外路径"));
        assertEquals(ReviewDecision.Verdict.ESCALATE, d.verdict());
        assertFalse(d.fallback());
        assertTrue(d.reason().contains("已重试"), d.reason());
    }

    @Test
    void decisionArrayInsideFenceEscalatesAfterRetry() {
        ReviewDecision d = reviewer("""
                ```json
                [
                  {"decision":"ALLOW","reason":"a"},
                  {"decision":"ALLOW","reason":"b"}
                ]
                ```
                """).review(req("p::read::C:\\\\Windows", "AI 请求读取工作区外路径"));
        assertEquals(ReviewDecision.Verdict.ESCALATE, d.verdict());
        assertFalse(d.fallback());
        assertTrue(d.reason().contains("已重试"), d.reason());
    }

    /** 单对象 + 无花括号的尾随说明:保留原有宽容度(不因尾随散文误拒)。 */
    @Test
    void trailingProseKeepsSingleDecisionAllowed() {
        ReviewDecision d = reviewer("{\"decision\":\"ALLOW\",\"reason\":\"ok\"} 以上为唯一结论。")
                .review(req("c::del", "AI 请求"));
        assertEquals(ReviewDecision.Verdict.ALLOW, d.verdict());
    }

    /** reason 文案内含花括号不误判轮次边界(字符串感知的花括号配对计数)。 */
    @Test
    void bracesInsideReasonStillParses() {
        ReviewDecision d = reviewer("{\"decision\":\"ALLOW\",\"reason\":\"模板 ${x} 与 } 无害\"}")
                .review(req("c::del", "AI 请求"));
        assertEquals(ReviewDecision.Verdict.ALLOW, d.verdict());
        assertEquals("模板 ${x} 与 } 无害", d.reason());
    }

    // ---- 辅助:读取桩内记录的 auth.review trace ----

    private JsonNode authTracePayload() {
        return authTraceEvent().payload().path("data");
    }

    private EventRecord authTraceEvent() {
        for (EventRecord r : task.records) {
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
        assertEquals(task.subjectId(), authTracePayload().path("taskId").asString());
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
     * EmitEvent → EventRecord 的 wire 口径复刻(event=kind,
     * payload{title,summary,content,status,data},ext{persist,operate});agentId 由调用方给定
     * (复刻 TaskEvents / AgentEntity 的 agentId 兜底填充语义)。
     */
    private static EventRecord toRecord(EmitEvent e, String agentId) {
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
        return new EventRecord(e.id(), System.currentTimeMillis(), e.kind(), agentId, payload, ext);
    }

    /**
     * ExecContext 记录桩(原借 worker TaskEntry + TaskEvents):
     * emitter() 发射的 EmitEvent 按 wire 口径复刻为 EventRecord(event=kind,
     * payload{title,summary,content,status,data},ext{persist,operate}),
     * records 只读回放同一列表,供测试断言审计 trace。
     */
    private static final class RecordingExecContext implements ExecContext {
        private final String subjectId;
        private final ModelConfig snapshot;
        // agents 注册表用并发 Map:超时用例里 executor 线程注册审议 agent、主线程读，
        // 复用真实并发语义(AgentBuilder.build() 注册 → emitAuthTrace 回退判定)。
        private final Map<String, AgentContext> agentsMap = new ConcurrentHashMap<>();
        private final List<EventRecord> records = new CopyOnWriteArrayList<>();

        private final AgentFactory agentFactory;

        RecordingExecContext(String subjectId, ModelConfig snapshot, AgentFactory agentFactory) {
            this.subjectId = subjectId;
            this.snapshot = snapshot;
            this.agentFactory = agentFactory;
        }

        private final EventEmitter emitter = e -> {
            records.add(toRecord(e, e.agentId()));
            return e.id();
        };

        @Override public String subjectId() { return subjectId; }
        @Override public String workspaceRoot() { return "ws"; }
        @Override public String workspaceId() { return "defaultworkspace"; }
        @Override public ModelConfig snapshot() { return snapshot; }
        @Override public EventEmitter emitter() { return emitter; }
        @Override public AgentFactory agentFactory() { return agentFactory; }
        @Override public Map<String, Object> metadata() { return new HashMap<>(); }
        @Override public Path dataDir() { return Path.of("workspaces", "defaultworkspace", "tasks", subjectId); }
        @Override public boolean terminal() { return false; }
        @Override public dev.everyagent.plugin.api.interaction.InteractionService interaction() { return null; }
        @Override public Map<String, AgentContext> agents() { return agentsMap; }
    }
}
