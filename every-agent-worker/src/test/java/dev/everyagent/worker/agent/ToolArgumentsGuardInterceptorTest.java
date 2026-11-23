package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.api.spi.ToolExecutionChain;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolArgumentsGuardInterceptor} 单测:缺必填 → 合成失败串 + proceed(哨兵置位,
 * 链尾短路真实调用);齐全 → 原样放行。
 */
class ToolArgumentsGuardInterceptorTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"path\":{\"type\":\"string\"},\"oldcontent\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},"
            + "\"required\":[\"path\",\"oldcontent\",\"content\"]}";

    /** 复现 t_jrm6 事故形态:模型把 content 写成 newcontent。 */
    private static final String WRONG_ARGS =
            "{\"newcontent\":\"新内容\",\"oldcontent\":\"旧内容\",\"path\":\"docs/a.md\"}";

    private static ToolCallback updateFile() {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("update_file").description("test").inputSchema(SCHEMA).build();
            }
            @Override public ToolMetadata getToolMetadata() { return ToolMetadata.builder().build(); }
            @Override public String call(String toolInput) { throw new UnsupportedOperationException("护栏外不得直呼"); }
        };
    }

    /** 最小 per-call ctx:只记合成哨兵。 */
    private static final class Ctx implements ToolExecutionContext {
        final String args;
        final AtomicReference<String> synthesized = new AtomicReference<>();
        Ctx(String args) { this.args = args; }
        @Override public Prompt prompt() { return null; }
        @Override public ChatResponse chatResponse() { return null; }
        @Override public List<AssistantMessage.ToolCall> toolCalls() { return List.of(); }
        @Override public AssistantMessage.ToolCall toolCall() {
            return new AssistantMessage.ToolCall("call_1", "function", "update_file", args);
        }
        @Override public ToolCallback toolCallback() { return updateFile(); }
        @Override public String arguments() { return args; }
        @Override public void arguments(String arguments) { }
        @Override public void synthesize(String result) { synthesized.set(result); }
        @Override public String synthesizedResult() { return synthesized.get(); }
        @Override public long startedAt() { return 0; }
        @Override public void startedAt(long epochMs) { }
        @Override public dev.everyagent.plugin.api.spi.ToolOutcomeLog toolOutcomes() { return null; }
        @Override public String subjectId() { return "t1"; }
        @Override public String workspaceRoot() { return "C:\\ws"; }
        @Override public String workspaceId() { return "ws1"; }
        @Override public dev.everyagent.plugin.api.model.ModelConfig snapshot() { return null; }
        @Override public dev.everyagent.plugin.api.model.EventEmitter emitter() { return null; }
        @Override public dev.everyagent.plugin.api.agent.AgentFactory agentFactory() { return null; }
        @Override public Map<String, Object> metadata() { return Map.of(); }
        @Override public Path dataDir() { return null; }
        @Override public boolean terminal() { return false; }
        @Override public dev.everyagent.plugin.api.interaction.InteractionService interaction() { return null; }
        @Override public Map<String, dev.everyagent.plugin.api.agent.AgentContext> agents() { return Map.of(); }
    }

    @Test
    void missingRequiredSynthesizesFailureAndSkipsRealCall() throws Exception {
        Ctx ctx = new Ctx(WRONG_ARGS);
        AtomicBoolean realCalled = new AtomicBoolean();
        ToolExecutionChain tail = c -> {
            // 链尾语义(PerCallToolCallingManager):哨兵置位即短路
            if (c.synthesizedResult() != null) return c.synthesizedResult();
            realCalled.set(true);
            return "已更新到：docs/a.md";
        };
        String out = new ToolArgumentsGuardInterceptor().invoke(ctx, tail);

        assertTrue(out.startsWith(ExecResults.TOOL_FAILURE_PREFIX), "失败前缀 → tool-events 标 error: " + out);
        assertTrue(out.contains("缺少必填参数 content"), out);
        assertTrue(out.contains("本次收到的参数名是 newcontent"), "给出疑似改名提示: " + out);
        assertTrue(out.contains("update_file"), out);
        assertTrue(realCalled.get() == false, "哨兵置位,链尾不得进入真实调用");
    }

    @Test
    void completeArgumentsPassThroughUntouched() throws Exception {
        Ctx ctx = new Ctx("{\"path\":\"docs/a.md\",\"oldcontent\":\"旧\",\"content\":\"新\"}");
        String out = new ToolArgumentsGuardInterceptor().invoke(ctx, c -> "已更新到：docs/a.md");
        assertEquals("已更新到：docs/a.md", out);
        assertNull(ctx.synthesizedResult(), "齐全不置哨兵");
    }

    @Test
    void orderPlacedBeforeRealExecutionAndOutsideEventEmitter() {
        ToolArgumentsGuardInterceptor guard = new ToolArgumentsGuardInterceptor();
        assertEquals(50f, guard.order(), "rg-hint(0) 内侧、unattended(100)/tool-events(500) 外侧");
        assertEquals("tool-args-guard", guard.id());
    }
}
