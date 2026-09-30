package dev.everyagent.plugin.inputqueue;

import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DialogInsertAdvisor 提供者（从 worker 核心迁入插件）。
 * <p>advisor 在工具循环下行阶段 drain 插入对话队列，把用户输入以 role=user 随工具结果提交给 AI。
 * order = ToolCallingAdvisor.DEFAULT_ORDER + 30（工具循环内侧）。
 */
public class DialogInsertAdvisorProvider implements AdvisorProvider {

    private static final Logger log = LoggerFactory.getLogger(DialogInsertAdvisorProvider.class);

    private final TaskQueueRegistry registry;

    public DialogInsertAdvisorProvider(TaskQueueRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String pluginId() { return "task-input-queue"; }

    @Override
    public int order() { return ToolCallingAdvisor.DEFAULT_ORDER + 30; }

    @Override
    public Advisor create(AdvisorContext ctx) {
        return new DialogInsertAdvisor(
                registry.getDialogInsertQueue(ctx.taskId()),
                ctx.taskId()
        );
    }
}
