package dev.everyagent.plugin.api.spi;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * AI 工具提供者 SPI —— 插件实现此接口向 agent 注入工具。
 *
 * <p>核心只做聚合——从注册表遍历所有 provider，按 appliesTo() 筛选。
 * AgentBuilder 调用方可通过 .tools(list, ModifyMode) 按需增删改。
 */
public interface ToolProvider {

    /** 插件 id。 */
    String pluginId();

    /**
     * 为指定任务创建工具回调列表。
     *
     * @param ctx 工具创建上下文（含 subjectId、workspaceRoot、sandbox 等，extends ExecContext）
     * @return 该 provider 贡献的工具回调列表
     */
    List<ToolCallback> createTools(ToolContext ctx);

    /**
     * 为指定任务创建<b>hidden 工具</b>：常驻下行的可执行工具池——默认不进请求的 tool
     * 定义，绑定于 skill.md frontmatter {@code tools} 的工具在模型<b>读取该知识包后</b>以
     * 完整定义（含参数 schema）进请求（自动暴露）；执行链（拦截链/事件/授权）与常规工具
     * 完全一致。
     *
     * <p>契约要点：
     * <ul>
     *   <li><b>hidden 语义</b>：未暴露期间工具名与 schema 不随请求下发，但工具
     *       <b>始终可执行</b>——模型按 skill 文档硬调即命中；暴露只是可见性语义，
     *       不是执行开关；</li>
     *   <li><b>自动暴露</b>：skill.md frontmatter {@code tools:} 声明本知识包绑定的工具名，
     *       模型经 {@code read_file} 成功读取该知识包后，绑定的工具名写入当前 agent 的
     *       {@code exposedTools}（写入点 = plugin-api 扩展方法
     *       {@code AgentContext#exposeTools(List)}），组装期取与本池的交集以完整定义
     *       并入请求 toolCallbacks；参数的唯一真源即暴露后的工具定义，skill 文档只写
     *       绑定声明与方法论、不再写参数细节（双源必漂移）；</li>
     *   <li><b>per-run 池</b>：worker 聚合各 provider 的返回为 {@code Map<工具名, ToolCallback>}
     *       随 prompt options 的框架 ToolContext map 独立键下行（与 RunContext 同 map 共存、
     *       互不可见），生命周期 = 本 run，跨任务/跨 run 天然隔离；</li>
     *   <li><b>不得捕获 per-run ctx</b>：本方法每次 run 调用一次，回调若需 per-run 数据
     *       （RunContext 等）应在工具被调用时从框架 ToolContext map 按键取，不得把 per-run
     *       上下文存进可跨 run 存活的对象字段；本方法入参 ctx 本身随工具回调同生命周期，
     *       直接持有是允许的；</li>
     *   <li>暴露集合由 worker 侧持久化（agent 台账 {@code exposedTools} 数组）并在 agent
     *       重建时回灌，跨轮/跨重启生效——provider 侧无须感知，只管产出本池。</li>
     * </ul>
     *
     * @param ctx 工具创建上下文（同 {@link #createTools}）
     * @return 该 provider 贡献的 hidden 工具回调列表；无则返回空表（本默认实现）
     */
    default List<ToolCallback> createHiddenTools(ToolContext ctx) {
        return List.of();
    }

    /**
     * 可选：工具是否适用于此任务。
     */
    default boolean appliesTo(ToolContext ctx) {
        return true;
    }
}
