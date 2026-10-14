package dev.everyagent.worker.tools.permission;

import dev.everyagent.worker.modules.WorkspaceManager;

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
 *
 * <p>命令链的同源消费(§7.17):{@code CommandCheck} 用同一谓词
 * ({@link #grantingRoot})把「命令串中引用的路径落在外部授权根内」判为不越界——否则
 * 用户 {@code @} 引用过的目录,文件工具读得进、而一句 {@code Get-ChildItem <该目录>}
 * 仍会弹授权 ask(与「用户显式选择 = 已授权」及沙箱侧 EXEC 根口径不一致)。
 */
@Component
public class ExternalRootAllowCheck implements PermissionCheck {

    private final WorkspaceManager workspaces;

    public ExternalRootAllowCheck(WorkspaceManager workspaces) {
        this.workspaces = workspaces;
    }

    @Override
    public PermissionDecision invoke(PermissionContext ctx, PermissionChain next) {
        if (ctx.realPath() == null || ctx.workspaceRoot() == null) {
            return next.proceed(ctx);
        }
        Path hit = grantingRoot(workspaces.externalRootsOf(ctx.workspaceRoot()), ctx.realPath());
        return hit == null ? next.proceed(ctx) : PermissionDecision.allow("外部授权根放行: " + hit);
    }

    /**
     * 外部授权根放行谓词(<b>单点实现</b>,§7.17):{@code real}(realpath 形态)落在任一
     * 外部授权根内 → 返回命中的根,否则 null。
     *
     * <p>两处同源消费:本节点的文件路径链放行环;以及命令链
     * ({@code CommandCheck})——外部根语义 = 完全读写 READ+WRITE+EXEC,命令串中引用的
     * 路径落在授权根内即「不越界」,既不触发危险动词授权,也不触发 EXEC 路径授权。
     */
    public static Path grantingRoot(List<Path> roots, Path real) {
        if (roots == null || real == null) {
            return null;
        }
        for (Path root : roots) {
            if (real.startsWith(root)) {
                return root;
            }
        }
        return null;
    }
}
