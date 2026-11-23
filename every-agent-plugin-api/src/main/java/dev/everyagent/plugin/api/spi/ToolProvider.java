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
     * 为指定任务创建<b>hidden 工具</b>：注册但不进请求的 tool 定义——模型经 skill 文档
     * 认知后按名调用，执行链（拦截链/事件/授权）与常规工具完全一致。
     *
     * <p>契约要点：
     * <ul>
     *   <li><b>hidden 语义</b>：工具名与 schema 都不随请求下发给模型，模型调用的依据是
     *       skill 文档里的工具参考（名称/入参/示例）——<b>skill 文档化是本机制的另一半</b>，
     *       只产工具不写文档等于模型永远不知道这些工具存在；</li>
     *   <li><b>per-run 池</b>：worker 聚合各 provider 的返回为 {@code Map<工具名, ToolCallback>}
     *       随 prompt options 的框架 ToolContext map 独立键下行（与 RunContext 同 map 共存、
     *       互不可见），生命周期 = 本 run，跨任务/跨 run 天然隔离；</li>
     *   <li><b>不得捕获 per-run ctx</b>：本方法每次 run 调用一次，回调若需 per-run 数据
     *       （RunContext 等）应在工具被调用时从框架 ToolContext map 按键取，不得把 per-run
     *       上下文存进可跨 run 存活的对象字段；本方法入参 ctx 本身随工具回调同生命周期，
     *       直接持有是允许的；</li>
     *   <li>worker 配置 {@code worker.tools.hidden-enabled=false}（应急回退）时，聚合层把
     *       本池回落为常规工具（进请求 tool 定义、带 schema），provider 侧无须感知开关。</li>
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
