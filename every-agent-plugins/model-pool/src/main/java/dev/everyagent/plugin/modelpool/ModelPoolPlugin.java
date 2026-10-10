package dev.everyagent.plugin.modelpool;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模型池容灾插件入口。
 *
 * <p>activate() 中注册 {@link ModelPoolEnhancer}——当 ChatModelFactory 遇到
 * {@code provider=model-pool} 配置时，经 ChatModelEnhancer SPI 委托本插件
 * 构建组合 ChatModel（ModelPoolChatModel，按序容灾切换）。
 */
public class ModelPoolPlugin implements EveryAgentPlugin {

    private static final Logger log = LoggerFactory.getLogger(ModelPoolPlugin.class);

    @Override
    public String id() {
        return "model-pool";
    }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        ctx.registerChatModelEnhancer(new ModelPoolEnhancer());
        log.info("[model-pool] 已注册 ModelPoolEnhancer");
    }
}
