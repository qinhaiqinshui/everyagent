package dev.everyagent.plugin.taskwait;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;

/**
 * 等待任务插件入口。
 *
 * <p>activate() 中共享同一个内存态 {@link WaitRegistry}，依次注册三件 SPI：
 * <ol>
 *   <li>{@link WaitBindNode}（洋葱 879）：扫描本轮 rawContent 中的等待胶囊，
 *       内存登记绑定（不落盘，worker 重启即丢失）；</li>
 *   <li>{@link WaitTaskAdvisorProvider}（DEF+40，仅主 agent）：首个模型调用前
 *       消费绑定，阻塞等待目标任务 done 才放行，期间发射 task.wait trace（落盘）；</li>
 *   <li>{@link WaitTaskSlashResolver}：等待胶囊从 AI 上下文剥离（等待是行为开关，
 *       模型不需要看到）。</li>
 * </ol>
 *
 * <p>零 Spring 注解、零 worker 依赖（全仓插件纪律，参考 {@code UnattendedPlugin}）；
 * 不耦合任何队列插件（task-queue / task-input-queue 禁用时等待仍完整可用）。
 */
public class TaskWaitPlugin implements EveryAgentPlugin {

    @Override
    public String id() { return "task-wait"; }

    @Override
    public void activate(WorkerPluginContext ctx) {
        // 绑定注册表：activate 内构造、三处共享；内存态不落盘（需求 7）。
        WaitRegistry registry = new WaitRegistry();

        // 1. 洋葱节点 wait.bind(879)：rawContent → 内存绑定登记
        ctx.registerTaskLifecycleNode(new WaitBindNode(registry));

        // 2. 等待 advisor 提供者（仅主 agent 装配，DEF+40）
        ctx.registerAdvisorProvider(new WaitTaskAdvisorProvider(registry, ctx.services().task()));

        // 3. token 提交解析器：等待胶囊不注入模型上下文
        ctx.registerSlashTokenResolver(new WaitTaskSlashResolver());
    }
}
