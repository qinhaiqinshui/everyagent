package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.plugin.registry.AdvisorProviderRegistry;
import dev.everyagent.worker.skill.SkillAdvisor;
import dev.everyagent.worker.slash.SlashTokenHandler;
import dev.everyagent.worker.task.RoundIndexStore;
import dev.everyagent.worker.task.TaskStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 内置 Advisor 的 AdvisorProvider 注册入口。
 *
 * <p>在 {@link PostConstruct} 中将 12 个内置 Advisor 的适配器注册到
 * {@link AdvisorProviderRegistry}，替代 {@code AgentClientFactory} 原硬编码的
 * Advisor 创建与顺序。适配器持有的共享依赖（TaskStore、RoundIndexStore、OsSandbox、
 * SlashTokenHandler、WorkerProperties、SkillAdvisor）由 Spring 注入；
 * per-run 的 AgentEntity / ToolCallingManager 通过 {@link dev.everyagent.worker.plugin.AdvisorContextImpl}
 * 在 create() 时传递。Git 自动同步 Advisor 由 git 插件模块自行注册。
 *
 * <p>注册顺序不影响最终 Advisor 链顺序——{@link AdvisorProviderRegistry} 按
 * {@link dev.everyagent.worker.plugin.spi.AdvisorProvider#order()} 排序输出。
 */
@Component
public class BuiltInAdvisorProviders {

    private final AdvisorProviderRegistry registry;
    private final TaskStore taskStore;
    private final RoundIndexStore roundIndexStore;
    private final OsSandbox osSandbox;
    private final SlashTokenHandler slashTokenHandler;
    private final WorkerProperties props;
    private final SkillAdvisor skillAdvisor;

    public BuiltInAdvisorProviders(
            AdvisorProviderRegistry registry,
            TaskStore taskStore,
            RoundIndexStore roundIndexStore,
            OsSandbox osSandbox,
            SlashTokenHandler slashTokenHandler,
            WorkerProperties props,
            SkillAdvisor skillAdvisor) {
        this.registry = registry;
        this.taskStore = taskStore;
        this.roundIndexStore = roundIndexStore;
        this.osSandbox = osSandbox;
        this.slashTokenHandler = slashTokenHandler;
        this.props = props;
        this.skillAdvisor = skillAdvisor;
    }

    @PostConstruct
    public void registerBuiltin() {
        // 核心基础设施（0─99）
        registry.register(new RoundIndexAdvisorProvider(taskStore, roundIndexStore));
        registry.register(new SystemInfoAdvisorProvider(osSandbox));
        registry.register(new AgentsMdAdvisorProvider());

        // 功能 Advisor（100─199）
        registry.register(new SkillAdvisorProvider(skillAdvisor));
        registry.register(new SlashTokenResolveAdvisorProvider(slashTokenHandler));

        // 守卫 / 文件跟踪（200─399）
        registry.register(new LoopRepeatGuardAdvisorProvider(props));
        registry.register(new FileChangeAdvisorProvider());

        // 重试 / 护栏（400─599）
        registry.register(new EmptyResponseRetryAdvisorProvider(props));
        registry.register(new TransientErrorRetryAdvisorProvider(props));
        registry.register(new ModelLengthGuardAdvisorProvider(props));

        // 终层（800─999）
        registry.register(new ContextCompressionAdvisorProvider(props));
    }
}
