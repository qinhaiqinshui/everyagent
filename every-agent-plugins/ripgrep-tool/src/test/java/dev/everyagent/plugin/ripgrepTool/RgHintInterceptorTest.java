// ea: JUnit5 单元测试：只依赖 every-agent-plugin-api + Spring AI 类型（§14.9 红线：不依赖 worker）
package dev.everyagent.plugin.ripgrepTool;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RgHintInterceptor} 单测：命中 / 不命中矩阵 + hint 追加位置。
 *
 * <p>ToolExecutionResult 用真实 Spring AI 类型构造（builder + ToolResponseMessage），
 * 链与上下文用最小 fake——上行后处理路径（next.proceed 返回后）与生产完全一致。
 */
class RgHintInterceptorTest {

    private static final Path WS = Path.of("C:\\ws");

    private static final String BARE_RG_EXIT1_EMPTY = "[exit code: 1]";
    private static final String HINTED =
            ExecResults.appendNote(BARE_RG_EXIT1_EMPTY, RgHintInterceptor.HINT);

    // ---- 最小 fake：ToolExecutionContext（ExecContext 槽位委托给 ToolContexts 的现成 fake）----

    record FakeToolExecCtx(ExecContext base, List<AssistantMessage.ToolCall> toolCalls)
            implements ToolExecutionContext {

        @Override public Prompt prompt() { return null; }
        @Override public ChatResponse chatResponse() { return null; }
        @Override public String subjectId() { return base.subjectId(); }
        @Override public String workspaceRoot() { return base.workspaceRoot(); }
        @Override public String workspaceId() { return base.workspaceId(); }
        @Override public ModelConfig snapshot() { return base.snapshot(); }
        @Override public EventEmitter emitter() { return base.emitter(); }
        @Override public AgentFactory agentFactory() { return base.agentFactory(); }
        @Override public Map<String, Object> metadata() { return base.metadata(); }
        @Override public Path dataDir() { return base.dataDir(); }
        @Override public boolean terminal() { return base.terminal(); }
        @Override public InteractionService interaction() { return base.interaction(); }
        @Override public Map<String, AgentContext> agents() { return base.agents(); }
    }

