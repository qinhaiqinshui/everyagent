package dev.everyagent.worker.task;

import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * UnattendedModeAdvisor 单测(plan-unattended-ai-auth 步骤 8):order 位与
 * {@code ToolCallingAdvisor.DEFAULT_ORDER - 100} 一致;unattended=true 时一次改写
 * 同时完成「注入无人值守 SystemMessage」+「剥离 ask_user 工具(其余工具保留)」;
 * unattended=false 时原样返回同一请求(不注入、不过滤)。模型调用为 mock ChatModel,
 * 不触网。工具用真实 {@code ToolCallbacks.from} 包装的 fake 工具(name 取 ask_user/bash)。
 */
class UnattendedModeAdvisorTest {

    private final TaskEntry task = newTask();
    private final AgentEntity agent = new AgentEntity(task, "main-agent", AgentEntity.Kind.MAIN, "主",
            mock(ChatModel.class), OpenAiChatOptions.builder().model("m").build(), List.of());
    private final UnattendedModeAdvisor advisor = new UnattendedModeAdvisor(agent);

    private static TaskEntry newTask() {
        ModelSnapshot snap = new ModelSnapshot("cfg", "openai-compat",
                "http://localhost:9999/v1", "m", null);
        return new TaskEntry("t-1", "任务", snap, "k", "ws", "main-agent", 10_000);
    }

    // ---- order ----

    @Test
    void orderIsToolCallingDefaultMinus100() {
        assertEquals(ToolCallingAdvisor.DEFAULT_ORDER - 100, advisor.getOrder(),
                "无人值守 advisor 应排在 ToolCallingAdvisor 之前 100 位");
    }

    // ---- unattended=true:注入提示词 + 剥离 ask_user ----

    @Test
    void beforeUnattendedOnInjectsSystemPromptAfterLeadingSystemArea() {
        task.unattended = true;
        ChatClientRequest request = requestWithTools(realTools());

        ChatClientRequest out = advisor.before(request, mock(AdvisorChain.class));

        List<Message> instructions = out.prompt().getInstructions();
        int idx = indexOfUnattendedPrompt(instructions);
        assertTrue(idx >= 0, "应注入无人值守提示词");
        assertTrue(instructions.get(idx) instanceof SystemMessage, "注入的是 SystemMessage");
        assertTrue(idx >= 1, "注入位置应在首部连续 SystemMessage 区之后");
        // 首条原 system 仍在、原 user 仍在最末
        assertTrue(instructions.get(0) instanceof SystemMessage, "原首部 system 保留在最前");
        assertEquals("你好", ((UserMessage) instructions.get(instructions.size() - 1)).getText(),
                "原 user 消息保持末位");
    }

    @Test
    void beforeUnattendedOnStripsAskUserAndKeepsOtherTools() {
        task.unattended = true;
        List<ToolCallback> tools = realTools();
        ChatClientRequest out = advisor.before(requestWithTools(tools), mock(AdvisorChain.class));

        ToolCallingChatOptions opts = (ToolCallingChatOptions) out.prompt().getOptions();
        List<ToolCallback> cbs = opts.getToolCallbacks();
        assertTrue(cbs.stream().noneMatch(c -> "ask_user".equals(c.getToolDefinition().name())),
                "无人值守时 options.toolCallbacks 不得含 ask_user");
        assertTrue(cbs.stream().anyMatch(c -> "bash".equals(c.getToolDefinition().name())),
                "无人值守时其它工具(如 bash)应保留: " + names(cbs));
    }

    @Test
    void beforeUnattendedOnWithPlainChatOptionsOnlyInjectsPrompt() {
        task.unattended = true;
        ChatOptions plain = mock(ChatOptions.class);
        Prompt prompt = new Prompt(new ArrayList<>(List.of(new UserMessage("hi"))), plain);
        ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

        ChatClientRequest out = advisor.before(request, mock(AdvisorChain.class));

        assertTrue(indexOfUnattendedPrompt(out.prompt().getInstructions()) >= 0, "非工具 options 也应注入提示词");
        assertSame(plain, out.prompt().getOptions(), "非 ToolCallingChatOptions 的 options 原样保留");
    }

    // ---- unattended=false:原样返回 ----

    @Test
    void beforeUnattendedOffReturnsSameRequestUnchanged() {
        task.unattended = false;
        ChatClientRequest request = requestWithTools(realTools());

        ChatClientRequest out = advisor.before(request, mock(AdvisorChain.class));

        assertSame(request, out, "无人值守关闭时应原样返回同一请求");
        assertTrue(indexOfUnattendedPrompt(out.prompt().getInstructions()) < 0, "关闭时不注入提示词");
        ToolCallingChatOptions opts = (ToolCallingChatOptions) out.prompt().getOptions();
        assertTrue(opts.getToolCallbacks().stream()
                        .anyMatch(c -> "ask_user".equals(c.getToolDefinition().name())),
                "关闭时不剥离 ask_user: " + names(opts.getToolCallbacks()));
    }

    // ---- 辅助 ----

    private static ChatClientRequest requestWithTools(List<ToolCallback> tools) {
        List<Message> instructions = new ArrayList<>();
        instructions.add(new SystemMessage("你是助手"));
        instructions.add(new UserMessage("你好"));
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .model("m")
                .toolCallbacks(tools)
                .build();
        return ChatClientRequest.builder().prompt(new Prompt(instructions, options)).build();
    }

    /** 真实工具:method 名即工具名(与 AskUserTool.ask_user 口径一致)——ask_user 待剥离、bash 保留。 */
    private static List<ToolCallback> realTools() {
        return new ArrayList<>(List.of(ToolCallbacks.from(new FakeTools())));
    }

    private static int indexOfUnattendedPrompt(List<Message> instructions) {
        for (int i = 0; i < instructions.size(); i++) {
            Message m = instructions.get(i);
            if (m instanceof SystemMessage sm && sm.getText() != null
                    && sm.getText().contains("无人值守模式")
                    && sm.getText().contains("按你推荐的实现即可")) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> names(List<ToolCallback> cbs) {
        List<String> out = new ArrayList<>();
        for (ToolCallback c : cbs) {
            out.add(c.getToolDefinition() == null ? "<null>" : c.getToolDefinition().name());
        }
        return out;
    }

    static final class FakeTools {

        @Tool(description = "向用户提问")
        public String ask_user(String question) {
            return "answered";
        }

        @Tool(description = "执行命令")
        public String bash(String command) {
            return "ok";
        }
    }
}