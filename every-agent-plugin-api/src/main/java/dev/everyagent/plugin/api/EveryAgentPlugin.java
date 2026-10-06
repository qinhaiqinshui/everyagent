package dev.everyagent.plugin.api;

/**
 * 插件入口接口 —— 对标 VSCode 的 {@code activate(context)} / {@code deactivate()}。
 *
 * <p>每个插件实现此接口，在 {@link #activate} 中通过 {@link WorkerPluginContext}
 * 注册自己的 SPI 实现（ToolProvider / AdvisorProvider / SandboxProvider 等）。
 *
 * <p>插件 id 必须与 plugin.json 中的 id 一致。
 */
public interface EveryAgentPlugin {

    /** 插件 id（与 plugin.json 一致，仅允许 [a-z0-9-]）。 */
    String id();

    /**
     * 激活：获取 PluginContext，注册扩展。
     *
     * @param ctx 插件上下文（含注册方法与只读服务）
     * @throws Exception 激活失败（单个插件失败不影响其他插件）
     */
    void activate(WorkerPluginContext ctx) throws Exception;

    /**
     * 可选：停用（释放资源、注销 SPI 实现）。
     *
     * <p>worker 优雅关闭时由 PluginLoader 在销毁阶段逐个调用——只调
     * {@code activate()} 成功的插件（激活失败的插件不会被调）。运行期
     * 禁用/卸载插件不触发本方法（名单变更对下一次 worker 启动生效）；
     * 进程异常终止（被强杀/崩溃）时本方法也不会被调用。
     */
    default void deactivate() {}
}
