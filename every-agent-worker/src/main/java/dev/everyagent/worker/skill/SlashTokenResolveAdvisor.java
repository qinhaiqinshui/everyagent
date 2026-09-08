package dev.everyagent.worker.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import dev.everyagent.worker.slash.SlashTokenHandler;

/**
 * 解析用户消息里的结构化 token(老项目 {@code replaceComposerTokensForSubmission}
 * 的 worker 侧等价物,「解析都在后端」)。
 *
 * <p>本 advisor 只负责「扫描正文 + 按 kind 分发」:逐段识别 inline opaque token,
 * 调用 {@link SlashTokenHandler#resolve(String)} 按 kind 解析为提交文本(未知 kind /
 * 解析失败保留原串)。具体的 kind→文本 映射由各来源自管的 {@code SlashTokenResolver}
 * 声明(如 skill 解析为技能名、git.auto_sync 解析为空串),核心层零业务分支——
 * 一个 advisor 只做一个功能(红线)。
 *
 * <p>顺序:位于 {@link SkillAdvisor}(HIGHEST_PRECEDENCE + 100)之后,只改写 user
 * 消息、不动 system 区;无状态,可安全共享。
 */
public class SlashTokenResolveAdvisor implements BaseAdvisor {

    /** opaque token 边界(4 连符号定界,与 SlashTokenEncoder 一致)。 */
    private static final Pattern TOKEN_RE = Pattern.compile("\\[\\[\\[\\[[\\s\\S]*?\\]\\]\\]\\]");

    private final SlashTokenHandler handler;

    public SlashTokenResolveAdvisor(SlashTokenHandler handler) {
        this.handler = handler;
    }

    @Override
    public String getName() {
        return "Slash Token Resolve Advisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 150;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        List<Message> instructions = chatClientRequest.prompt().getInstructions();
        boolean changed = false;
        List<Message> out = new ArrayList<>(instructions.size());
        for (Message m : instructions) {
            if (m instanceof UserMessage um) {
                String text = um.getText();
                if (text == null) {
                    out.add(m);
                    continue;
                }
                String replaced = replaceTokens(text);
                if (!replaced.equals(text)) {
                    changed = true;
                    out.add(new UserMessage(replaced));
                } else {
                    out.add(m);
                }
            } else {
                out.add(m);
            }
        }
        if (!changed) {
            return chatClientRequest;
        }
        Prompt newPrompt = new Prompt(out, chatClientRequest.prompt().getOptions());
        return chatClientRequest.mutate().prompt(newPrompt).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    /**
     * 把正文里的 opaque token 替换为提交文本(统一交 {@link SlashTokenHandler} 按 kind 分发);
     * 非 opaque 格式 / 未知 kind / 解析失败 → 保留原串(老项目兜底行为)。
     */
    private String replaceTokens(String content) {
        Matcher matcher = TOKEN_RE.matcher(content);
        if (!matcher.find()) {
            return content;
        }
        matcher.reset();
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String opaque = matcher.group();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(handler.resolve(opaque)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
