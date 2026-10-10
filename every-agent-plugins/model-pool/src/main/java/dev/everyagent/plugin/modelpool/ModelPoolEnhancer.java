package dev.everyagent.plugin.modelpool;

import dev.everyagent.plugin.api.model.ChatModelEnhancer;
import dev.everyagent.plugin.api.model.EnhancedChatModel;
import dev.everyagent.plugin.api.model.EnhancerContext;
import dev.everyagent.plugin.api.model.MemberSpec;
import dev.everyagent.plugin.api.model.ModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型池 ChatModel 增强器。
 *
 * <p>检测 provider=model-pool 时，自行解析 poolConfig().model() 逗号串得到成员 configId 列表，
 * 逐个 resolveMember + buildMember 构建 ChatModel，组装为 ModelPoolChatModel（按序容灾切换），
 * 返回 EnhancedChatModel。
 *
 * <p>成员的 OpenAiChatOptions 从 buildMember() 返回的 ChatModel.getOptions() 获取：
 * OpenAiChatModel.getOptions() 返回构建时使用的 OpenAiChatOptions。
 */
public class ModelPoolEnhancer implements ChatModelEnhancer {

    private static final Logger log = LoggerFactory.getLogger(ModelPoolEnhancer.class);

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
        // 解析 poolConfig().model() 逗号串（trim/去空/去重/顺序保持）
        String rawModel = ctx.poolConfig().model();
        if (rawModel == null || rawModel.isBlank()) {
            throw new IllegalStateException("model-pool 配置缺 model（逗号分隔的成员 configId 列表）");
        }
        List<String> memberIds = new ArrayList<>();
        for (String part : rawModel.split(",")) {
            String id = part.trim();
            if (id.isEmpty()) {
                continue;
            }
            if (!memberIds.contains(id)) {  // 去重，保持顺序
                memberIds.add(id);
            }
        }

        // 逐个 resolveMember：null 跳过 + error 日志；禁套池；全空 → 抛异常
        List<ChatModel> chatModels = new ArrayList<>();
        List<OpenAiChatOptions> memberOptions = new ArrayList<>();
        List<ModelConfig> memberSnapshots = new ArrayList<>();

        for (String id : memberIds) {
            MemberSpec spec = ctx.resolveMember(id);
            if (spec == null) {
                log.error("[model-pool] 成员 configId 不存在，已跳过: {}", id);
                continue;
            }
            // 禁套池：成员 provider 是本插件 id（组合型）→ 抛异常
            String memberProvider = spec.config().provider();
            if ("model-pool".equals(memberProvider)) {
                throw new IllegalStateException("model-pool 成员不得是池配置（禁池套池）: " + id);
            }
            ChatModel model = ctx.buildMember(spec);
            chatModels.add(model);
            ChatOptions opts = model.getOptions();
            memberOptions.add(opts instanceof OpenAiChatOptions o ? o : OpenAiChatOptions.builder().build());
            memberSnapshots.add(spec.config());
        }

        if (chatModels.isEmpty()) {
            throw new IllegalStateException("model-pool 配置无有效成员（全部缺失或无效）");
        }

        ModelPoolChatModel pool = new ModelPoolChatModel(
                chatModels, memberOptions, memberSnapshots, ctx.events(), ctx.agentId());

        return new EnhancedChatModel(pool, memberOptions.get(0));
    }
}
