package dev.everyagent.worker.config;

import dev.everyagent.worker.os.SandboxPathRegistry;
import dev.everyagent.worker.plugin.registry.SkillContributorRegistry;
import dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry;
import dev.everyagent.worker.skill.BuiltInSkills;
import dev.everyagent.worker.skill.SkillAdvisor;
import dev.everyagent.plugin.api.exception.AgentCancelledException;
import dev.everyagent.worker.task.InterceptingToolCallingManager;
import dev.everyagent.worker.tools.MissingToolCallbackResolver;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Worker 核心 Bean 装配（从 AgentClientFactory 迁入）。
 *
 * <p>共享无状态 ToolCallingManager 和 SkillAdvisor 单例的 @Bean 定义。
 */
@Configuration
public class WorkerBeanConfiguration {

    /**
     * 工具执行异常处理器：取消类异常穿透框架向上抛（中断 agent 线程），其余异常转模型可读文本。
     */
    private static final ToolExecutionExceptionProcessor CANCELLING_PROCESSOR = ex -> {
        Throwable cause = ex.getCause();
        if (cause instanceof InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AgentCancelledException("interrupted");
        }
        if (cause instanceof AgentCancelledException ace) {
            throw ace;
        }
        String msg = cause == null ? ex.getMessage() : cause.getMessage();
        return "[工具执行失败] " + (msg == null ? cause.getClass().getSimpleName() : msg);
    };

    /**
     * 共享无状态工具调用管理器，经 InterceptingToolCallingManager 包装以支持 ToolExecutionInterceptor 责任链。
     */
    @Bean
    public ToolCallingManager toolCallingManager(ToolExecutionInterceptorRegistry interceptorRegistry) {
        ToolCallingManager base = ToolCallingManager.builder()
                .toolExecutionExceptionProcessor(CANCELLING_PROCESSOR)
                .toolCallbackResolver(new MissingToolCallbackResolver())
                .resolutionFallbackEnabled(true)
                .maxCallsPerTool(200)
                .maxTotalToolCalls(500)
                .build();
        return new InterceptingToolCallingManager(base, interceptorRegistry);
    }

    /** skill 渐进式披露索引注入 advisor（无状态可共享单例）。 */
    @Bean
    public SkillAdvisor skillAdvisor(BuiltInSkills builtInSkills,
            SkillContributorRegistry skillContributorRegistry, SandboxPathRegistry pathRegistry) {
        return new SkillAdvisor(builtInSkills, skillContributorRegistry, pathRegistry);
    }
}
