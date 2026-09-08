package dev.everyagent.worker.task;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 LLM 的上下文要点摘要器,实现 {@link ContextSummarizer}。
 *
 * <p>职责:把将被丢弃的历史对话 / 工具过程压成一段短要点摘要,供 {@link ContextCompressor}
 * 阶段 C(删除最旧历史轮)前调用,以「不进上下文即丢弃」为兜底、以「保留关键信息」为目标。
 *
 * <p>实现约定:<ul>
 *   <li>同步、幂等、线程安全:仅持有 final 的 {@link ChatModel} 与默认 maxTokens,无其他可变共享状态;</li>
 *   <li>任何异常(模型错误 / 超时 / 空响应 / 中断)都返回空串,绝不抛出、绝不影响主流程;</li>
 *   <li>{@code maxTokens <= 0} 时回退到实例默认值。</li>
 * </ul>
 */
public class LlmContextSummarizer implements ContextSummarizer {

    /**
     * 固定 system 提示词:要求压缩为要点摘要,保留关键事实/路径/结论/约束/待办,
     * 去除冗余与寒暄,且不编造不存在的内容。
     */
    private static final String SYSTEM_PROMPT = "你是上下文压缩器。把用户提供的历史对话/工具过程压缩成要点摘要,"
            + "保留关键事实、路径、结论、约束、待办,去除冗余与寒暄;不要编造不存在的内容。";

    /** 当前 agent 的同步 ChatModel(构造时注入,摘要默认复用该模型)。 */
    private final ChatModel chatModel;

    /** 摘要默认输出 token 上限(估算口径)。 */
    private final int maxTokens;

    /**
     * @param chatModel 用于同步生成摘要的模型(复用当前 agent 的 ChatModel)
     * @param maxTokens 默认摘要输出 token 上限
     */
    public LlmContextSummarizer(ChatModel chatModel, int maxTokens) {
        this.chatModel = chatModel;
        this.maxTokens = maxTokens;
    }

    /**
     * 把一段历史文本压缩成要点摘要。
     *
     * @param text      待压缩文本
     * @param maxTokens 摘要 token 上限(估算口径);<=0 时使用实例默认上限
     * @return 短摘要;失败 / 不适用返回空串(调用方降级)
     */
    @Override
    public String summarize(String text, int maxTokens) {
        try {
            if (text == null || text.isBlank()) {
                return "";
            }
            int effMax = maxTokens > 0 ? maxTokens : this.maxTokens;
            String instruction = effMax > 0
                    ? "请生成不超过 " + effMax + " token 的要点摘要。"
                    : "请生成简洁的要点摘要。";

            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(SYSTEM_PROMPT));
            messages.add(new UserMessage(text + "\n" + instruction));

            // 稳健优先:Spring AI 2.0.1 中 prompt 携带 options 会原样透传并替换模型默认
            // options,若只设置 maxTokens 会丢失 baseUrl/apiKey/model/超时等完整快照,
            // 故输出规模限制以指令文本为主,不额外注入局部 OpenAiChatOptions。
            ChatResponse response = chatModel.call(new Prompt(messages));
            if (response == null || response.getResult() == null
                    || response.getResult().getOutput() == null) {
                return "";
            }
            String content = response.getResult().getOutput().getText();
            return content == null ? "" : content.trim();
        } catch (Exception e) {
            // 摘要失败 / 超时 / 模型异常 / 中断一律降级为空串,不 rethrow、不吞掉后再抛,
            // 由调用方降级为确定性保留(如保留 user 原句简版)。
            return "";
        }
    }
}
