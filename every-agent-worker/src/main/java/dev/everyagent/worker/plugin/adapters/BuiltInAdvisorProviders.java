package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.plugin.registry.AdvisorProviderRegistry;
import dev.everyagent.worker.skill.SkillAdvisor;
import dev.everyagent.worker.slash.SlashTokenHandler;
import dev.everyagent.worker.task.RoundIndexStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 内置 Advisor 的 AdvisorProvider 注册入口。
 *
 * <p>在 {@link PostConstruct} 中将内置 Advisor 的适配器注册到
 * {@link AdvisorProviderRegistry}，替代 {@code AgentClientFactory} 原硬编码的
 * Advisor 创建与顺序。适配器持有的共享依赖（RoundIndexStore、
 * SlashTokenHandler、SkillAdvisor）由 Spring 注入；
 * per-run 的 AgentEntity / ToolCallingManager 通过 {@link dev.everyagent.worker.plugin.AdvisorContextImpl}
 * 在 create() 时传递。Git 自动同步 Advisor 由 git 插件模块自行注册。
 * SystemInfoAdvisor 由 system-info 插件模块自行注册。
 * AgentsMdAdvisor 由 agents-md 插件模块自行注册。
 *
 * <p>注册顺序不影响最终 Advisor 链顺序——{@link AdvisorProviderRegistry} 按
 * {@link dev.everyagent.worker.plugin.spi.AdvisorProvider#order()} 排序输出。
 */
@Component
public class BuiltInAdvisorProviders {

    private final AdvisorProviderRegistry registry;
    private final RoundIndexStore roundIndexStore;
    private final SlashTokenHandler slashTokenHandler;
    private final SkillAdvisor skillAdvisor;

    public BuiltInAdvisorProviders(
            AdvisorProviderRegistry registry,
            RoundIndexStore roundIndexStore,
            SlashTokenHandler slashTokenHandler,
            SkillAdvisor skillAdvisor) {
        this.registry = registry;
        this.roundIndexStore = roundIndexStore;
        this.slashTokenHandler = slashTokenHandler;
        this.skillAdvisor = skillAdvisor;
    }

    @PostConstruct
    public void registerBuiltin() {
        // 核心基础设施（0─99）
        // TokenCalibrationAdvisor 已迁至 model-rate-limit 插件（步骤 4），不再在此注册。
        // SystemInfoAdvisor 已迁入 system-info 插件模块，由插件自行注册。
        // AgentsMdAdvisor 已迁入 agents-md 插件模块，由插件自行注册。
        registry.register(new RoundIndexAdvisorProvider(roundIndexStore));

        // 功能 Advisor（100─199）
        registry.register(new SkillAdvisorProvider(skillAdvisor));
        registry.register(new SlashTokenResolveAdvisorProvider(slashTokenHandler));
        // 文件附件注入（读 metadata.attachments → 末位 UserMessage 重建为多模态消息）
        registry.register(new FileAttachmentAdvisorProvider());

        // 事件发射 / 文件跟踪（200─399）
        // LoopRepeatGuardAdvisorProvider 已移除，死循环守卫改由 AgentClientFactory 在组装入口装饰 TCM 承担；
        // 事件发射由 WorkerToolEventAdvisorProvider 提供。
        registry.register(new WorkerToolEventAdvisorProvider());
        // FileChangeAdvisorProvider 已迁入 file-change 插件模块，由插件自行注册。

        // 重试 / 护栏（400─599）
        // EmptyResponseRetryAdvisor / TransientErrorRetryAdvisor 已迁入独立插件模块（步骤 3），
        // 由各插件自行注册，不再在此注册。
        // model-length-guard 插件 已迁入 model-length-guard 插件模块，由插件自行注册，不再在此注册。

        // 终层（800─999）
        // ContextCompressionAdvisor 已迁入 context-compression 插件模块（步骤 3），不再在此注册。
    }
}
