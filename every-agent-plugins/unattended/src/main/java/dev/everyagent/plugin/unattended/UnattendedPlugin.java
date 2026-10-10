package dev.everyagent.plugin.unattended;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;

/**
 * 无人值守插件入口。
 *
 * <p>activate() 中注册 UnattendedToolInterceptor、UnattendedAuthHandler、
 * UnattendedSlashProvider、UnattendedSlashResolver。
 */
public class UnattendedPlugin implements EveryAgentPlugin {

    @Override
    public String id() { return "unattended"; }

    @Override
    public void activate(WorkerPluginContext ctx) {
        // 1. 注册工具执行拦截器（ask_user 自动作答）
        ctx.registerToolExecutionInterceptor(new UnattendedToolInterceptor());

        // 2. 注册授权链节点（授权一律拒绝）
        ctx.registerAuthorizationHandler(new UnattendedAuthHandler());

        // 3. 注册 /无人值守 命令提供者
        ctx.registerSlashProvider("unattended", () ->
                UnattendedSlashProvider.items(ctx.services()));

        // 4. 注册 token 提交解析器
        ctx.registerSlashTokenResolver(new UnattendedSlashResolver());
    }
}
