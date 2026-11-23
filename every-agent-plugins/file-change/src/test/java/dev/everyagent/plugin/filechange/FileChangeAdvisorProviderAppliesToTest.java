package dev.everyagent.plugin.filechange;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.task.TaskRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileChangeAdvisorProvider#appliesTo} 任务域过滤测试:collector 以 taskId 为 key
 * 常驻 provider(activeCollectors),挂到非任务主体(工作流 runtime)时轮闭合回调
 * (RoundClosedListener)永不触发,collector 条目永不落盘——内存泄漏;非任务主体必须
 * false,任务主体照常 true。taskService 传 null(appliesTo 路径不触达)。
 *
 * <p>AdvisorContext 桩按插件测试约定在测试类内自建(实现 plugin-api 接口,
 * 不借 worker 具体类);AgentContext / ExecContext / TaskRuntime 用动态代理
 * (本用例只触达 {@code execution()} 槽位)。
 */
class FileChangeAdvisorProviderAppliesToTest {

    private final FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(null);

    /** 手写 AdvisorContext 桩:仅 agentEntity() 携带注入值,其余槽位最小假值。 */
    private static final class StubAdvisorContext implements AdvisorContext {
        private final AgentContext agent;

        StubAdvisorContext(AgentContext agent) {
            this.agent = agent;
        }

        @Override public AgentContext agentEntity() { return agent; }
        @Override public String agentId() { return "a1"; }
        @Override public String configId() { return null; }
        @Override public ToolCallingManager toolCallingManager() { return null; }
        @Override public String subjectId() { return "s1"; }
        @Override public String workspaceRoot() { return null; }
        @Override public String workspaceId() { return null; }
        @Override public ModelConfig snapshot() { return null; }
        @Override public EventEmitter emitter() { return null; }
        @Override public AgentFactory agentFactory() { return null; }
        @Override public Map<String, Object> metadata() { return Map.of(); }
        @Override public Path dataDir() { return null; }
        @Override public boolean terminal() { return false; }
        @Override public InteractionService interaction() { return null; }
        @Override public Map<String, AgentContext> agents() { return Map.of(); }
    }

    /** 只接 execution() 的 AgentContext 动态代理桩。 */
    private static AdvisorContext ctxWithExecution(ExecContext exec) {
        return new StubAdvisorContext((AgentContext) Proxy.newProxyInstance(
                FileChangeAdvisorProviderAppliesToTest.class.getClassLoader(),
                new Class<?>[] { AgentContext.class },
                (p, m, a) -> "execution".equals(m.getName()) ? exec : null));
    }

    /** 非任务主体桩:只实现 ExecContext(工作流 runtime 的最小形态)。 */
    private static ExecContext workflowLikeExec() {
        return (ExecContext) Proxy.newProxyInstance(
                FileChangeAdvisorProviderAppliesToTest.class.getClassLoader(),
                new Class<?>[] { ExecContext.class },
                (p, m, a) -> null);
    }

    /** 任务主体桩:实现 TaskRuntime。 */
    private static TaskRuntime taskRuntime() {
        return (TaskRuntime) Proxy.newProxyInstance(
                FileChangeAdvisorProviderAppliesToTest.class.getClassLoader(),
                new Class<?>[] { TaskRuntime.class },
                (p, m, a) -> null);
    }

    @Test
    void appliesToFalseForNonTaskSubject() {
        assertFalse(provider.appliesTo(ctxWithExecution(workflowLikeExec())),
                "非任务主体(工作流)不得挂载:collector 常驻后无轮闭合回调,永不落盘(泄漏)");
    }

    @Test
    void appliesToFalseWhenAgentEntityMissing() {
        assertFalse(provider.appliesTo(new StubAdvisorContext(null)),
                "agentEntity 为 null 时保守判非任务,不挂载");
    }

    @Test
    void appliesToTrueForTaskSubject() {
        assertTrue(provider.appliesTo(ctxWithExecution(taskRuntime())),
                "任务主体(TaskRuntime)照常挂载");
    }
}
