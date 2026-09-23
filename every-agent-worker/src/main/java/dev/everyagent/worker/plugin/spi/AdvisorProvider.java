package dev.everyagent.worker.plugin.spi;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.nio.file.Path;

/**
 * Advisor 提供者 SPI —— 插件实现此接口向 agent 链注入 Advisor。
 *
 * <p>对标 VSCode 的 extension contribution point：
 * 不同插件提供不同 Advisor（skill 注入、git 自动同步、重试护栏、上下文压缩等），
 * 核心只做聚合——{@link dev.everyagent.worker.AgentClientFactory#forMain}
 * 从注册表按 scope 筛选 + 按 order 排序装配。
 *
 * <p>改造前：AgentClientFactory 硬编码 12 个 Advisor 的创建与顺序。
 * 改造后：AgentClientFactory 从 {@link dev.everyagent.worker.plugin.registry.AdvisorProviderRegistry} 聚合。
 *
 * <p>Advisor 顺序分段（预留插件挂载区间）：
 * <pre>
 *   0 ─  99 │ 核心基础设施（RoundIndex / SystemInfo / AgentsMd）
 * 100 ─ 199 │ 功能 Advisor（Skill / Git / 自定义 system prompt 注入）
 * 200 ─ 299 │ 守卫 / 文件跟踪（LoopRepeatGuard / FileChange）
 * 300 ─ 399 │ 对话增强（DialogInsert / SlashTokenResolve）
 * 400 ─ 599 │ 重试 / 护栏（EmptyRetry / TransientRetry / LengthGuard）
 * 600 ─ 799 │ 用户扩展区（第三方 Advisor 插件挂载）
 * 800 ─ 999 │ 终层（ContextCompression）
 * </pre>
 *
 * <p>红线对齐：一个 AdvisorProvider 只负责一个功能（一个 Advisor）；
 * 事件发射等需挂钩工具循环的增强通过继承 Spring AI {@code ToolCallingAdvisor} 实现。
 */
public interface AdvisorProvider {

    /** 工具作用范围：主/子/两者。 */
    enum Scope {
        MAIN, SUB, BOTH
    }

    /** 插件 id。 */
    String pluginId();

    /** Advisor 作用范围。 */
    Scope scope();

    /** Advisor 顺序（数字，Spring @Order 语义，见分段表）。 */
    int order();

    /**
     * 为指定任务创建 Advisor 实例。
     *
     * @param ctx Advisor 创建上下文
     * @return Advisor 实例
     */
    Advisor create(AdvisorContext ctx);

    /**
     * 可选：Advisor 是否适用于此任务。
     */
    default boolean appliesTo(AdvisorContext ctx) {
        return true;
    }
}
