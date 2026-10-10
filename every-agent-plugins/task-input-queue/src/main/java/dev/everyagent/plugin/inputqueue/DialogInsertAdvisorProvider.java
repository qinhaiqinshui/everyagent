package dev.everyagent.plugin.inputqueue;

import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.task.TaskService;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * DialogInsertAdvisor 提供者（从 worker 核心迁入插件）。
 * <p>advisor 在工具循环下行阶段 drain 插入对话队列，把用户输入以 role=user 随工具结果提交给 AI。
 * order = ToolCallingAdvisor.DEFAULT_ORDER + 30（工具循环内侧）。
 * <p><b>仅主 agent 装配</b>：{@code appliesTo} 比较 {@code agentId()} 与
 * {@link TaskRuntime#mainAgentId()}，非主 agent 直接不挂 advisor——子 agent 的对话是父 agent 的
 * 一次性嵌套，不接收任务队列输入；否则主/子 advisor 会 drain 同一个任务级插入队列互相抢输入。
 * <p>不在构造期取队列对象：插入队列是用户点击「插入」时才懒建的（见
 * {@link DialogInsertAdvisor} 类注释），advisor 持注册表按 taskId 现取。
 */
public class DialogInsertAdvisorProvider implements AdvisorProvider {

    private final TaskQueueRegistry registry;
    private final TaskService taskService;

    public DialogInsertAdvisorProvider(TaskQueueRegistry registry, TaskService taskService) {
        this.registry = registry;
        this.taskService = taskService;
    }

    @Override
    public String pluginId() { return "task-input-queue"; }

    @Override
    public int order() { return ToolCallingAdvisor.DEFAULT_ORDER + 30; }

    @Override
    public boolean appliesTo(AdvisorContext ctx) {
        TaskRuntime t = taskService == null ? null : taskService.get(ctx.subjectId());
        return t != null && ctx.agentId() != null && ctx.agentId().equals(t.mainAgentId());
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        return new DialogInsertAdvisor(registry, ctx.subjectId(), ctx.agentEntity());
    }
}
