package dev.everyagent.plugin.api.spi;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.shell.ShellExecutor;
import java.nio.file.Path;

/**
 * 工具创建上下文 —— {@link ToolProvider#createTools} 的参数。
 *
 * <p>本身即统一执行上下文（extends {@link ExecContext}：subjectId / workspaceRoot /
 * snapshot / emitter 等类型化槽位），外加工具创建侧专属信息
 * （agentId、沙箱后端、工作区管理器、rg 二进制路径、shell 执行器），
 * 工具提供者据此创建工具实例。需要 {@link Path} 形态工作区根的消费者
 * 经 {@code Path.of(ctx.workspaceRoot())} 转换并自行 null 判定。
 */
public interface ToolContext extends ExecContext {

    /** Agent ID（主 agent 的 mainAgentId 或子 agent 的 agentId）。 */
    String agentId();

    /**
     * 生效的沙箱后端（由 {@code SandboxProviderRegistry} 按注册表代次惰性解析,
     * 未解析到 SPI 后端时为 DIRECT 门面,{@code id() == "direct"}）。
     *
     * <p>各后端的命令工具用 {@code ctx.sandbox().id().equals("<自己的 id>")} 判定生效条件。
     */
    SandboxBackend sandbox();

    /** 工作区管理器（多工作区注册表）。 */
    WorkspaceManager workspaces();

    /** ripgrep 二进制路径（搜索工具用，可能为 null）。 */
    default Path rgBinary() {
        return null;
    }

    /** 已组装好的 shell 执行器（授权 + 沙箱已内建），插件用它注册 ShellTool。 */
    default ShellExecutor shellExecutor() {
        return null;
    }

    /**
     * 命令授权门禁 —— 沙箱插件在 spawn 前调用（{@link CommandGate#authorize}），
     * 把危险动词 / 工作区外路径引用交给 worker 的授权决议链（弹窗 / AI 审议）。
     *
     * <p>后端自建命令执行器时<b>必须</b>先过本门禁：worker 只对自己内置的
     * {@code CommandExecutor} 做了门禁，插件的执行器若不过门禁，则「授权 → 下发沙箱」
     * 这条链根本不会启动（表现为工作区外写操作被 OS 直接拒绝且从不弹窗）。
     *
     * <p>默认实现放行（无门禁）——仅供不走沙箱 / 不需要授权的极端场景。
     */
    default CommandGate commandGate() {
        return command -> {
        };
    }
}
