package dev.everyagent.plugin.workflow.engine;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.workflow.model.AgentEventLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 命名 agent 会话线的读侧与写侧投影（「Agent 上下文模型 §2」的实现底座）。
 *
 * <ul>
 *   <li><b>重建</b>（{@link #rebuild}）：读 {@code agents/<name>.jsonl} 事件流按 worker
 *       {@code ConversationLoader} 同款谓词全保真重建 {@code List<Message>}——
 *       user.message → UserMessage；message → AssistantMessage(正文 + 工具调用真实 id)；
 *       tool.result → ToolResponseMessage(summary 兼容 content)；被中断的轮次
 *       (toolCalls 无配对 result)补合成占位结果「[上一轮被中断,无结果]」；
 *       system/thinking/usage 等其余事件不进会话。失败返回空表不抛（永不抛异常契约）。</li>
 *   <li><b>投影</b>（{@link #projectPayload}）：EmitEvent → jsonl 行 payload，形态照抄
 *       worker {@code TaskEvents.emit} 的映射分支（title/summary/content/status 非空才写，
 *       data → Json.toJson）——与 task 域 {@code <agentId>.jsonl} 逐字对齐，
 *       保证重建谓词两域通用。</li>
 * </ul>
 */
public final class AgentSessions {

    private static final Logger log = LoggerFactory.getLogger(AgentSessions.class);

    /** 中断轮合成的占位工具结果(与 ConversationLoader 同文案,模型可读、配对完整)。 */
    public static final String INTERRUPTED_RESULT = "[上一轮被中断,无结果]";

    /** 会话种子尾部为孤立 user(中断早于任何回复)时补的占位 assistant 轮(SubAgentManager 同语义)。 */
    public static final String NO_FINAL_ANSWER_PLACEHOLDER = "[上一轮被中断,无最终回答]";

    /** 会话重建行数上限(与 ConversationLoader.MAX 同量级防御)。 */
    private static final int MAX = 100_000;

    private AgentSessions() {
    }

    // ══════════════════════════ 读侧：事件流 → 会话 ══════════════════════════

    /**
     * 按 ConversationLoader 同款谓词重建会话（不含 system 轮——system 由装配时注入）。
     * 坏行/空 payload 容错跳过；任何异常返回已重建前缀或空表，不抛。
     */
    public static List<Message> rebuild(List<AgentEventLine> lines) {
        List<Message> out = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            return out;
        }
        try {
            int n = 0;
            List<ToolCall> pending = null;
            List<ToolResponse> collected = new ArrayList<>();
            for (AgentEventLine r : lines) {
                if (r == null || ++n > MAX) {
                    break;
                }
                JsonNode p = r.payload() == null ? Json.obj() : r.payload();
                switch (r.event() == null ? "" : r.event()) {
                    case Events.USER_MESSAGE -> {
                        flushTools(out, pending, collected);
                        pending = null;
                        out.add(new UserMessage(p.path("content").asString("")));
                    }
                    case Events.MESSAGE -> {
                        flushTools(out, pending, collected);
                        pending = null;
                        List<ToolCall> calls = new ArrayList<>();
                        for (JsonNode tc : p.path("data").path("toolCalls")) {
                            calls.add(new ToolCall(tc.path("id").asString(""),
                                    "function", tc.path("name").asString(""),
                                    tc.path("arguments").asString("")));
                        }
                        out.add(AssistantMessage.builder()
                                .content(p.path("content").asString(""))
                                .toolCalls(calls)
                                .build());
                        if (!calls.isEmpty()) {
                            pending = calls;
                        }
                    }
                    case Events.TOOL_RESULT -> {
                        if (pending != null) {
                            JsonNode data = p.path("data");
                            collected.add(new ToolResponse(data.path("callId").asString(""),
                                    data.path("name").asString(""), toolResultText(p)));
                        }
                    }
                    default -> {
                        // usage/system/thinking/瞬态与终态事件不进会话
                    }
                }
            }
            flushTools(out, pending, collected);
            return out;
        } catch (RuntimeException e) {
            log.warn("[workflow] 会话重建失败,返回已重建前缀(条数={})", out.size(), e);
            return out;
        }
    }

    /**
     * tool.result 文本：统一事件模型里工具结果写在 {@code payload.summary}
     * (发射侧 ToolEventInterceptor 把结果串作 summary 下发)，兼容更早记录回落 content。
     */
    private static String toolResultText(JsonNode payload) {
        JsonNode summary = payload.path("summary");
        if (summary.isTextual() && !summary.asString().isEmpty()) {
            return summary.asString();
        }
        JsonNode content = payload.path("content");
        return content.isTextual() ? content.asString() : "";
    }

    /** 上一轮 toolCalls 收口：缺 result 的补合成占位，整批转 ToolResponseMessage。 */
    private static void flushTools(List<Message> out, List<ToolCall> pending,
            List<ToolResponse> collected) {
        if (pending == null || pending.isEmpty()) {
            collected.clear();
            return;
        }
        List<ToolResponse> responses = new ArrayList<>(pending.size());
        for (ToolCall tc : pending) {
            ToolResponse hit = null;
            for (ToolResponse r : collected) {
                if (r.id() != null && r.id().equals(tc.id())) {
                    hit = r;
                    break;
                }
            }
            responses.add(hit != null ? hit
                    : new ToolResponse(tc.id(), tc.name(), INTERRUPTED_RESULT));
        }
        out.add(ToolResponseMessage.builder().responses(responses).build());
        collected.clear();
    }

    // ══════════════════════════ 写侧：EmitEvent → 行 payload ══════════════════════════

    /**
     * EmitEvent → jsonl 行 payload（照抄 worker {@code TaskEvents.emit} 的映射分支：
     * title/summary/content/status 非空才写；data → Json.toJson）。
     */
    public static ObjectNode projectPayload(EmitEvent e) {
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
        return payload;
    }

    /**
     * 会话种子尾部为孤立 user 时补占位 assistant 轮（user/assistant 交替不变量，
     * SubAgentManager.rebuildFromArchive 同语义——防续跑时两条连续 user 重复回答上一轮指令）。
     * 返回补齐后的新列表（入参不变）。
     */
    public static List<Message> ensureAlternation(List<Message> seed) {
        if (seed == null || seed.isEmpty()) {
            return seed == null ? List.of() : seed;
        }
        if (seed.get(seed.size() - 1) instanceof UserMessage) {
            List<Message> fixed = new ArrayList<>(seed);
            fixed.add(AssistantMessage.builder().content(NO_FINAL_ANSWER_PLACEHOLDER).build());
            return fixed;
        }
        return seed;
    }
}
