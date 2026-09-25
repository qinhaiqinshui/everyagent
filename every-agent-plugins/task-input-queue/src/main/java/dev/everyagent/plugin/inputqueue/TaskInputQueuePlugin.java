package dev.everyagent.plugin.inputqueue;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;

/**
 * task-input-queue 插件入口。
 * <p>核心装配经 Spring @Component（TaskInputQueueRegistrar）自动完成；
 * 此类仅供 plugin.json 声明入口（外部插件加载路径），activate 为空操作。
 */
public class TaskInputQueuePlugin implements EveryAgentPlugin {

    @Override
    public String id() { return "task-input-queue"; }

    @Override
    public void activate(WorkerPluginContext ctx) {
        // 装配经 Spring @Component 自动完成，无需手动注册
    }
}
