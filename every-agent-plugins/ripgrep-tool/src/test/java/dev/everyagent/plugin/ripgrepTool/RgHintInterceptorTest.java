// ea: JUnit5 单元测试：只依赖 every-agent-plugin-api + Spring AI 类型（§14.9 红线：不依赖 worker）
package dev.everyagent.plugin.ripgrepTool;

import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RgHintInterceptor} 单测（per-call 形态）：命中 / 不命中矩阵 + hint 追加位置。
 *
 * <p>链与上下文用最小 fake——上行后处理路径（next.proceed 返回当前调用结果串后判定）
 * 与生产完全一致（per-call:每次工具调用一遍链,当前调用即全部世界,无历史扫描）。
 */
class RgHintInterceptorTest {

    private static final String BARE_RG_EXIT1_EMPTY = "[exit code: 1]";
    private static final String HINTED =
            ExecResults.appendNote(BARE_RG_EXIT1_EMPTY, RgHintInterceptor.HINT);

    // ---- 命中 ----

    @Test
    void hitsPowershellBareRgAndAppendsHintAtTail() throws Exception {
        String out = run(call("c1", "powershell", "{\"command\":\"rg foo\"}"),
                BARE_RG_EXIT1_EMPTY);
        // 追加位置：原文在前、hint 独立成行收尾（appendNote 形态），原文一字不丢
        assertEquals(HINTED, out);
        assertTrue(out.startsWith(BARE_RG_EXIT1_EMPTY + "\n"), "原文必须在最前，hint 只追加尾部");
        assertTrue(out.endsWith(RgHintInterceptor.HINT + "\n"), "hint 必须独立成行收尾");
        // hint 文案要点：引导改用 rg 工具 + path 默认工作区说明 + shell 侧显式带路径
        assertTrue(RgHintInterceptor.HINT.contains("rg 工具"), "必须引导改用 rg 工具");
        assertTrue(RgHintInterceptor.HINT.contains("工作区"), "必须说明 path 省略默认工作区");
        assertTrue(RgHintInterceptor.HINT.contains("rg <pattern> ."), "必须给出 shell 侧显式带路径写法");
    }

    @Test
    void hitsBashAndCaseInsensitiveRgToken() throws Exception {
        assertEquals(HINTED, run(call("c1", "bash", "{\"command\":\"RG foo\"}"),
                BARE_RG_EXIT1_EMPTY), "bash 工具同样兜底；PowerShell 命令名不区分大小写");
    }

    @Test
    void hitsRgExeForm() throws Exception {
        assertEquals(HINTED, run(call("c1", "powershell", "{\"command\":\"rg.exe foo\"}"),
                BARE_RG_EXIT1_EMPTY), "rg.exe 里的 rg 命中词边界，是正确行为（它就是 rg）");
    }

    @Test
    void hitsPipedRgWhoseExitCodeDominates() throws Exception {
        // 管道后置 cmdlet 场景：$LASTEXITCODE 仍传导 rg 的 1，输出为空 → 该命中
        assertEquals(HINTED, run(call("c1", "powershell",
                "{\"command\":\"rg foo | Select-Object -First 5\"}"), BARE_RG_EXIT1_EMPTY));
    }

    @Test
    void onlyCurrentCallCheckedBatchSiblingsUntouched() throws Exception {
        // per-call 语义：一次 invoke 只看当前调用——同批其他调用的形态不由本节点处理
        //（各自有独立的一次链执行）。这里验证当前调用为非 shell 工具时不检查。
        String out = run(call("c1", "rg", "{\"pattern\":\"foo\"}"), BARE_RG_EXIT1_EMPTY);
        assertEquals(BARE_RG_EXIT1_EMPTY, out, "非 shell 工具（哪怕结果形态相同）不检查");
    }

    // ---- 不命中矩阵（原串直返）----

    @Test
    void noHintOnExitZero() throws Exception {
        assertEquals("src/a.java:1:foo", run(
                call("c1", "powershell", "{\"command\":\"rg foo\"}"), "src/a.java:1:foo"),
                "exit 0（真有匹配）不改写");
    }

    @Test
    void noHintWhenStdoutNonEmpty() throws Exception {
        assertEquals("m\n[exit code: 1]", run(
                call("c1", "powershell", "{\"command\":\"rg foo\"}"), "m\n[exit code: 1]"),
                "exit 1 但 stdout 非空（真·无匹配之外的形态）不改写");
    }

    @Test
    void noHintWhenStderrPresent() throws Exception {
        assertEquals("[stderr]\nregex parse error\n[exit code: 2]", run(
                call("c1", "powershell", "{\"command\":\"rg ( foo\"}"),
                "[stderr]\nregex parse error\n[exit code: 2]"),
                "exit 2 正则错误 + stderr 有内容：不是「空 stdin 零匹配」形态");
    }

    @Test
    void noHintWhenCommandHasNoRg() throws Exception {
        assertEquals(BARE_RG_EXIT1_EMPTY, run(
                call("c1", "powershell", "{\"command\":\"grep foo\"}"), BARE_RG_EXIT1_EMPTY),
                "命令里没有 rg token");
    }

    @Test
    void argTokenDoesNotCountAsRg() throws Exception {
        assertEquals(BARE_RG_EXIT1_EMPTY, run(
                call("c1", "powershell", "{\"command\":\"echo arg rgx cargo\"}"),
                BARE_RG_EXIT1_EMPTY), "arg / rgx / cargo 里的字母串不是独立 rg token（词边界）");
    }

    @Test
    void nullSafety() throws Exception {
        assertNull(run(call("c1", "powershell", null), null), "proceed 返回 null 原样透传");
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

    // ---- 最小 fake ----

    private static AssistantMessage.ToolCall call(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    private static String run(AssistantMessage.ToolCall call, String preset) throws Exception {
        return new RgHintInterceptor().invoke(ctx(call), c -> preset);
    }

    /** 最小 per-call ctx(ExecContext 槽位取最小假值;判定只读 toolCall/arguments)。 */
    private static ToolExecutionContext ctx(AssistantMessage.ToolCall call) {
        return new ToolExecutionContext() {
            @Override public Prompt prompt() { return null; }
            @Override public ChatResponse chatResponse() { return null; }
            @Override public List<AssistantMessage.ToolCall> toolCalls() { return List.of(call); }
            @Override public AssistantMessage.ToolCall toolCall() { return call; }
            @Override public ToolCallback toolCallback() { return null; }
            @Override public String arguments() { return call.arguments(); }
            @Override public void arguments(String arguments) { }
            @Override public void synthesize(String result) { }
            @Override public String synthesizedResult() { return null; }
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
            @Override public Map<String, dev.everyagent.plugin.api.agent.AgentContext> agents() {
                return Map.of();
            }
        };
    }
}
