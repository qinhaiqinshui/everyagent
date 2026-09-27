package dev.everyagent.plugin.modelratelimit;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;

/**
 * 模型限流插件入口。
 *
 * <p>骨架阶段:仅实现 Plugin 接口,activate() 为空。
 * 步骤 4 将搬迁限流逻辑,在此注册 RateLimitNode（ModelRequestNode）。
 */
public class ModelRateLimitPlugin implements EveryAgentPlugin {

    @Override
    public String id() {
        return "model-rate-limit";
    }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        // 步骤 4:搬迁限流逻辑后,在此注册 RateLimitNode
        // ctx.registerModelRequestNode(new RateLimitNode(...));
    }
}
