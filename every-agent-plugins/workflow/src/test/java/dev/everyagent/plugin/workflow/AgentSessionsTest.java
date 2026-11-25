// ea: 会话重建/事件投影/决策解析/渲染/条件求值 单元测试（不依赖 worker，§14.9）
package dev.everyagent.plugin.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.EmitEvent.Mode;
import dev.everyagent.plugin.workflow.engine.AgentSessions;
import dev.everyagent.plugin.workflow.engine.NodeRunners;
import dev.everyagent.plugin.workflow.engine.NodeRunners.Decision;
import dev.everyagent.plugin.workflow.model.AgentEventLine;
import dev.everyagent.plugin.workflow.model.ConditionDef;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 会话续跑保真（jsonl→Message 重建工具轮完整、中断轮占位补齐）+ 决策协议 + 渲染 + 条件求值。 */
class AgentSessionsTest {

    private static AgentEventLine line(String event, String json) {
        return new AgentEventLine("1", 0L, event, "writer#wi_x",
                (tools.jackson.databind.JsonNode) Json.parse(json));
    }

    // ── 重建：工具轮完整 ──

    @Test
    void rebuildKeepsFullToolRounds() {
        List<AgentEventLine> lines = List.of(
                line("user.message", "{\"content\":\"写大纲\"}"),
                line("message", "{\"content\":\"\",\"data\":{\"toolCalls\":"
                        + "[{\"id\":\"c1\",\"name\":\"write_file\",\"arguments\":\"{\\\"p\\\":1}\"}]}}"),
                line("tool.result", "{\"summary\":\"已写入\",\"data\":"
                        + "{\"callId\":\"c1\",\"name\":\"write_file\",\"status\":\"success\"}}"),
                line("message", "{\"content\":\"大纲完成\",\"data\":{\"toolCalls\":[]}}"));
        List<Message> msgs = AgentSessions.rebuild(lines);

        assertThat(msgs).hasSize(4);
        assertThat(msgs.get(0)).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class);
        assertThat(msgs.get(0).getText()).isEqualTo("写大纲");
        AssistantMessage a1 = (AssistantMessage) msgs.get(1);
        assertThat(a1.getToolCalls()).hasSize(1);
        assertThat(a1.getToolCalls().get(0).id()).isEqualTo("c1");
        assertThat(a1.getToolCalls().get(0).name()).isEqualTo("write_file");
        assertThat(a1.getToolCalls().get(0).arguments()).isEqualTo("{\"p\":1}");
        ToolResponseMessage tr = (ToolResponseMessage) msgs.get(2);
        assertThat(tr.getResponses()).hasSize(1);
        assertThat(tr.getResponses().get(0).id()).isEqualTo("c1");
        assertThat(tr.getResponses().get(0).responseData()).isEqualTo("已写入");
        AssistantMessage a2 = (AssistantMessage) msgs.get(3);
        assertThat(a2.getText()).isEqualTo("大纲完成");
    }

    // ── 重建：中断轮占位补齐 ──

    @Test
    void interruptedRoundGetsPlaceholderResult() {
        List<AgentEventLine> lines = List.of(
                line("user.message", "{\"content\":\"继续\"}"),
                line("message", "{\"content\":\"\",\"data\":{\"toolCalls\":["
                        + "{\"id\":\"c9\",\"name\":\"shell\",\"arguments\":\"{}\"}]}}"),
                // 无 tool.result：中断
                line("usage", "{\"data\":{\"round\":{}}}"));
        List<Message> msgs = AgentSessions.rebuild(lines);

        assertThat(msgs).hasSize(3);
        ToolResponseMessage tr = (ToolResponseMessage) msgs.get(2);
        assertThat(tr.getResponses().get(0).responseData())
                .isEqualTo(AgentSessions.INTERRUPTED_RESULT);
    }

    @Test
    void interruptedRoundKeepsMatchedResultsAndFillsOnlyMissing() {
        List<AgentEventLine> lines = List.of(
                line("message", "{\"data\":{\"toolCalls\":["
                        + "{\"id\":\"a\",\"name\":\"t\",\"arguments\":\"{}\"},"
                        + "{\"id\":\"b\",\"name\":\"t\",\"arguments\":\"{}\"}]}}"),
                line("tool.result", "{\"summary\":\"A 结果\",\"data\":{\"callId\":\"a\",\"name\":\"t\"}}"));
        List<Message> msgs = AgentSessions.rebuild(lines);
        ToolResponseMessage tr = (ToolResponseMessage) msgs.get(1);
        assertThat(tr.getResponses()).hasSize(2);
        assertThat(tr.getResponses().get(0).responseData()).isEqualTo("A 结果");
        assertThat(tr.getResponses().get(1).responseData()).isEqualTo(AgentSessions.INTERRUPTED_RESULT);
    }

    // ── 重建：非会话事件忽略 / 容错 ──

    @Test
    void nonConversationEventsIgnored() {
        List<AgentEventLine> lines = List.of(
                line("agent.started", "{\"title\":\"x\"}"),
                line("system", "{\"content\":\"sys\"}"),
                line("thinking", "{\"text\":\"t\"}"),
                line("usage", "{\"data\":{}}"),
                line("user.message", "{\"content\":\"u\"}"));
        List<Message> msgs = AgentSessions.rebuild(lines);
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0).getText()).isEqualTo("u");
    }

    @Test
    void emptyAndNullSafe() {
        assertThat(AgentSessions.rebuild(null)).isEmpty();
        assertThat(AgentSessions.rebuild(List.of())).isEmpty();
    }

    // ── 投影：EmitEvent → payload（TaskEvents.emit 同形） ──

    @Test
    void projectPayloadMatchesTaskWireShape() {
        var userMessage = new EmitEvent(1L, "user.message", "writer#wi", null, null, "指令文本",
                null, java.util.Map.of("rawContent", "原始串"), true, Mode.REPLACE);
        var p1 = AgentSessions.projectPayload(userMessage);
        assertThat(p1.path("content").asString()).isEqualTo("指令文本");
        assertThat(p1.path("data").path("rawContent").asString()).isEqualTo("原始串");

        var toolResult = new EmitEvent(2L, "tool.result", "writer#wi", null, "结果串", null, null,
                java.util.Map.of("callId", "c1", "name", "shell", "status", "success"), true, Mode.REPLACE);
        var p2 = AgentSessions.projectPayload(toolResult);
        assertThat(p2.path("summary").asString()).isEqualTo("结果串");
        assertThat(p2.path("data").path("callId").asString()).isEqualTo("c1");

        var message = new EmitEvent(3L, "message", "writer#wi", null, null, "正文", null,
                java.util.Map.of("thinking", "", "toolCalls", List.of()), true, Mode.REPLACE);
        var p3 = AgentSessions.projectPayload(message);
        assertThat(p3.path("content").asString()).isEqualTo("正文");
        assertThat(p3.path("data").has("toolCalls")).isTrue();
    }

    /** 投影→重建往返：wire 形态即重建谓词的输入。 */
    @Test
    void projectedEventsRoundTripThroughRebuild() {
        List<AgentEventLine> lines = List.of(
                new AgentEventLine("1", 0L, "user.message", "writer#wi",
                        AgentSessions.projectPayload(new EmitEvent(1L, "user.message", "writer#wi",
                                null, null, "第一轮指令", null, null, true, Mode.REPLACE))),
                new AgentEventLine("2", 0L, "message", "writer#wi",
                        AgentSessions.projectPayload(new EmitEvent(2L, "message", "writer#wi", null,
                                null, "调用工具", null,
                                java.util.Map.of("toolCalls", List.of(
                                        java.util.Map.of("id", "x1", "name", "shell",
                                                "arguments", "{}"))), true, Mode.REPLACE))),
                new AgentEventLine("3", 0L, "tool.result", "writer#wi",
                        AgentSessions.projectPayload(new EmitEvent(3L, "tool.result", "writer#wi",
                                null, "shell 输出", null, null,
                                java.util.Map.of("callId", "x1", "name", "shell"), true,
                                Mode.REPLACE))));
        List<Message> msgs = AgentSessions.rebuild(lines);
        assertThat(msgs).hasSize(3);
        assertThat(((ToolResponseMessage) msgs.get(2)).getResponses().get(0).responseData())
                .isEqualTo("shell 输出");
    }

    // ── 交替不变量补齐 ──

    @Test
    void loneUserTailGetsPlaceholderAssistant() {
        List<Message> seed = List.of(
                new org.springframework.ai.chat.messages.UserMessage("u1"),
                org.springframework.ai.chat.messages.AssistantMessage.builder().content("a1").build(),
                new org.springframework.ai.chat.messages.UserMessage("u2"));
        List<Message> fixed = AgentSessions.ensureAlternation(seed);
        assertThat(fixed).hasSize(4);
        assertThat(fixed.get(3)).isInstanceOf(AssistantMessage.class);
        assertThat(fixed.get(3).getText()).isEqualTo(AgentSessions.NO_FINAL_ANSWER_PLACEHOLDER);
        // 正常尾巴不动
        List<Message> normal = List.of(new org.springframework.ai.chat.messages.UserMessage("u1"),
                org.springframework.ai.chat.messages.AssistantMessage.builder().content("a1").build());
        assertThat(AgentSessions.ensureAlternation(normal)).isSameAs(normal);
    }

    // ── supervisor 决策解析 ──

    @Test
    void parseDecisionPlainAndFenced() {
        Decision plain = NodeRunners.parseDecision("{\"action\":\"retry\",\"reason\":\"瞬时\"}");
        assertThat(plain.action()).isEqualTo("retry");
        assertThat(plain.reason()).isEqualTo("瞬时");

        Decision fenced = NodeRunners.parseDecision(
                "```json\n{\"action\":\"retryWithInput\",\"input\":\"新输入\",\"reason\":\"换姿势\"}\n```");
        assertThat(fenced.action()).isEqualTo("retryWithInput");
        assertThat(fenced.input()).isEqualTo("新输入");

        Decision noisy = NodeRunners.parseDecision("决策如下：\n{\"action\":\"skip\"}\n请执行。");
        assertThat(noisy.action()).isEqualTo("skip");

        assertThat(NodeRunners.parseDecision("完全没有 JSON")).isNull();
        assertThat(NodeRunners.parseDecision("{\"action\":\"自创动作\"}")).isNull();
        assertThat(NodeRunners.parseDecision("{\"reason\":\"缺 action\"}")).isNull();
        assertThat(NodeRunners.parseDecision(null)).isNull();
        assertThat(NodeRunners.parseDecision("  ")).isNull();
    }

    // ── 变量渲染 ──

    @Test
    void renderVariablesSinglePassNonRecursive() {
        var decls = List.of(new dev.everyagent.plugin.workflow.model.VariableDef(
                "opt", null, false, "缺省值", null));
        // 基本替换 + 未提供用默认
        assertThat(NodeRunners.render("echo {{name}} {{opt}}",
                java.util.Map.of("name", "星"), decls)).isEqualTo("echo 星 缺省值");
        // 未声明变量提供与否都渲染空串（校验器拦引用，引擎防御）
        assertThat(NodeRunners.render("a{{ghost}}b", java.util.Map.of(), decls)).isEqualTo("ab");
        // 值不递归：值里的 {{}} 不二次展开
        assertThat(NodeRunners.render("x={{v}}",
                java.util.Map.of("v", "{{name}}"), decls)).isEqualTo("x={{name}}");
        // 空模板
        assertThat(NodeRunners.render(null, java.util.Map.of(), decls)).isEmpty();
        // 值里的 $ 等正则元字符安全（quoteReplacement）
        assertThat(NodeRunners.render("{{v}}",
                java.util.Map.of("v", "a$b\\c"), decls)).isEqualTo("a$b\\c");
    }

    // ── 八种条件求值 ──

    @Test
    void conditionEvaluationSemantics() {
        int ok = 0;
        int bad = 1;
        String output = "hello world";
        assertThat(NodeRunners.evaluate(null, ok, output)).isTrue(); // 缺省 always
        assertThat(NodeRunners.evaluate(new ConditionDef("always", null, null, null), bad, output)).isTrue();
        assertThat(NodeRunners.evaluate(new ConditionDef("onSuccess", null, null, null), ok, output)).isTrue();
        assertThat(NodeRunners.evaluate(new ConditionDef("onSuccess", null, null, null), bad, output)).isFalse();
        assertThat(NodeRunners.evaluate(new ConditionDef("onError", null, null, null), bad, output)).isTrue();
        assertThat(NodeRunners.evaluate(new ConditionDef("onError", null, null, null), ok, output)).isFalse();
        assertThat(NodeRunners.evaluate(new ConditionDef("exitCode", 1L, null, null), bad, output)).isTrue();
        assertThat(NodeRunners.evaluate(new ConditionDef("exitCode", 0L, null, null), bad, output)).isFalse();
        assertThat(NodeRunners.evaluate(new ConditionDef("outputContains", null, "hello", null), bad, output)).isTrue();
        assertThat(NodeRunners.evaluate(new ConditionDef("outputContains", null, "zzz", null), bad, output)).isFalse();
        assertThat(NodeRunners.evaluate(new ConditionDef("outputNotContains", null, "zzz", null), bad, output)).isTrue();
        assertThat(NodeRunners.evaluate(new ConditionDef("outputNotContains", null, "hello", null), bad, output)).isFalse();
        // outputEquals：两侧各自 trim 后精确相等（命令输出常带尾随换行）
        assertThat(NodeRunners.evaluate(new ConditionDef("outputEquals", null, "hello world", null), ok, "  hello world\r\n")).isTrue();
        assertThat(NodeRunners.evaluate(new ConditionDef("outputEquals", null, " hello world ", null), bad, "\nhello world")).isTrue();
        assertThat(NodeRunners.evaluate(new ConditionDef("outputEquals", null, "hello", null), ok, output)).isFalse();
        assertThat(NodeRunners.evaluate(new ConditionDef("outputEquals", null, "hello world!", null), ok, output)).isFalse();
        assertThat(NodeRunners.evaluate(new ConditionDef("outputEquals", null, null, null), ok, output)).isFalse();
        assertThat(NodeRunners.evaluate(new ConditionDef("outputMatches", null, null, "hello.*"), ok, output)).isTrue();
        assertThat(NodeRunners.evaluate(new ConditionDef("outputMatches", null, null, "^bye.*"), ok, output)).isFalse();
        assertThat(NodeRunners.evaluate(new ConditionDef("自创", null, null, null), ok, output)).isFalse();
    }
}
