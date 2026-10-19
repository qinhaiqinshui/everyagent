package dev.everyagent.plugin.unattended;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import dev.everyagent.plugin.api.slash.SlashTokenService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link UnattendedToolInterceptor} 单测:per-call 链下行合成(方案 B 切口)。
 *
 * <ul>
 *   <li>开关关 / 当前调用非 ask_user → 放行(next 被调用,无合成哨兵);</li>
 *   <li>开关开 + 当前调用 ask_user → {@code ctx.synthesize} 置哨兵 + 照常 proceed
 *       (链尾跳过真实提问,合成串走框架 ToolResponse 构造路径,混合轮天然正确);</li>
 *   <li>合成答案格式:「题干:首选项」逐题一行。</li>
 * </ul>
 *
 * <p>约束(§14.9):插件对 worker 零依赖——WorkerServices 用 Mockito mock 承接
 * (形态对照 {@link UnattendedAuthHandlerTest})。
 */
class UnattendedToolInterceptorTest {

    private static final String ASK_ARGS = """
            {"questions":[{"question":"是否删除该文件","options":["删除","保留"]},
            {"question":"继续吗","options":[{"text":"继续"},"停止"]}]}""";

    // ---- 放行 ----

    @Test
    void passesThroughWhenUnattendedDisabled() throws Exception {
        AtomicInteger proceeds = new AtomicInteger();
        Ctx ctx = new Ctx(call("c1", "ask_user", ASK_ARGS));
        String out = new UnattendedToolInterceptor(services(false))
                .invoke(ctx, c -> {
                    proceeds.incrementAndGet();
                    return "real";
                });
        assertEquals("real", out, "开关关:真实执行结果透传");
        assertEquals(1, proceeds.get());
        assertNull(ctx.synthesizedResult(), "不置合成哨兵");
    }

    @Test
    void passesThroughNonAskUserCallsEvenWhenEnabled() throws Exception {
        AtomicInteger proceeds = new AtomicInteger();
        Ctx ctx = new Ctx(call("c1", "read_file", "{\"path\":\"a.txt\"}"));
        String out = new UnattendedToolInterceptor(services(true))
                .invoke(ctx, c -> {
                    proceeds.incrementAndGet();
                    return "file content";
                });
        assertEquals("file content", out, "混合轮:非 ask_user 调用照常真执行(per-call 判定)");
        assertEquals(1, proceeds.get());
        assertNull(ctx.synthesizedResult());
    }

    // ---- 合成 ----

    @Test
    void synthesizesAskUserAnswerWhenEnabled() throws Exception {
        AtomicInteger proceeds = new AtomicInteger();
        Ctx ctx = new Ctx(call("c1", "ask_user", ASK_ARGS));
        String out = new UnattendedToolInterceptor(services(true))
                .invoke(ctx, c -> {
                    proceeds.incrementAndGet();
                    // 链尾形态:哨兵置位 → 跳过真实调用,返回合成串
                    return ctx.synthesizedResult() != null ? ctx.synthesizedResult() : "real";
                });
        assertEquals(1, proceeds.get(), "照常调 next(合成走框架路径,不是硬短路)");
        assertEquals("是否删除该文件：删除\n继续吗：继续", out, "「题干:首选项」逐题一行");
        assertEquals(out, ctx.synthesizedResult(), "哨兵串即合成答案");
    }

    @Test
    void emptyQuestionsYieldSkipMessage() throws Exception {
        Ctx ctx = new Ctx(call("c1", "ask_user", "{\"questions\":[]}"));
        new UnattendedToolInterceptor(services(true)).invoke(ctx, c -> ctx.synthesizedResult());
        assertEquals("未提供任何问题,跳过提问。", ctx.synthesizedResult());
    }

    @Test
    void malformedArgumentsYieldSkipMessage() throws Exception {
        Ctx ctx = new Ctx(call("c1", "ask_user", "not-json"));
        new UnattendedToolInterceptor(services(true)).invoke(ctx, c -> ctx.synthesizedResult());
        assertEquals("问题解析失败,跳过提问。", ctx.synthesizedResult());
    }

    // ---- fake ----

    private static WorkerServices services(boolean unattended) {
        WorkerServices services = Mockito.mock(WorkerServices.class);
        SlashTokenService tokens = Mockito.mock(SlashTokenService.class);
        when(services.slashTokens()).thenReturn(tokens);
        when(tokens.hasKind(Mockito.anyString(), Mockito.eq(UnattendedToken.KIND))).thenReturn(unattended);
        return services;
    }

    private static AssistantMessage.ToolCall call(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    /** 最小 per-call ctx:带可变合成哨兵槽(其余槽位最小假值)。 */
    private static final class Ctx implements ToolExecutionContext {

        private final AssistantMessage.ToolCall call;
        private String synthesized;

        Ctx(AssistantMessage.ToolCall call) {
            this.call = call;
        }

        @Override public Prompt prompt() { return null; }
        @Override public ChatResponse chatResponse() { return null; }
        @Override public List<AssistantMessage.ToolCall> toolCalls() { return List.of(call); }
        @Override public AssistantMessage.ToolCall toolCall() { return call; }
        @Override public ToolCallback toolCallback() { return null; }
        @Override public String arguments() { return call.arguments(); }
        @Override public void arguments(String arguments) { }
        @Override public void synthesize(String result) { this.synthesized = result; }
        @Override public String synthesizedResult() { return synthesized; }
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
}
