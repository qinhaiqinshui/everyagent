package dev.everyagent.plugin.api.spi;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.task.TaskRuntime;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdvisorContext#isTaskSubject()} 默认实现语义测试:判定走
 * {@code agentEntity()} 委托槽位上的 {@code execution()}——agentEntity 为 null、
 * execution 为 null、execution 非任务域(如工作流 runtime)时 false;
 * execution 为 {@link TaskRuntime} 时 true。
 *
 * <p>桩按插件测试约定在测试类内自建(实现 plugin-api 接口,不借 worker 具体类);
 * AgentContext / ExecContext / TaskRuntime 用动态代理(默认方法经 InvocationHandler
 * 路由,本用例只触达 {@code execution()} 槽位,其余方法不被调用)。
 */
class AdvisorContextIsTaskSubjectTest {

    /** 手写 AdvisorContext 桩:仅 agentEntity() 携带注入值,其余槽位最小假值;isTaskSubject() 走接口默认实现。 */
    private static final class StubAdvisorContext implements AdvisorContext {
        private final AgentContext agent;

        StubAdvisorContext(AgentContext agent) {
            this.agent = agent;
        }

        @Override public AgentContext agentEntity() { return agent; }
        @Override public String agentId() { return "a1"; }
        @Override public String configId() { return null; }
        @Override public org.springframework.ai.model.tool.ToolCallingManager toolCallingManager() { return null; }
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

    /** 只接 execution() 的 AgentContext 动态代理桩(其余方法不在 isTaskSubject 路径上)。 */
    private static AgentContext agentWithExecution(ExecContext exec) {
        return (AgentContext) Proxy.newProxyInstance(
                AdvisorContextIsTaskSubjectTest.class.getClassLoader(),
                new Class<?>[] { AgentContext.class },
                (p, m, a) -> "execution".equals(m.getName()) ? exec : null);
    }

    /** 非任务主体桩:只实现 ExecContext(工作流 WorkflowRuntime 的最小形态)。 */
    private static ExecContext workflowLikeExec() {
        return (ExecContext) Proxy.newProxyInstance(
                AdvisorContextIsTaskSubjectTest.class.getClassLoader(),
                new Class<?>[] { ExecContext.class },
                (p, m, a) -> null);
    }

    /** 任务主体桩:实现 TaskRuntime(worker TaskEntry 的契约形态)。 */
    private static TaskRuntime taskRuntime() {
        return (TaskRuntime) Proxy.newProxyInstance(
                AdvisorContextIsTaskSubjectTest.class.getClassLoader(),
                new Class<?>[] { TaskRuntime.class },
                (p, m, a) -> null);
    }

    @Test
    void nullAgentEntityYieldsFalse() {
        assertFalse(new StubAdvisorContext(null).isTaskSubject(),
                "agentEntity 为 null(未装配/测试桩)时保守判非任务");
    }

    @Test
    void nullExecutionYieldsFalse() {
        assertFalse(new StubAdvisorContext(agentWithExecution(null)).isTaskSubject(),
                "execution 槽位为 null(旧桩未覆写)时不得 NPE,判非任务");
    }

    @Test
    void nonTaskExecutionYieldsFalse() {
        assertFalse(new StubAdvisorContext(agentWithExecution(workflowLikeExec())).isTaskSubject(),
                "非任务主体(工作流等只实现 ExecContext 的 runtime)判非任务");
    }

    @Test
    void taskRuntimeExecutionYieldsTrue() {
        assertTrue(new StubAdvisorContext(agentWithExecution(taskRuntime())).isTaskSubject(),
                "execution 为 TaskRuntime 时判任务主体");
    }
}
