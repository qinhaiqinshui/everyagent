package dev.everyagent.worker.config;

import dev.everyagent.plugin.api.spi.SandboxBackend.Access;
import dev.everyagent.worker.agent.PerCallToolCallingManager;
import dev.everyagent.worker.agent.ToolCallLimits;
import dev.everyagent.worker.agent.ToolEventInterceptor;
import dev.everyagent.worker.modules.SkillExposureStore;
import dev.everyagent.worker.os.SandboxPathRegistry;
import dev.everyagent.worker.plugin.adapters.SkillAdvisorProvider;
import dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry;
import dev.everyagent.worker.skill.SkillCatalog;
import dev.everyagent.plugin.api.exception.AgentCancelledException;
import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.worker.tools.MissingToolCallbackResolver;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Set;

/**
 * Worker 核心 Bean 装配（从 AgentClientFactory 迁入）。
 *
 * <p>共享无状态 {@link PerCallToolCallingManager} 单例(照抄 DefaultToolCallingManager +
 * per-call 拦截链切口,§7.14.3)+ SkillAdvisor 的 per-run 装配入口
 * （SkillAdvisorProvider）的 @Bean 定义。
 */
@Configuration
public class WorkerBeanConfiguration {

    /**
     * 工具执行异常处理器：取消类异常穿透框架向上抛（中断 agent 线程），其余异常转模型可读文本。
     * 失败文本统一携带 {@link ExecResults#TOOL_FAILURE_PREFIX} 前缀——per-call 链上行
     * {@code tool.result} 的 data.status 前缀判定约定(§5.3)。
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
        // 前缀取自 ExecResults 单点常量:tool.result 的 data.status 判定源即此文案。
        return ExecResults.TOOL_FAILURE_PREFIX + " "
                + (msg == null ? cause.getClass().getSimpleName() : msg);
    };

    /**
     * 基础工具调用管理器:per-call 化的照抄实现(PerCallToolCallingManager,§7.14.3)。
     * <p>原 {@code ToolCallingManager.builder()} 四槽位显式传构造,行为逐位保真:
     * 异常处理器(CANCELLING_PROCESSOR)/回调解析器(MissingToolCallbackResolver 兜底,
     * resolutionFallback=true)/limits(maxCallsPerTool=200, maxTotalToolCalls=500,
     * behavior=THROW 与 builder 缺省一致)/resolutionFallbackEnabled。
     * per-call 切口的拦截链经 {@link ToolExecutionInterceptorRegistry} 查得;
     * 内置发射节点(tool.started/tool.result)在此注册。per-run 上下文由 AgentRunner
     * 经 prompt options 的 toolContext 下行,不依赖 ThreadLocal。
     */
    @Bean
    public ToolCallingManager toolCallingManager(ToolExecutionInterceptorRegistry interceptorRegistry) {
        interceptorRegistry.register(new ToolEventInterceptor());
        ToolCallLimits limits = new ToolCallLimits(200, Map.of(), Set.of(), 500,
                ToolCallLimitBehavior.THROW);
        return new PerCallToolCallingManager(new MissingToolCallbackResolver(),
                CANCELLING_PROCESSOR, limits, true, interceptorRegistry);
    }

    /**
     * skill 渐进式披露索引注入的 AdvisorProvider —— per-run 装配入口。
     *
     * <p>provider 是持 SkillCatalog / SkillExposureStore / SandboxPathRegistry 三个
     * 共享依赖的单例；{@code create(ctx)} 每次按 {@code ctx.workspaceRoot()} 新建无状态
     * {@code SkillAdvisor}（按工作区的暴露过滤需要每 run 可得的工作区根，共享 advisor
     * 单例拿不到）。SkillAdvisor 的 @Bean 已取消：主链装配统一走 AdvisorProviderRegistry
     * （BuiltInAdvisorProviders 注册本 provider），worker 内无其他消费方、测试直接
     * new SkillAdvisor 构造。
     *
     * <p>顺带在此完成「skills 目录初始化时」的沙箱挂载意图登记（架构 §7.10）：系统技能目录
     * （{@code props.resolveSkillsDir()}）是 AI 免授权可读写、且 {@code SkillAdvisor} 要注入
     * 其沙箱内可见路径的唯一根，归此登记；{@code register} 只记账、随后由
     * {@link SandboxPathRegistry} 下发到生效沙箱后端。
     */
    @Bean
    public SkillAdvisorProvider skillAdvisorProvider(WorkerProperties props,
            SkillCatalog catalog, SkillExposureStore exposureStore, SandboxPathRegistry pathRegistry) {
        pathRegistry.register(SandboxPathRegistry.OWNER_SKILLS,
                props.resolveSkillsDir(), Access.READ_WRITE);
        return new SkillAdvisorProvider(catalog, exposureStore, pathRegistry);
    }
}
