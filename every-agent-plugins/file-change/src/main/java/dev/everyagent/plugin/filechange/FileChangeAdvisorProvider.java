package dev.everyagent.plugin.filechange;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.plugin.api.task.TaskService;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

/**
 * {@link FileChangeAdvisor} 适配器。
 *
 * <p>order = {@link Ordered#HIGHEST_PRECEDENCE} + 301。每 run 新建实例。
 *
 * <p>file-change 是任务域插件（fileChanges 三槽位是 TaskRuntime 私有成员，
 * 不进 ExecContext）：advisor 经 {@link TaskService} 按
 * {@code ctx.execution().subjectId()}（今天=taskId）取回 TaskRuntime 读写
 * （SubAgentManager 同款路径，S4 不走黑盒 properties）。
 */
public class FileChangeAdvisorProvider implements AdvisorProvider {

    private final TaskService taskService;

    public FileChangeAdvisorProvider(TaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public String pluginId() {
        return "file-change";
    }

    @Override
    public int order() {
        return Ordered.HIGHEST_PRECEDENCE + 301;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentContext a = ctx.agentEntity();
        return new FileChangeAdvisor(a, taskService);
    }
}
