package dev.everyagent.plugin.api;

import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import dev.everyagent.plugin.api.rpc.RpcMethod;
import dev.everyagent.plugin.api.skill.SkillContributor;
import dev.everyagent.plugin.api.slash.SlashProvider;
import dev.everyagent.plugin.api.slash.SlashTokenResolver;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.plugin.api.spi.FileReferenceHandler;
import dev.everyagent.plugin.api.spi.SandboxProvider;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.plugin.api.spi.TokenEstimator;
import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.plugin.api.model.ChatModelEnhancer;
import dev.everyagent.plugin.api.task.TaskPluginContext;

import java.nio.file.Path;

/**
 * Worker 插件上下文 —— 对标 VSCode 的 {@code ExtensionContext}。
 *
 * <p>插件在 {@link EveryAgentPlugin#activate} 中通过此接口注册自己的 SPI 实现。
 * 核心提供注册方法与只读服务访问。
 */
public interface WorkerPluginContext extends TaskPluginContext {

    /** 插件 id。 */
    String pluginId();

    /**
     * 插件根目录绝对路径（内置插件源码目录或外部插件安装目录）。
     *
     * <p>对标 VSCode 的 {@code ExtensionContext.extensionPath}。
     * 插件可经此定位自带资源（如 rootfs 镜像、脚本等）。
     */
    Path pluginDir();

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

    /** 注册 SkillContributor（skill 贡献者，向 system prompt 与 / 菜单贡献 skill）。 */
    void registerSkillContributor(SkillContributor contributor);

    /** 注册 TokenEstimator（Token 估算器，替换内置实现）。 */
    void registerTokenEstimator(TokenEstimator estimator);

    /** 注册 ChatModelEnhancer（模型构建增强器，如模型池容灾）。 */
    void registerChatModelEnhancer(ChatModelEnhancer enhancer);

    /** 注册 FileReferenceHandler（文件引用处理器，按扩展名处理 @ 文件引用）。 */
    void registerFileReferenceHandler(FileReferenceHandler handler);

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

    /**
     * 注册 Slash token 提交解析器。
     *
     * @param resolver token 解析器
     */
    void registerSlashTokenResolver(SlashTokenResolver resolver);

    // ── 只读服务 ──

    /** 访问 worker 核心服务（沙箱、权限门、工作区管理器等，只读）。 */
    WorkerServices services();

    /** 插件配置（从 plugin.json contributes.config 解析）。 */
    PluginConfig config();

    /**
     * 按 Class 获取 worker 核心服务（Spring bean）。
     *
     * <p>内置插件可经此获取未在 {@link WorkerServices} 中暴露的 worker 内部服务
     * （如 {@code WorkerProperties}、{@code TaskStore} 等）。
     * 外部插件不应依赖此方法获取未公开的服务。
     *
     * @param type 服务 Class
     * @return Spring bean（不存在时抛异常）
     */
    <T> T getService(Class<T> type);
}
