package dev.everyagent.plugin.api;

import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import dev.everyagent.plugin.api.rpc.RpcMethod;
import dev.everyagent.plugin.api.skill.SkillContributor;
import dev.everyagent.plugin.api.slash.SlashProvider;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.plugin.api.spi.SandboxProvider;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.plugin.api.task.TaskAdmissionPolicy;
import dev.everyagent.plugin.api.task.TaskInputInterceptor;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;

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

    /** 注册 SearchProvider（搜索后端）。 */
    void registerSearchProvider(SearchProvider provider);

    /** 注册 AuthorizationHandler（授权决议链节点）。 */
    void registerAuthorizationHandler(AuthorizationHandler handler);

    /** 注册 ToolExecutionInterceptor（工具执行拦截链节点）。 */
    void registerToolExecutionInterceptor(ToolExecutionInterceptor interceptor);

    /** 注册 TaskLifecycleNode（任务生命周期链节点）。 */
    void registerTaskLifecycleNode(TaskLifecycleNode node);

    /** 注册 TaskInputInterceptor（任务输入拦截器，接管运行中 task.input / task.dialogInsert）。 */
    void registerTaskInputInterceptor(TaskInputInterceptor interceptor);

    /** 注册 SkillContributor（skill 贡献者，向 system prompt 与 / 菜单贡献 skill）。 */
    void registerSkillContributor(SkillContributor contributor);

    /** 注册任务准入策略（队列插件用）。 */
    void registerTaskAdmissionPolicy(TaskAdmissionPolicy policy);

    // ── 通用扩展注册 ──

    /**
     * 注册 RPC 方法（经 RpcDispatcher 分发）。
     *
     * @param method 方法名（域.动作，如 "git.status"）
     * @param handler 处理器
     */
    void registerRpcMethod(String method, RpcMethod handler);

    /**
     * 注册 Slash 命令提供者。
     *
     * @param id 来源 id
     * @param provider 命令提供者
     */
    void registerSlashProvider(String id, SlashProvider provider);

    // ── 只读服务 ──

    /** 访问 worker 核心服务（沙箱、权限门、工作区管理器等，只读）。 */
    WorkerServices services();

    /** 插件配置（从 plugin.json contributes.config 解析）。 */
    PluginConfig config();
}
