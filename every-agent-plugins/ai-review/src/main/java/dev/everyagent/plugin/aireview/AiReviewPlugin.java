package dev.everyagent.plugin.aireview;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.slash.SlashTokenResolver;
import dev.everyagent.worker.agent.AgentBuilder;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.ConfigStore;
import dev.everyagent.worker.task.ChatModelFactory;

/**
 * AI 审议插件入口。
 *
 * <p>activate() 中实例化 AiAuthReviewer，注册 AiReviewAuthHandler、
 * AiReviewSlashProvider、AiReviewSlashResolver。
 */
public class AiReviewPlugin implements EveryAgentPlugin {

    @Override
    public String id() { return "ai-review"; }

    @Override
    public void activate(WorkerPluginContext ctx) {
        WorkerProperties props = ctx.getService(WorkerProperties.class);
        ConfigStore configStore = ctx.getService(ConfigStore.class);
        ChatModelFactory chatModelFactory = ctx.getService(ChatModelFactory.class);
        AgentBuilder agentBuilder = ctx.getService(AgentBuilder.class);

        // 1. 实例化 AI 审议器
        AiAuthReviewer reviewer = new AiAuthReviewer(props, configStore, chatModelFactory,
                agentBuilder);

        // 2. 注册授权链节点
        ctx.registerAuthorizationHandler(new AiReviewAuthHandler(reviewer));

        // 3. 注册 /AI 审议 命令提供者
        ctx.registerSlashProvider("ai-review", () ->
                AiReviewSlashProvider.items(ctx.services()));

        // 4. 注册 token 提交解析器
        ctx.registerSlashTokenResolver(new AiReviewSlashResolver());
    }
}
