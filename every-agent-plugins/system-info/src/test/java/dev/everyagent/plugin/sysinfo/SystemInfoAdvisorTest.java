package dev.everyagent.plugin.sysinfo;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * SystemInfoAdvisor 单测:order = HIGHEST_PRECEDENCE + 50;
 * before 注入「# 身份 + # 工作区 + 当前操作系统」SystemMessage,插入位置在首部连续
 * SystemMessage 区之后;末位 user 消息保持末位。
 */
class SystemInfoAdvisorTest {

    @Test
    void orderIsHighestPlus50() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 50,
                new SystemInfoAdvisor("ws", false, false).getOrder());
    }

    @Test
    void beforeInjectsSystemMessageAfterLeadingSystemArea() {
        ChatClientRequest out = new SystemInfoAdvisor("/c/test", false, false)
                .before(requestWith("你是助手", "你好"), mock(AdvisorChain.class));

        List<Message> instructions = out.prompt().getInstructions();
        // 首条仍是原 system
        assertTrue(instructions.get(0) instanceof SystemMessage, "原首部 system 保留在最前");
        // 末条仍是原 user
        assertTrue(instructions.get(instructions.size() - 1) instanceof UserMessage,
                "原 user 消息保持末位");
        // 注入了至少一条额外的 SystemMessage
        long systemCount = instructions.stream().filter(m -> m instanceof SystemMessage).count();
        assertTrue(systemCount >= 2, "应注入至少一条额外 SystemMessage");
    }

    @Test
    void beforeWithNullWorkspaceStillInjectsIdentity() {
        ChatClientRequest out = new SystemInfoAdvisor(null, false, false)
                .before(requestWith("你是助手", "你好"), mock(AdvisorChain.class));

        List<Message> instructions = out.prompt().getInstructions();
        // 即使 workspaceRoot 为 null,身份信息仍注入
        boolean hasIdentity = instructions.stream()
                .filter(m -> m instanceof SystemMessage)
                .map(m -> ((SystemMessage) m).getText())
                .anyMatch(t -> t != null && t.contains("# 身份"));
        assertTrue(hasIdentity, "null workspaceRoot 时仍应注入身份信息");
    }

    @Test
    void beforeInjectsWorkspaceWhenProvided() {
        ChatClientRequest out = new SystemInfoAdvisor("/c/myproject", false, false)
                .before(requestWith("你是助手", "你好"), mock(AdvisorChain.class));

        List<Message> instructions = out.prompt().getInstructions();
        boolean hasWorkspace = instructions.stream()
                .filter(m -> m instanceof SystemMessage)
                .map(m -> ((SystemMessage) m).getText())
                .anyMatch(t -> t != null && t.contains("# 工作区"));
        assertTrue(hasWorkspace, "应注入工作区信息");
    }

    @Test
    void beforeInjectsWslLinuxOsWhenWslBackend() {
        ChatClientRequest out = new SystemInfoAdvisor("/c/myproject", true, false)
                .before(requestWith("你是助手", "你好"), mock(AdvisorChain.class));

        List<Message> instructions = out.prompt().getInstructions();
        boolean hasLinuxOs = instructions.stream()
                .filter(m -> m instanceof SystemMessage)
                .map(m -> ((SystemMessage) m).getText())
                .anyMatch(t -> t != null && t.contains("Linux"));
        assertTrue(hasLinuxOs, "wsl 后端时应注入 Linux 操作系统信息");
    }

    // ---- 辅助 ----

    private static ChatClientRequest requestWith(String systemText, String userText) {
        List<Message> instructions = new ArrayList<>();
        instructions.add(new SystemMessage(systemText));
        instructions.add(new UserMessage(userText));
        return ChatClientRequest.builder().prompt(new Prompt(instructions, mock(ChatOptions.class))).build();
    }
}
