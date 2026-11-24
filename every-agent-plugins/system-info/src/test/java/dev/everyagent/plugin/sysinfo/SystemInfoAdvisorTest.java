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
                new SystemInfoAdvisor("ws", null).getOrder());
    }

    @Test
    void beforeInjectsSystemMessageAfterLeadingSystemArea() {
        ChatClientRequest out = new SystemInfoAdvisor("/c/test", null)
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
        ChatClientRequest out = new SystemInfoAdvisor(null, null)
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
        ChatClientRequest out = new SystemInfoAdvisor("/c/myproject", null)
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
        ChatClientRequest out = new SystemInfoAdvisor("/c/myproject", "wsl-ubuntu")
                .before(requestWith("你是助手", "你好"), mock(AdvisorChain.class));

        List<Message> instructions = out.prompt().getInstructions();
        boolean hasLinuxOs = instructions.stream()
                .filter(m -> m instanceof SystemMessage)
                .map(m -> ((SystemMessage) m).getText())
                .anyMatch(t -> t != null && t.contains("Linux"));
        assertTrue(hasLinuxOs, "wsl 后端时应注入 Linux 操作系统信息");
    }

    @Test
    void directBackendToolHintOmitsSandboxWording() {
        // DIRECT(无沙箱)后端:命令直接跑宿主,文案不得出现「沙箱」(谎报隔离层),
        // 应表达「直接放行」;null 归一为 direct,同口径。
        for (String id : new String[] {"direct", null}) {
            ChatClientRequest out = new SystemInfoAdvisor("/c/myproject", id)
                    .before(requestWith("你是助手", "你好"), mock(AdvisorChain.class));

            String text = joinedSystemText(out);
            assertTrue(!text.contains("沙箱"),
                    "DIRECT 后端(id=" + id + ")注入文案不应出现「沙箱」: " + text);
            assertTrue(text.contains("直接在宿主机上执行命令"),
                    "DIRECT 后端应声明命令直接在宿主机执行: " + text);
            assertTrue(text.contains("直接放行"),
                    "DIRECT 后端应声明工作区内读写直接放行: " + text);
        }
    }

    @Test
    void sandboxBackendKeepsSandboxWording() {
        // 真有隔离层的后端(如 windows-mic):保留「沙箱」原文案,不被 DIRECT 分支误删。
        ChatClientRequest out = new SystemInfoAdvisor("/c/myproject", "windows-mic")
                .before(requestWith("你是助手", "你好"), mock(AdvisorChain.class));

        String text = joinedSystemText(out);
        assertTrue(text.contains("工具会在沙箱中执行"),
                "沙箱后端应保留「会在沙箱中执行」提示: " + text);
    }

    // ---- 辅助 ----

    private static String joinedSystemText(ChatClientRequest request) {
        StringBuilder sb = new StringBuilder();
        request.prompt().getInstructions().stream()
                .filter(m -> m instanceof SystemMessage)
                .forEach(m -> sb.append(((SystemMessage) m).getText()).append('\n'));
        return sb.toString();
    }

    private static ChatClientRequest requestWith(String systemText, String userText) {
        List<Message> instructions = new ArrayList<>();
        instructions.add(new SystemMessage(systemText));
        instructions.add(new UserMessage(userText));
        return ChatClientRequest.builder().prompt(new Prompt(instructions, mock(ChatOptions.class))).build();
    }
}
