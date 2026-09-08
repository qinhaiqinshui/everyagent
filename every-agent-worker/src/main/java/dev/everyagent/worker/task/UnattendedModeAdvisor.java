package dev.everyagent.worker.task;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * 无人值守模式主/子 Agent 行为 advisor(一个 advisor 只负责一个功能,红线):
 * 任务级 {@code TaskEntry.unattended}=true 时,一次 before 同时完成
 * <ol>
 *   <li>从请求 {@code ToolCallingChatOptions.toolCallbacks} 中过滤掉 {@code ask_user}
 *       (模型不可见,无人值守无可问之人,避免 AI 发起 question 挂起);</li>
 *   <li>向 instructions 首部系统区注入 SystemMessage「当下处于无人值守模式,如果有疑问,
 *       按你推荐的实现即可。」。</li>
 * </ol>
 * false 时原样返回(不剥离、不注入)。每轮 before 实时读 {@code a.task.unattended}
 * (volatile,任务运行中用户点胶囊开/关即时生效,非构造期冻结)。
 *
 * <p>order = {@code ToolCallingAdvisor.DEFAULT_ORDER - 100}(即 HIGHEST_PRECEDENCE + 200):
 * 在 SlashTokenResolveAdvisor(+150) 之后、LoopRepeatGuard(+300)/工具循环之前。已核实
 * {@code ToolCallingAdvisor} 源码——递归轮 {@code internalStream} 复用入口处同一
 * {@code ToolCallingChatOptions} 引用(过滤全轮保持),递归轮 instructions=完整
 * conversationHistory(含初始注入的 system 区,提示词全轮保持),不重新经过外层 advisor
 * 的 before,因此本 advisor 单次改写即可全轮生效,无需每轮重注。
 *
 * <p>主/子 agent 同挂:子 agent 也注册 ask_user,无人值守时同样不可提问。
 * AI 审议开关({@code aiReview})本身不影响 ask_user 可见性——只有 unattended=true 才剥离。
 */
public class UnattendedModeAdvisor implements BaseAdvisor {

    /** 需从工具定义中剥离的工具名(AskUserTool 的 Spring AI 工具名 = 方法名 ask_user)。 */
    private static final String ASK_USER_TOOL_NAME = "ask_user";

    /** 无人值守模式下注入系统指令区的提示词。 */
    private static final String UNATTENDED_SYSTEM_PROMPT = "当下处于无人值守模式，如果有疑问，按你推荐的实现即可。";

    private final AgentEntity a;

    public UnattendedModeAdvisor(AgentEntity a) {
        this.a = a;
    }

    @Override
    public String getName() {
        return "Unattended Mode Advisor";
    }

    @Override
    public int getOrder() {
        // ToolCallingAdvisor.DEFAULT_ORDER = HIGHEST_PRECEDENCE + 300;此处 = HIGHEST_PRECEDENCE + 200。
        return ToolCallingAdvisor.DEFAULT_ORDER - 100;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!a.task.unattended) {
            return request; // 无人值守关闭:原样返回,不做任何改写
        }
        List<Message> instructions = new ArrayList<>(request.prompt().getInstructions());
        // 注入位置:首部连续 SystemMessage 区之后(与 SystemInfoAdvisor 一致,不落会话末位)。
        int insertAt = 0;
        while (insertAt < instructions.size() && instructions.get(insertAt) instanceof SystemMessage) {
            insertAt++;
        }
        instructions.add(insertAt, new SystemMessage(UNATTENDED_SYSTEM_PROMPT));

        ChatOptions options = request.prompt().getOptions();
        ChatOptions filteredOptions = options;
        if (options instanceof ToolCallingChatOptions toolCallingOptions) {
            List<ToolCallback> filtered = new ArrayList<>();
            List<ToolCallback> callbacks = toolCallingOptions.getToolCallbacks();
            if (callbacks != null) {
                for (ToolCallback callback : callbacks) {
                    if (callback.getToolDefinition() != null
                            && !ASK_USER_TOOL_NAME.equals(callback.getToolDefinition().name())) {
                        filtered.add(callback);
                    }
                }
            }
            // 用现有 options 的 mutate() 拷贝(保留 baseUrl/apiKey/model/采样参数等),
            // 仅把 toolCallbacks 替换为过滤后的列表。
            filteredOptions = toolCallingOptions.mutate().toolCallbacks(filtered).build();
        }
        Prompt newPrompt = new Prompt(instructions, filteredOptions);
        return request.mutate().prompt(newPrompt).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }
}
