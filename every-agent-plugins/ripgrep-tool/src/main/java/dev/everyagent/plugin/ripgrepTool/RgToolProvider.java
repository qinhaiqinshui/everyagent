package dev.everyagent.plugin.ripgrepTool;

import dev.everyagent.plugin.api.spi.NativeExec;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;

/**
 * rg 工具提供者——包装 {@link RgTools}（注解注册形态对照 ask-user 的
 * {@code AskUserToolProvider}：{@code ToolCallbacks.from(new RgTools(...))} 直返裸
 * ToolCallback，不自建循环）。
 *
 * <p>rg 二进制由 {@link RipgrepToolPlugin#activate} 经 {@link RgResolver} 三档解析
 * （插件根 bin/ → 程序根 runtime/bin/ → 系统 PATH）一次存字段后传入；宿主执行器
 * {@link NativeExec}（argv 直传/超时/输出上限内建）同在 activate 经
 * {@code ctx.services().nativeExec()} 取得——ToolContext 无 services 槽位，per-run
 * 变化的槽位（workspaceRoot/commandGate/toolOutcomes）才走 {@link #createTools}。
 */
public class RgToolProvider implements ToolProvider {

    private final RgResolver.Resolved rg;
    private final NativeExec nativeExec;

    public RgToolProvider(RgResolver.Resolved rg, NativeExec nativeExec) {
        this.rg = rg;
        this.nativeExec = nativeExec;
    }

    @Override
    public String pluginId() {
        return "ripgrep-tool";
    }

    @Override
    public boolean appliesTo(ToolContext ctx) {
        // 排除 wsl-ubuntu 后端：该后端下任务的工作区挂在 Linux 侧（drvfs /mnt/<盘> 或
        // ext4），本工具的 rg.exe 在宿主直跑——既读不到模型口中的 Linux 侧路径语义，
        // 也看不见 wsl 内安装的 rg；wsl 语境的内容搜索归 wsl 后端自己的 shell 工具
        //（wsl 内 rg）承担。其余后端（direct / codex / windows-mic，含 sandbox 为
        // null 的极端形态）命令都在宿主侧执行，rg 以绝对路径直跑可用。
        return ctx.sandbox() == null || !"wsl-ubuntu".equals(ctx.sandbox().id());
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        if (!rg.available()) {
            // rg 三档皆无：不注册本工具。工具描述承诺了 rg 能力，注册即谎报可用——
            // 模型会把「命令不存在/执行失败」误读成「无匹配、结果正常」（口径同
            // CodexBashToolProvider 的条件化描述，此处直接不注册）。
            return List.of();
        }
        Path workspaceRoot = ctx.workspaceRoot() != null
                ? Path.of(ctx.workspaceRoot())
                : Path.of(System.getProperty("user.dir")); // 极端兜底：无工作区锚点时锚到进程 cwd
        return List.of(ToolCallbacks.from(new RgTools(rg.binary(), workspaceRoot,
                ctx.commandGate(), ctx.toolOutcomes(), nativeExec)));
    }
}
