package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.plugin.ToolContextImpl;
import dev.everyagent.worker.plugin.spi.ToolContext;
import dev.everyagent.worker.plugin.spi.ToolProvider;
import dev.everyagent.worker.tools.FsToolSupport;
import dev.everyagent.worker.tools.FileTools;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 文件工具提供者（scope=BOTH）—— 包装 {@link FileTools}。
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
    public Scope scope() {
        return Scope.BOTH;
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        ToolContextImpl impl = (ToolContextImpl) ctx;
        return List.of(ToolCallbacks.from(new FileTools(fs, impl.taskEntry(), ctx.agentId())));
    }
}
