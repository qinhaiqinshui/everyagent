package dev.everyagent.worker.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * AgentsMdAdvisor 单测:order 位介于 SystemInfoAdvisor(+50) 与 SkillAdvisor(+100) 之间;
 * 工作区根存在 agents.md(忽略大小写)时注入「头部 + 原文」SystemMessage,插入位置在首部
 * 连续 SystemMessage 区之后;文件不存在 / 工作区缺失时原样返回同一请求。
 */
class AgentsMdAdvisorTest {

    @TempDir
    Path ws;

    private static final String HEADER_PREFIX = "# agents.md\n以下内容来自工作区根目录agents.md,你需要严格遵守其中约束。\n";

    // ---- order ----

    @Test
    void orderIsBetweenSystemInfoAndSkill() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 60, new AgentsMdAdvisor("ws").getOrder(),
                "agents.md advisor 应排在 SystemInfoAdvisor(+50) 之后、SkillAdvisor(+100) 之前");
    }

    // ---- 注入(小写文件名) ----

    @Test
    void beforeInjectsHeaderPlusContentAfterLeadingSystemArea() throws IOException {
        String body = "1. 禁止删库\n2. 提交前跑测试\n";
        Files.writeString(ws.resolve("agents.md"), body);

        ChatClientRequest out = new AgentsMdAdvisor(ws.toString())
                .before(requestWith("你是助手", "你好"), mock(AdvisorChain.class));

        List<Message> instructions = out.prompt().getInstructions();
        int idx = indexOfAgentsMd(instructions);
        assertTrue(idx >= 0, "应注入 agents.md SystemMessage");
        assertTrue(instructions.get(idx) instanceof SystemMessage, "注入的是 SystemMessage");
        assertEquals(HEADER_PREFIX + body, ((SystemMessage) instructions.get(idx)).getText(),
                "注入内容 = 固定头部 + 文件原文");
        // 首条原 system 保留在最前、原 user 保持末位
        assertTrue(instructions.get(0) instanceof SystemMessage, "原首部 system 保留在最前");
        assertTrue(idx >= 1, "注入位置应在首部连续 SystemMessage 区之后");
        assertEquals("你好", ((UserMessage) instructions.get(instructions.size() - 1)).getText(),
                "原 user 消息保持末位");
    }

    // ---- 大小写不敏感 ----

    @Test
    void beforeMatchesFileNameIgnoringCase() throws IOException {
        String body = "AGENTS.md 大写约束\n";
        Files.writeString(ws.resolve("AGENTS.md"), body);

        ChatClientRequest out = new AgentsMdAdvisor(ws.toString())
                .before(requestWith("你是助手", "你好"), mock(AdvisorChain.class));

        assertTrue(indexOfAgentsMd(out.prompt().getInstructions()) >= 0, "AGENTS.md 应被大小写不敏感命中");
        List<Message> instructions = out.prompt().getInstructions();
        int idx = indexOfAgentsMd(instructions);
        assertEquals(HEADER_PREFIX + body, ((SystemMessage) instructions.get(idx)).getText(),
                "命中大写文件时内容为文件原文");
    }

    // ---- 文件不存在:原样返回 ----

    @Test
    void beforeWithoutFileReturnsSameRequest() {
        ChatClientRequest request = requestWith("你是助手", "你好");

        ChatClientRequest out = new AgentsMdAdvisor(ws.toString())
                .before(request, mock(AdvisorChain.class));

        assertSame(request, out, "工作区无 agents.md 时应原样返回同一请求");
    }

    // ---- 工作区缺失/空白:原样返回 ----

    @Test
    void beforeWithBlankWorkspaceReturnsSameRequest() {
        ChatClientRequest request = requestWith("你是助手", "你好");

        assertSame(request, new AgentsMdAdvisor("").before(request, mock(AdvisorChain.class)),
                "空白 workspaceRoot 时应原样返回");
        assertSame(request, new AgentsMdAdvisor(null).before(request, mock(AdvisorChain.class)),
                "null workspaceRoot 时应原样返回");
    }

    // ---- 辅助 ----

    private static ChatClientRequest requestWith(String systemText, String userText) {
        List<Message> instructions = new ArrayList<>();
        instructions.add(new SystemMessage(systemText));
        instructions.add(new UserMessage(userText));
        return ChatClientRequest.builder().prompt(new Prompt(instructions, mock(ChatOptions.class))).build();
    }

    private static int indexOfAgentsMd(List<Message> instructions) {
        for (int i = 0; i < instructions.size(); i++) {
            Message m = instructions.get(i);
            if (m instanceof SystemMessage sm && sm.getText() != null
                    && sm.getText().startsWith(HEADER_PREFIX)) {
                return i;
            }
        }
        return -1;
    }
}
