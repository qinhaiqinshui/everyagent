package dev.everyagent.plugin.askuser;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用户提问插件入口：向 agent 提供 ask_user 工具，支持向用户发起单选题提问并等待答复。
 */
public class AskUserPlugin implements EveryAgentPlugin {

    private static final Logger log = LoggerFactory.getLogger(AskUserPlugin.class);

    /** 插件 id，必须与 plugin.json 的 id 字段一致。 */
    @Override
    public String id() {
        return "ask-user";
    }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        ctx.registerToolProvider(new AskUserToolProvider(ctx.services().config()));
        log.info("[ask-user] 已激活（{}）", ctx.pluginDir());
    }
}