    private static AssistantMessage.ToolCall shellCall(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    /** 单工具结果形态的 ToolExecutionResult（history = 助手消息 + 工具响应消息）。 */
    private static ToolExecutionResult resultOf(String callId, String toolName, String data,
            boolean returnDirect) {
        return ToolExecutionResult.builder()
                .conversationHistory(List.of(
                        new AssistantMessage("call"),
                        ToolResponseMessage.builder()
                                .responses(List.of(new ToolResponse(callId, toolName, data)))
                                .build()))
                .returnDirect(returnDirect)
                .build();
    }

    private static ToolExecutionResult run(List<AssistantMessage.ToolCall> calls,
            ToolExecutionResult preset) throws Exception {
        return new RgHintInterceptor()
                .invoke(new FakeToolExecCtx(ToolContexts.ctx("direct", WS), calls), c -> preset);
    }

    private static String lastResponseData(ToolExecutionResult r) {
        List<Message> h = r.conversationHistory();
        Message last = h.get(h.size() - 1);
        return ((ToolResponseMessage) last).getResponses().get(0).responseData();
    }

    // ---- 命中 ----

    @Test
    void hitsPowershellBareRgAndAppendsHintAtTail() throws Exception {
        ToolExecutionResult out = run(
                List.of(shellCall("c1", "powershell", "{\"command\":\"rg foo\"}")),
                resultOf("c1", "powershell", BARE_RG_EXIT1_EMPTY, false));
        String data = lastResponseData(out);
        // 追加位置：原文在前、hint 独立成行收尾（appendNote 形态），原文一字不丢
        assertEquals(HINTED, data);
        assertTrue(data.startsWith(BARE_RG_EXIT1_EMPTY + "\n"), "原文必须在最前，hint 只追加尾部");
        assertTrue(data.endsWith(RgHintInterceptor.HINT + "\n"), "hint 必须独立成行收尾");
        // hint 文案要点：引导改用 rg 工具 + path 默认工作区说明 + shell 侧显式带路径
        assertTrue(RgHintInterceptor.HINT.contains("rg 工具"), "必须引导改用 rg 工具");
        assertTrue(RgHintInterceptor.HINT.contains("工作区"), "必须说明 path 省略默认工作区");
        assertTrue(RgHintInterceptor.HINT.contains("rg <pattern> ."), "必须给出 shell 侧显式带路径写法");
    }

    @Test
    void hitsBashAndCaseInsensitiveRgToken() throws Exception {
        ToolExecutionResult out = run(
                List.of(shellCall("c1", "bash", "{\"command\":\"RG foo\"}")),
                resultOf("c1", "bash", BARE_RG_EXIT1_EMPTY, false));
        assertEquals(HINTED, lastResponseData(out), "bash 工具同样兜底；PowerShell 命令名不区分大小写");
    }

    @Test
    void hitsRgExeForm() throws Exception {
        ToolExecutionResult out = run(
                List.of(shellCall("c1", "powershell", "{\"command\":\"rg.exe foo\"}")),
                resultOf("c1", "powershell", BARE_RG_EXIT1_EMPTY, false));
        assertEquals(HINTED, lastResponseData(out), "rg.exe 里的 rg 命中词边界，是正确行为（它就是 rg）");
    }

    @Test
    void hitsPipedRgWhoseExitCodeDominates() throws Exception {
        // 管道后置 cmdlet 场景：$LASTEXITCODE 仍传导 rg 的 1，输出为空 → 该命中
        ToolExecutionResult out = run(
                List.of(shellCall("c1", "powershell",
                        "{\"command\":\"rg foo | Select-Object -First 5\"}")),
                resultOf("c1", "powershell", BARE_RG_EXIT1_EMPTY, false));
        assertEquals(HINTED, lastResponseData(out));
    }

    @Test
    void onlyHitResponseRewrittenOthersUntouched() throws Exception {
        ToolResponse hit = new ToolResponse("c1", "powershell", BARE_RG_EXIT1_EMPTY);
        ToolResponse sibling = new ToolResponse("c2", "powershell", "ok");
        ToolResponse nonShell = new ToolResponse("c3", "rg", BARE_RG_EXIT1_EMPTY);
        AssistantMessage assistant = new AssistantMessage("calls");
        ToolExecutionResult preset = ToolExecutionResult.builder()
                .conversationHistory(List.of(assistant,
                        ToolResponseMessage.builder()
                                .responses(List.of(hit, sibling, nonShell)).build()))
                .returnDirect(true)
                .build();
        ToolExecutionResult out = run(
                List.of(shellCall("c1", "powershell", "{\"command\":\"rg foo\"}"),
                        shellCall("c2", "powershell", "{\"command\":\"git status\"}"),
                        shellCall("c3", "rg", "{\"pattern\":\"foo\"}")),
                preset);

        ToolResponseMessage trm = (ToolResponseMessage) out.conversationHistory().get(1);
        assertEquals(HINTED, trm.getResponses().get(0).responseData(), "仅命中的 c1 追加 hint");
        assertEquals("ok", trm.getResponses().get(1).responseData(), "同消息内未命中的兄弟响应原样");
        assertEquals(BARE_RG_EXIT1_EMPTY, trm.getResponses().get(2).responseData(), "非 shell 工具结果不碰");
        assertSame(assistant, out.conversationHistory().get(0), "非工具响应消息保持同一实例");
        assertTrue(out.returnDirect(), "returnDirect 等其余结果字段保持");
    }

    // ---- 不命中矩阵（原样返回，含引用同一性）----

    @Test
    void noHintOnExitZero() throws Exception {
        ToolExecutionResult preset = resultOf("c1", "powershell", "src/a.java:1:foo", false);
        assertSame(preset, run(
                List.of(shellCall("c1", "powershell", "{\"command\":\"rg foo\"}")), preset),
                "exit 0（真有匹配）不改写");
    }

    @Test
    void noHintWhenStdoutNonEmpty() throws Exception {
        ToolExecutionResult preset = resultOf("c1", "powershell", "m\n[exit code: 1]", false);
        assertSame(preset, run(
                List.of(shellCall("c1", "powershell", "{\"command\":\"rg foo\"}")), preset),
                "exit 1 但 stdout 非空（真·无匹配之外的形态）不改写");
    }

    @Test
    void noHintWhenStderrPresent() throws Exception {
        ToolExecutionResult preset = resultOf("c1", "powershell",
                "[stderr]\nregex parse error\n[exit code: 2]", false);
        assertSame(preset, run(
                List.of(shellCall("c1", "powershell", "{\"command\":\"rg ( foo\"}")), preset),
                "exit 2 正则错误 + stderr 有内容：不是「空 stdin 零匹配」形态");
    }

    @Test
    void noHintWhenCommandHasNoRg() throws Exception {
        ToolExecutionResult preset = resultOf("c1", "powershell", BARE_RG_EXIT1_EMPTY, false);
        assertSame(preset, run(
                List.of(shellCall("c1", "powershell", "{\"command\":\"grep foo\"}")), preset),
                "命令里没有 rg token");
    }

    @Test
    void argTokenDoesNotCountAsRg() throws Exception {
        ToolExecutionResult preset = resultOf("c1", "powershell", BARE_RG_EXIT1_EMPTY, false);
        assertSame(preset, run(
                List.of(shellCall("c1", "powershell", "{\"command\":\"echo arg rgx cargo\"}")),
                preset), "arg / rgx / cargo 里的字母串不是独立 rg token（词边界）");
    }

    @Test
    void noHintForNonShellToolName() throws Exception {
        ToolExecutionResult preset = resultOf("c1", "rg", BARE_RG_EXIT1_EMPTY, false);
        assertSame(preset, run(
                List.of(shellCall("c1", "rg", "{\"pattern\":\"foo\"}")), preset),
                "非 powershell/bash 工具（哪怕结果形态相同）不检查");
    }

    @Test
    void noHintWhenNoShellCallsThisRound() throws Exception {
        ToolExecutionResult preset = resultOf("c1", "powershell", BARE_RG_EXIT1_EMPTY, false);
        assertSame(preset, run(List.of(), preset), "本轮无 tool call → 直接放行");
        assertNull(run(null, null), "proceed 返回 null 原样透传");
    }

    // ---- 判定函数与 command 提取的直测 ----

    @Test
    void shouldHintMatrix() {
        assertTrue(RgHintInterceptor.shouldHint(BARE_RG_EXIT1_EMPTY, "rg foo"));
        assertTrue(RgHintInterceptor.shouldHint(" [exit code: 1]\n", "cd tmp; rg foo"),
                "结果文本首尾空白不影响（strip 后判定）");
        assertFalse(RgHintInterceptor.shouldHint(BARE_RG_EXIT1_EMPTY, "echo arg"));
        assertFalse(RgHintInterceptor.shouldHint("[exit code: 2]", "rg ( foo"), "只认 exit 1");
        assertFalse(RgHintInterceptor.shouldHint("[命令执行完成(无输出)]", "rg foo"),
                "rc=0 空输出占位形态不命中（无 exit 1 尾注）");
        assertFalse(RgHintInterceptor.shouldHint(null, "rg foo"));
        assertFalse(RgHintInterceptor.shouldHint(BARE_RG_EXIT1_EMPTY, null));
    }

    @Test
    void extractCommandHandlesEscapedJson() {
        assertEquals("rg \"a b\" .", RgHintInterceptor
                .extractCommand("{\"command\":\"rg \\\"a b\\\" .\"}"), "引号转义须反转义");
        assertEquals("rg C:\\ws\\x", RgHintInterceptor
                .extractCommand("{\"other\":1,\"command\":\"rg C:\\\\ws\\\\x\"}"),
                "反斜杠转义反转义 + 多字段 JSON 里定位 command");
        assertNull(RgHintInterceptor.extractCommand(null));
        assertNull(RgHintInterceptor.extractCommand("{\"pattern\":\"foo\"}"), "无 command 字段 → null");
    }
}
