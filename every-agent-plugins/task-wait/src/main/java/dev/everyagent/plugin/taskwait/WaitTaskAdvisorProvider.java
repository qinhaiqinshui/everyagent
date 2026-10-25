package dev.everyagent.plugin.taskwait;

import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.task.TaskService;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * {@link WaitTaskAdvisor} 的提供者。
 *
 * <p>order = {@link ToolCallingAdvisor#DEFAULT_ORDER} + 40：队列 advisor
 * （DialogInsertAdvisor，DEF+30）内侧、EmptyResponseRetry(DEF+100) /
 * TransientErrorRetry(DEF+200) 外侧——等待失败异常不经过任何重试 advisor。
 * 本插件不 import 任何队列插件类型，两队列插件禁用时本 advisor 照常装配。
 *
 * <p><b>仅主 agent 装配</b>：{@code appliesTo} 比较 {@code agentId()} 与
 * {@link TaskRuntime#mainAgentId()}（逐字仿
 * {@code inputqueue.DialogInsertAdvisorProvider.appliesTo}）——子 agent 不等待
 * 任务（等待是任务级行为，主 agent 首个模型调用统一接管）。
 */
public class WaitTaskAdvisorProvider implements AdvisorProvider {

    private final WaitRegistry registry;
    private final TaskService taskService;

    public WaitTaskAdvisorProvider(WaitRegistry registry, TaskService taskService) {
        this.registry = registry;
        this.taskService = taskService;
    }

    @Override
    public String pluginId() { return "task-wait"; }

    @Override
    public int order() { return ToolCallingAdvisor.DEFAULT_ORDER + 40; }

    @Override
    public boolean appliesTo(AdvisorContext ctx) {
        TaskRuntime t = taskService == null ? null : taskService.get(ctx.subjectId());
        return t != null && ctx.agentId() != null && ctx.agentId().equals(t.mainAgentId());
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        return new WaitTaskAdvisor(registry, taskService, ctx.subjectId(), ctx.agentEntity(), ctx);
    }
}
