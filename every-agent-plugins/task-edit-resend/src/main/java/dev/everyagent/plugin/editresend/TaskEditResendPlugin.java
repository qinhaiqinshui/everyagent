package dev.everyagent.plugin.editresend;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;

/**
 * task-edit-resend 插件入口。
 * <p>核心装配经 Spring @Component（TaskEditResendRegistrar）自动完成；
 * 此类仅供 plugin.json 声明入口（外部插件加载路径），activate 为空操作。
 */
public class TaskEditResendPlugin implements EveryAgentPlugin {

    @Override
    public String id() { return "task-edit-resend"; }

    @Override
    public void activate(WorkerPluginContext ctx) {
        // 装配经 Spring @Component 自动完成，无需手动注册
    }
}
