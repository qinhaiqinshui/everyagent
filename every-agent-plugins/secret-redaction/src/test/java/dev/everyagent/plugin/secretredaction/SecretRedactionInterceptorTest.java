package dev.everyagent.plugin.secretredaction;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.spi.ToolExecutionChain;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import dev.everyagent.plugin.api.util.SecretPatterns;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SecretRedactionInterceptor}：上行段掩码本轮工具输出，且只改本轮。
 */
class SecretRedactionInterceptorTest {

    private static final String KEY = "sk-ant-sid01-" + "Q".repeat(60) + "6280";
    private static final String CALL = "call_1";
    private static final String OLD_CALL = "call_0";

    private final SecretRedactionInterceptor interceptor = new SecretRedactionInterceptor();

    // ---- 用例 ----

    @Test
    void masksCurrentRoundToolOutput() throws Exception {
        ToolExecutionResult result = result(
                toolResponse(CALL, "powershell", "LangMode=ConstrainedLanguage\nkey=" + KEY));
        Captured captured = new Captured(result);

        ToolExecutionResult out = interceptor.invoke(ctx(List.of(toolCall(CALL)), captured), chain(result));

        assertNotSame(result, out, "有命中时必须返回新结果");
        String data = firstResponseData(out);
        assertFalse(data.contains(KEY), "明文必须消失: " + data);
        assertTrue(data.contains("[len=" + KEY.length() + "]"), "保留指纹: " + data);
        assertTrue(data.contains("LangMode=ConstrainedLanguage"), "无关内容不得被改动: " + data);
        assertEquals(1, captured.emitter.events.size(), "必须发一条 task.trace 审计");
        assertTrue(out.returnDirect(), "returnDirect 透传");
    }

    @Test
    void leavesCleanOutputUntouched() throws Exception {
        ToolExecutionResult result = result(toolResponse(CALL, "powershell", "hello 世界"));
        ToolExecutionResult out = interceptor.invoke(
                ctx(List.of(toolCall(CALL)), new Captured(result)), chain(result));
        assertSame(result, out, "无命中时原样返回同一实例(零开销路径)");
    }

    @Test
    void doesNotRescanHistoryRounds() throws Exception {
        // 历史轮在它那轮已被处理过,本轮不再重复扫描(避免每轮 O(全历史) 正则)
        ToolExecutionResult result = new ToolResponseMessageHolder(
                toolResponse(OLD_CALL, "powershell", KEY),
                toolResponse(CALL, "powershell", "ok")).result();
        ToolExecutionResult out = interceptor.invoke(
                ctx(List.of(toolCall(CALL)), new Captured(result)), chain(result));
        String oldData = responseData(out, OLD_CALL);
        assertTrue(oldData.contains(KEY), "非本轮 callId 不动(设计口径)");
    }

    @Test
    void isIdempotentAcrossRounds() throws Exception {
        Captured captured = new Captured(
                result(toolResponse(CALL, "powershell", "dump: " + KEY)));
        ToolExecutionResult first = interceptor.invoke(ctx(List.of(toolCall(CALL)), captured),
                chain(captured.result));
        ToolExecutionResult second = interceptor.invoke(ctx(List.of(toolCall(CALL)), captured),
                chain(first));
        assertEquals(firstData(first), firstData(second), "二次经过链必须文本不变");
        assertSame(first, second, "已掩码文本不再触发改写");
    }

    @Test
    void auditEventCarriesNoSecretMaterial() throws Exception {
        Captured captured = new Captured(result(toolResponse(CALL, "powershell", KEY)));
        interceptor.invoke(ctx(List.of(toolCall(CALL)), captured), chain(captured.result));
        EmitEvent trace = captured.emitter.events.get(0);
        String rendered = String.join("|", String.valueOf(trace.title()),
                String.valueOf(trace.summary()), String.valueOf(trace.content()),
                String.valueOf(trace.data()));
        assertFalse(rendered.contains(KEY), "审计事件不得带出凭据: " + rendered);
        assertFalse(rendered.contains("sk-ant-sid01-" + "Q".repeat(20)), "也不得带出可猜前缀片段");
        assertInstanceOf(Map.class, trace.data());
        assertEquals(List.of("powershell"), ((Map<?, ?>) trace.data()).get("tools"));
    }

    @Test
    void nullSafety() throws Exception {
        assertSame(null, interceptor.invoke(ctx(List.of(), new Captured(null)), chain(null)));
        ToolExecutionResult empty = ToolExecutionResult.builder().conversationHistory(List.of()).build();
        assertSame(empty, interceptor.invoke(ctx(List.of(toolCall(CALL)), new Captured(empty)), chain(empty)));
    }

    // ---- 辅助 ----

    private record Captured(RecordingEmitter emitter, ToolExecutionResult result) {
        Captured(ToolExecutionResult result) {
            this(new RecordingEmitter(), result);
        }
    }

    private static final class RecordingEmitter implements EventEmitter {
        final List<EmitEvent> events = new ArrayList<>();

        @Override
        public long emit(EmitEvent event) {
            events.add(event);
            return event.id();
        }
    }

    /** 结果容器：让测试能用两条 ToolResponseMessage 拼历史。 */
    private record ToolResponseMessageHolder(Message... messages) {
        ToolExecutionResult result() {
            return ToolExecutionResult.builder()
                    .conversationHistory(List.of(messages)).returnDirect(true).build();
        }
    }

    private static ToolExecutionResult result(Message... messages) {
        return ToolExecutionResult.builder()
                .conversationHistory(new ArrayList<>(List.of(messages)))
                .returnDirect(true)
                .build();
    }

    private static ToolResponseMessage toolResponse(String id, String name, String data) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponse(id, name, data))).build();
    }

    private static AssistantMessage.ToolCall toolCall(String id) {
        return new AssistantMessage.ToolCall(id, "function", "powershell", "{}");
    }

    private static ToolExecutionChain chain(ToolExecutionResult result) {
        return c -> result;
    }

    private static String firstResponseData(ToolExecutionResult r) {
        return responseData(r, CALL);
    }

    private static String firstData(ToolExecutionResult r) {
        return firstResponseData(r);
    }

    private static String responseData(ToolExecutionResult r, String callId) {
        for (Message m : r.conversationHistory()) {
            if (m instanceof ToolResponseMessage trm) {
                for (ToolResponse resp : trm.getResponses()) {
                    if (callId.equals(resp.id())) {
                        return resp.responseData();
                    }
                }
            }
        }
        throw new AssertionError("callId 未找到: " + callId);
    }

    /** 最小可用的执行上下文：拦截器只读 toolCalls / emitter / subjectId。 */
    private static ToolExecutionContext ctx(List<AssistantMessage.ToolCall> toolCalls,
            Captured captured) {
        return new ToolExecutionContext() {
            @Override public Prompt prompt() { return null; }
            @Override public ChatResponse chatResponse() { return null; }
            @Override public List<AssistantMessage.ToolCall> toolCalls() { return toolCalls; }
            @Override public String subjectId() { return "t_test"; }
            @Override public String workspaceRoot() { return "."; }
            @Override public String workspaceId() { return "w_test"; }
            @Override public ModelConfig snapshot() { return null; }
            @Override public EventEmitter emitter() { return captured.emitter(); }
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
        assertTrue(SecretPatterns.hasSecret(KEY));
    }
}
