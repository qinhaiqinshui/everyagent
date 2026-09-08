package dev.everyagent.worker.task;

import dev.everyagent.worker.proto.Events;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 冷启动上下文重建(终态任务继续对话,架构 §5.3):
 * 读主 agent 的 &lt;mainAgentId&gt;.jsonl → Spring AI 会话消息序列:
 * user.message → UserMessage;message → AssistantMessage(正文 + 工具调用,真实 id);
 * tool.result → ToolResponseMessage;被中断的轮次(toolCalls 无配对 result)补合成结果。
 * thinking 不进 conversation(自建 AssistantMessage 不带 reasoningContent,不回传 API)。
 * system 消息不进会话(主 agent 无预置 system;请求期 system 内容由 SkillAdvisor 等
 * advisor 注入)。子 agent 会话不重建(子 agent 一次性)。
 */
public final class ConversationLoader {

    private static final Logger log = LoggerFactory.getLogger(ConversationLoader.class);
    private static final int MAX = 100_000;
    /** 中断轮合成的占位结果(模型可读,保持 tool_call/result 配对完整)。 */
    private static final String INTERRUPTED_RESULT = "[上一轮被中断,无结果]";

    private ConversationLoader() {
    }

    /** 磁盘 → 主 agent 会话前缀(不含 system;文件缺失/旧格式返回空列表)。 */
    public static List<Message> load(TaskStore store, java.nio.file.Path dir, String mainAgentId) {
        List<Message> out = new ArrayList<>();
        if (mainAgentId == null || mainAgentId.isEmpty()) {
            return out; // 旧格式任务:无会话文件可重建
        }
        List<EventRecord> records;
        try {
            records = store.readAgentEvents(dir, mainAgentId, 0, MAX);
        } catch (java.io.IOException e) {
            log.warn("会话重建读取失败 dir={} agent={}", dir, mainAgentId, e);
            return out;
        }
        List<ToolCall> pending = null;
        List<ToolResponse> collected = new ArrayList<>();
        for (EventRecord r : records) {
            JsonNode p = r.payload();
            switch (r.event()) {
                case Events.USER_MESSAGE -> {
                    flushTools(out, pending, collected);
                    pending = null;
                    out.add(new UserMessage(p.path("text").asString("")));
                }
                case Events.MESSAGE -> {
                    flushTools(out, pending, collected);
                    pending = null;
                    List<ToolCall> calls = new ArrayList<>();
                    for (JsonNode tc : p.path("toolCalls")) {
                        calls.add(new ToolCall(tc.path("id").asString(""),
                                "function", tc.path("name").asString(""),
                                tc.path("arguments").asString("")));
                    }
                    String text = p.path("text").asString("");
                    out.add(AssistantMessage.builder()
                            .content(text)
                            .toolCalls(calls)
                            .build());
                    if (!calls.isEmpty()) {
                        pending = calls;
                    }
                }
                case Events.TOOL_RESULT -> {
                    if (pending != null) {
                        collected.add(new ToolResponse(p.path("callId").asString(""),
                                p.path("name").asString(""), p.path("summary").asString("")));
                    }
                }
                default -> {
                    // usage/ask.*/瞬态/终态事件与子 agent 事件(不在此文件)不进会话
                }
            }
        }
        flushTools(out, pending, collected);
        return out;
    }

    /** 上一轮 toolCalls 收口:缺 result 的补合成占位,整批转 ToolResponseMessage。 */
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
            if (hit != null) {
                responses.add(hit);
            } else {
                responses.add(new ToolResponse(tc.id(), tc.name(), INTERRUPTED_RESULT));
            }
        }
        out.add(ToolResponseMessage.builder().responses(responses).build());
        collected.clear();
    }
}
