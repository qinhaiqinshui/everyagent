package dev.everyagent.worker.plugin.spi;

import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;

/**
 * AI 工具提供者 SPI —— 插件实现此接口向 agent 注入工具。
 *
 * <p>对标 VSCode 的 language provider / command provider：
 * 不同插件提供不同 AI 工具（bash / file / git / sub-agent / ask-user），
 * 核心只做聚合——{@link dev.everyagent.worker.task.TaskManager#buildMainAgent}
 * 从注册表遍历所有 provider 聚合工具列表。
 *
 * <p>改造前：TaskManager 硬编码 {@code new AskUserTool()} / {@code new BashTool()} / {@code new FileTools()}。
 * 改造后：TaskManager 从 {@link dev.everyagent.worker.plugin.registry.ToolProviderRegistry} 聚合。
 *
 * <p>注意：工具创建是 per-task 的（需要 taskId、workspaceRoot 等），
 * 因此 {@link #createTools} 接收 {@link ToolContext}。
 */
public interface ToolProvider {

    /** 工具作用范围。 */
    enum Scope {
        /** 仅主 agent 注册。 */
        MAIN,
        /** 仅子 agent 注册。 */
        SUB,
        /** 主/子都注册。 */
        BOTH
    }

    /** 插件 id。 */
    String pluginId();

    /** 工具作用范围（主/子/两者）。 */
    Scope scope();

    /**
     * 为指定任务创建工具回调列表。
     *
     * @param ctx 工具创建上下文（含 taskId、workspaceRoot、sandbox、gate 等）
     * @return 该 provider 贡献的工具回调列表
     */
    List<ToolCallback> createTools(ToolContext ctx);

    /**
     * 可选：工具是否适用于此任务。
     * 如 PowerShell 工具只在 Windows + windows-mic 后端激活。
     */
    default boolean appliesTo(ToolContext ctx) {
        return true;
    }
}
