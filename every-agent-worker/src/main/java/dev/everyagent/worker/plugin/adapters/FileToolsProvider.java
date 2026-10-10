package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.worker.tools.FsToolSupport;
import dev.everyagent.worker.tools.FileTools;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 文件工具提供者 —— 包装 {@link FileTools}。
 *
 * <p>createTools: {@code ToolCallbacks.from(new FileTools(fs, task, agentId))}，
 * 与改造前 TaskManager/SubAgentManager 中的装配方式完全一致。
 */
public class FileToolsProvider implements ToolProvider {

    private final FsToolSupport fs;

    public FileToolsProvider(FsToolSupport fs) {
        this.fs = fs;
    }

    @Override
    public String pluginId() {
        return "builtin-file-tools";
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        return List.of(ToolCallbacks.from(new FileTools(fs, ctx, ctx.agentId())));
    }
}
