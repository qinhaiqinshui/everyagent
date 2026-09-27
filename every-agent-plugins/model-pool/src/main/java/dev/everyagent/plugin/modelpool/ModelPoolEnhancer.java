package dev.everyagent.plugin.modelpool;

import dev.everyagent.plugin.api.model.ChatModelEnhancer;
import dev.everyagent.plugin.api.model.EnhancedChatModel;
import dev.everyagent.plugin.api.model.EnhancerContext;
import dev.everyagent.plugin.api.model.MemberSpec;
import dev.everyagent.plugin.api.model.ModelConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型池 ChatModel 增强器。
 *
 * <p>检测 provider=model-pool 时，用 ctx.buildMember() 逐成员构建 ChatModel，
 * 组装为 ModelPoolChatModel（按序容灾切换），返回 EnhancedChatModel。
 *
 * <p>成员的 OpenAiChatOptions 从 buildMember() 返回的 ChatModel.getOptions() 获取：
 * OpenAiChatModel.getOptions() 返回构建时使用的 OpenAiChatOptions。
 */
public class ModelPoolEnhancer implements ChatModelEnhancer {

    @Override
    public String id() {
        return "model-pool";
    }

    @Override
    public boolean supports(String provider) {
        return "model-pool".equals(provider);
    }

    @Override
    public EnhancedChatModel enhance(EnhancerContext ctx) {
        List<MemberSpec> members = ctx.members();
        List<ChatModel> chatModels = new ArrayList<>(members.size());
        List<OpenAiChatOptions> memberOptions = new ArrayList<>(members.size());
        List<ModelConfig> memberSnapshots = new ArrayList<>(members.size());

        for (MemberSpec m : members) {
            ChatModel model = ctx.buildMember(m);
            chatModels.add(model);
            ChatOptions opts = model.getOptions();
            memberOptions.add(opts instanceof OpenAiChatOptions o ? o : OpenAiChatOptions.builder().build());
            memberSnapshots.add(m.config());
        }

        ModelPoolChatModel pool = new ModelPoolChatModel(
                chatModels, memberOptions, memberSnapshots, ctx.events(), ctx.agentId());

        return new EnhancedChatModel(pool, memberOptions.isEmpty()
                ? OpenAiChatOptions.builder().build() : memberOptions.get(0));
    }
}
