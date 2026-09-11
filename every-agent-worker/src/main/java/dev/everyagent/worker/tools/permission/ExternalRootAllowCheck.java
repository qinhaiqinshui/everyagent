package dev.everyagent.worker.tools.permission;

import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.task.TaskEntry;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 责任链节点:工作区外部授权根放行环(§7.8/§7.17)。用户经前端 {@code @} 弹窗 {@code +}
 * 图标<b>显式选择</b>的工作区外路径,由 {@link WorkspaceManager#addExternalRoot} 注册为
 * 该工作区的 externalRoots——该选择本身就是授权动作(语义 = 完全读写 READ+WRITE+EXEC)。
 * AI 文件工具的目标路径(realpath 前缀判定,符号链接逃逸同判)落在任一外部授权根内
 * → ALLOW(读/写均放行),不再走授权决议链(弹窗/AI 审议)。
 *
 * <p>这是 gate 链上的<b>显式放行环而非绕过 gate</b>:与人工弹窗、AI 审议并列的授权来源;
 * 全链 SKIP 兜底、过宽根拒收、命令危险动词拦截面等其余节点语义不变(§7.8)。
 * 沙箱侧消费(文件工具附加根 / 命令 EXEC 根 / wsl-direct 挂载)由消费方另行并入。
 */
@Component
public class ExternalRootAllowCheck implements PermissionCheck {

    private final WorkspaceManager workspaces;

    public ExternalRootAllowCheck(WorkspaceManager workspaces) {
        this.workspaces = workspaces;
    }

    @Override
    public PermissionDecision check(PermissionContext ctx) {
        if (ctx.realPath() == null || ctx.task() == null) {
            return PermissionDecision.skip();
        }
        TaskEntry t = ctx.task();
        List<Path> roots = workspaces.externalRootsOf(t.workspaceRoot); // 未注册返回空列表
        for (Path root : roots) {
            if (ctx.realPath().startsWith(root)) {
                return PermissionDecision.allow("外部授权根放行: " + root);
            }
        }
        return PermissionDecision.skip();
    }
}
