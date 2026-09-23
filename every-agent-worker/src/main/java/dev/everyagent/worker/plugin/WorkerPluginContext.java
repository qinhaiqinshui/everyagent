package dev.everyagent.worker.plugin;

import dev.everyagent.worker.plugin.spi.AdvisorProvider;
import dev.everyagent.worker.plugin.spi.AgentDispatcher;
import dev.everyagent.worker.plugin.spi.ReviewProvider;
import dev.everyagent.worker.plugin.spi.SandboxProvider;
import dev.everyagent.worker.plugin.spi.SearchProvider;
import dev.everyagent.worker.plugin.spi.ToolProvider;
import dev.everyagent.worker.slash.SlashCommandRegistry;

/**
 * Worker 插件上下文 —— 对标 VSCode 的 {@code ExtensionContext}。
 *
 * <p>插件在 {@link EveryAgentPlugin#activate} 中通过此接口注册自己的 SPI 实现。
 * 核心提供注册方法与只读服务访问。
 */
public interface WorkerPluginContext {

    /** 插件 id。 */
    String pluginId();

    // ── SPI 注册方法 ──

    /** 注册 ToolProvider（AI 工具提供者）。 */
    void registerToolProvider(ToolProvider provider);

    /** 注册 AdvisorProvider（Advisor 链提供者）。 */
    void registerAdvisorProvider(AdvisorProvider provider);

    /** 注册 SandboxProvider（沙箱后端提供者）。 */
    void registerSandboxProvider(SandboxProvider provider);

    /** 注册 AgentDispatcher（子 agent 调度策略）。 */
    void registerAgentDispatcher(AgentDispatcher dispatcher);

    /** 注册 ReviewProvider（授权审议策略）。 */
    void registerReviewProvider(ReviewProvider provider);

    /** 注册 SearchProvider（搜索后端）。 */
    void registerSearchProvider(SearchProvider provider);

    // ── 通用扩展注册 ──

    /**
     * 注册 RPC 方法（经 RpcDispatcher 分发）。
     *
     * @param method 方法名（域.动作，如 "git.status"）
     * @param handler 处理器
     */
    void registerRpcMethod(String method, dev.everyagent.worker.rpc.RpcDispatcher.Method handler);

    /**
     * 注册 Slash 命令提供者。
     *
     * @param id 来源 id
     * @param provider 命令提供者
     */
    void registerSlashProvider(String id, SlashCommandRegistry.SlashProvider provider);

    // ── 只读服务 ──

    /** 访问 worker 核心服务（沙箱、权限门、工作区管理器等，只读）。 */
    WorkerServices services();

    /** 插件配置（从 plugin.json contributes.config 解析）。 */
    PluginConfig config();
}
