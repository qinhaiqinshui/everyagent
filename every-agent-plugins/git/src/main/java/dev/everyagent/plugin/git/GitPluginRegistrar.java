package dev.everyagent.plugin.git;

import dev.everyagent.worker.plugin.registry.AdvisorProviderRegistry;
import org.springframework.stereotype.Component;

/**
 * Git 插件 AdvisorProvider 注册入口。
 *
 * <p>从 worker 核心 {@code BuiltInAdvisorProviders} 迁出后,由插件模块自行注册
 * {@link GitAutoSyncAdvisorProvider} 到 {@link AdvisorProviderRegistry}。
 * 与 auth-review 模块的 {@code AiReviewAuthHandler} 同模式:构造器注入 registry 后立即登记。
 */
@Component
public class GitPluginRegistrar {

    public GitPluginRegistrar(AdvisorProviderRegistry registry, GitService gitService) {
        registry.register(new GitAutoSyncAdvisorProvider(gitService));
    }
}
