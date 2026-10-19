package dev.everyagent.plugin.secretredaction;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SecretRedactionInterceptor}:per-call 上行段掩码当前调用的工具输出串。
 */
class SecretRedactionInterceptorTest {

    private static final String KEY = "sk-ant-sid01-" + "Q".repeat(60) + "6280";
    private static final String CALL = "call_1";

    private final SecretRedactionInterceptor interceptor = new SecretRedactionInterceptor();

    // ---- 用例 ----

    @Test
    void masksCurrentCallOutput() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();
        String out = interceptor.invoke(
                ctx(CALL, "powershell", emitter),
                c -> "LangMode=ConstrainedLanguage\nkey=" + KEY);

        assertNotSame("LangMode=ConstrainedLanguage\nkey=" + KEY, out, "有命中时必须返回掩码串");
        assertFalse(out.contains(KEY), "明文必须消失: " + out);
        assertTrue(out.contains("[len=" + KEY.length() + "]"), "保留指纹: " + out);
        assertTrue(out.contains("LangMode=ConstrainedLanguage"), "无关内容不得被改动: " + out);
        assertEquals(1, emitter.events.size(), "必须发一条 task.trace 审计");
    }

    @Test
    void leavesCleanOutputUntouched() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();
        String out = interceptor.invoke(
                ctx(CALL, "powershell", emitter), c -> "hello 世界");
        assertEquals("hello 世界", out, "无命中时返回掩码函数的原串(redact 同实例)");
        assertTrue(emitter.events.isEmpty(), "无命中不发审计事件");
    }

    @Test
    void nullAndEmptySafety() throws Exception {
        assertNull(interceptor.invoke(ctx(CALL, "powershell", null), c -> null));
        assertEquals("", interceptor.invoke(ctx(CALL, "powershell", null), c -> ""));
    }

    @Test
    void auditEventCarriesNoSecretMaterial() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();
        interceptor.invoke(ctx(CALL, "powershell", emitter), c -> "dump: " + KEY);
        EmitEvent trace = emitter.events.get(0);
        String rendered = String.join("|", String.valueOf(trace.title()),
                String.valueOf(trace.summary()), String.valueOf(trace.content()),
                String.valueOf(trace.data()));
        assertFalse(rendered.contains(KEY), "审计事件不得带出凭据: " + rendered);
        assertFalse(rendered.contains("sk-ant-sid01-" + "Q".repeat(20)), "也不得带出可猜前缀片段");
        tools.jackson.databind.node.ObjectNode data =
                assertInstanceOf(tools.jackson.databind.node.ObjectNode.class, trace.data());
        assertEquals(List.of("powershell"), List.of(data.path("tools").get(0).asText()),
                "审计 data.tools 只含当前调用工具名");
    }

    @Test
    void synthesizedAnswersAreAlsoRedactedByPosition() throws Exception {
        // 链序契约(§7.14.3):本拦截器 order=900 在发射节点(500)内侧——合成串/真实串
        // 都先经本节点掩码再被发射;本用例锁定「本节点只改写 next 返回的串、不改别的」。
        RecordingEmitter emitter = new RecordingEmitter();
        String out = interceptor.invoke(ctx(CALL, "ask_user", emitter),
                c -> "题干：sk-ant-sid01-" + "Q".repeat(60) + "6280");
        assertFalse(out.contains("sk-ant-sid01-"), "合成答案(unattended 产出)照样被掩码: " + out);
    }

    // ---- 辅助 ----

    private static final class RecordingEmitter implements EventEmitter {
        final List<EmitEvent> events = new ArrayList<>();

        @Override
        public long emit(EmitEvent event) {
            events.add(event);
            return event.id();
        }
    }

    /** 最小 per-call ctx:拦截器只读 toolCall/subjectId/emitter。 */
    private static ToolExecutionContext ctx(String callId, String toolName, EventEmitter emitter) {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(callId, "function", toolName, "{}");
        return new ToolExecutionContext() {
            @Override public Prompt prompt() { return null; }
            @Override public ChatResponse chatResponse() { return null; }
            @Override public List<AssistantMessage.ToolCall> toolCalls() { return List.of(call); }
            @Override public AssistantMessage.ToolCall toolCall() { return call; }
            @Override public ToolCallback toolCallback() { return null; }
            @Override public String arguments() { return "{}"; }
            @Override public void arguments(String arguments) { }
            @Override public void synthesize(String result) { }
            @Override public String synthesizedResult() { return null; }
            @Override public long startedAt() { return 0; }
            @Override public void startedAt(long epochMs) { }
            @Override public dev.everyagent.plugin.api.spi.ToolOutcomeLog toolOutcomes() { return null; }
            @Override public String subjectId() { return "t_test"; }
            @Override public String workspaceRoot() { return "."; }
            @Override public String workspaceId() { return "w_test"; }
            @Override public ModelConfig snapshot() { return null; }
            @Override public EventEmitter emitter() { return emitter; }
            @Override public AgentFactory agentFactory() { return null; }
            @Override public Map<String, Object> metadata() { return Map.of(); }
            @Override public Path dataDir() { return Path.of("."); }
            @Override public boolean terminal() { return false; }
            @Override public InteractionService interaction() { return null; }
            @Override public Map<String, AgentContext> agents() { return Map.of(); }
        };
    }

    static {
        // 保证测试自身对规则源的期望与实现同源(避免两处常量漂移)
        assertTrue(SecretRedactor.hasSecret(KEY));
    }
}
