package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Path;
import java.util.List;

/**
 * codex 沙箱自己的命令工具 ToolProvider（形态对照 WslUbuntuBashToolProvider）。
 *
 * <p>appliesTo：只在当前沙箱后端 id=="codex" 时生效（经 {@link ToolContext#sandbox}
 * 判定——后端被 worker 选中即 setup 已就绪，工具内不会再遇到未 setup 的静默降级）。
 * createTools：创建 {@link CodexCmdTool}（使用 {@link CodexCommandExecutor}），
 * 返回 {@code List.of(ToolCallbacks.from(...))}。
 */
public class CodexBashToolProvider implements ToolProvider {

    private final CodexSandboxManager manager;

    public CodexBashToolProvider(CodexSandboxManager manager) {
        this.manager = manager;
    }

    @Override
    public String pluginId() {
        return "sandbox-windows-codex";
    }

    @Override
    public boolean appliesTo(ToolContext ctx) {
        return ctx.sandbox() != null && "codex".equals(ctx.sandbox().id());
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        Path workspaceRoot = ctx.workspaceRoot();
        CodexCommandExecutor exec = new CodexCommandExecutor(manager, workspaceRoot,
                ctx.rgBinary());
        return List.of(ToolCallbacks.from(new CodexCmdTool(exec)));
    }

    /**
     * codex 沙箱的命令工具（Windows 沙箱账户内执行，不依赖 worker 的
     * CommandExecutor）。接收完整 cmd 命令字符串，委托
     * {@link CodexCommandExecutor#execute} 经 runner 会话执行
     * （cmd.exe /d /c，cwd=工作区根，stdin 关闭）。
     */
    public static class CodexCmdTool {

        private final CodexCommandExecutor exec;

        public CodexCmdTool(CodexCommandExecutor exec) {
            this.exec = exec;
        }

        @Tool(name = "cmd", description = "在系统上用 cmd 执行真实 OS 命令;"
                + "命令经 codex 沙箱(独立本地账户+受限令牌)隔离运行;"
                + "rg 已加入 PATH,可直接执行 rg 命令,内容搜索尽量使用rg命令，性能更好;"
                + "命令工作目录默认为任务工作区根;"
                + "stdin 为 null 设备,命令无法从 stdin 读入输入;"
                + "越出工作区/授权根的写会被 OS 级 ACL 拦截;")
        public String cmd(
                @ToolParam(description = "要执行的 cmd 命令,如 \"git status\"") String command) {
            return exec.execute(command, "cmd");
        }
    }
}
