package dev.everyagent.worker.skill;

import java.util.ArrayList;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import java.util.List;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.worker.slash.SlashTokenHandler;

/**
 * 解析用户消息里的结构化 token(老项目 {@code replaceComposerTokensForSubmission}
 * 的 worker 侧等价物,「解析都在后端」)。
 *
 * <p>本 advisor 只负责「扫描正文 + 按 kind 分发」:逐段识别 inline opaque token,
 * 调用 {@link SlashTokenHandler#resolve(String, ExecContext)} 按 kind 解析为提交文本
 * (透传构造期绑定的任务上下文,供任务感知 kind——如 {@code system.external_file}
 * 注册外部授权根——使用;未知 kind / 解析失败 / resolver 返回 null 保留原串)。
 * 具体的 kind→文本 映射由各来源自管的 {@code SlashTokenResolver}
 * 声明(如 skill 解析为技能名、git.auto_sync 解析为空串),核心层零业务分支——
 * 一个 advisor 只做一个功能(红线)。
 *
 * <p>段扫描走 {@link SlashTokenEncoder#scanTokens}(深度平衡配对,嵌套 token——
 * 胶囊 label 内嵌另一完整 token 串——整体成段,不被内层 {@code }]]]} 截断)。
 *
 * <p>顺序:位于 {@link SkillAdvisor}(HIGHEST_PRECEDENCE + 100)之后,只改写 user
 * 消息、不动 system 区;无请求级状态,可安全共享(持有 ExecContext 只读引用,
 * 每 run 由 {@code AgentClientFactory} 新建实例,任务生命周期内安全)。
 */
public class SlashTokenResolveAdvisor implements BaseAdvisor {

    private final SlashTokenHandler handler;
    /** 执行上下文(只读引用):任务感知 kind(system.external_file)解析时消费。 */
    private final ExecContext exec;

    public SlashTokenResolveAdvisor(SlashTokenHandler handler, ExecContext exec) {
        this.handler = handler;
        this.exec = exec;
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
     * 把正文里的 opaque token 替换为提交文本(统一交 {@link SlashTokenHandler} 按 kind
     * 分发,透传任务上下文);非 opaque 格式 / 未知 kind / 解析失败 / resolver 返回 null
     * → 保留原串(老项目兜底行为)。
     *
     * <p>2026-11 前用非贪婪正则 {@code \[\[\[\[[\s\S]*?\]\]\]\]},嵌套 token(如「等待任务」
     * 胶囊 label 内嵌目标任务标题的 token 串)会在内层首个 {@code }]]]} 处截断,外层被
     * 当成非法串保留原样进模型;改用 {@link SlashTokenEncoder#scanTokens} 平衡扫描后,
     * 嵌套外层整体命中、由 kind 分发正常解析。
     */
    private String replaceTokens(String content) {
        List<String> tokens = SlashTokenEncoder.scanTokens(content);
        if (tokens.isEmpty()) {
            return content;
        }
        // 长度降序替换:嵌套场景外层段必然比内层串长,先替换外层即随之外层消解,
        // 防止短的内层串先替换掉外层 label 里的同串、破坏外层锚点
        tokens.sort((a, b) -> Integer.compare(b.length(), a.length()));
        String result = content;
        for (String opaque : tokens) {
            result = result.replace(opaque, handler.resolve(opaque, exec));
        }
        return result;
    }
}
