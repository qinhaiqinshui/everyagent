package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.worker.plugin.registry.FileReadHandlerRegistry;
import dev.everyagent.worker.tools.FsToolSupport;
import dev.everyagent.worker.tools.FileTools;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 文件工具提供者 —— 包装 {@link FileTools}。
 *
 * <p>createTools: {@code ToolCallbacks.from(new FileTools(fs, ctx, ctx.agentId(), readHandlers))}，
 * 与改造前 TaskManager/SubAgentManager 中的装配方式一致；readHandlers 为 read_file
 * 按扩展名接管处理器注册表（插件注册，未命中回落核心默认文本读取）。
 */
public class FileToolsProvider implements ToolProvider {

    private final FsToolSupport fs;
    private final FileReadHandlerRegistry readHandlers;

    public FileToolsProvider(FsToolSupport fs, FileReadHandlerRegistry readHandlers) {
        this.fs = fs;
        this.readHandlers = readHandlers;
    }

    @Override
    public String pluginId() {
        return "builtin-file-tools";
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        return List.of(ToolCallbacks.from(new FileTools(fs, ctx, ctx.agentId(), readHandlers)));
    }
}
