package dev.everyagent.worker.tools.permission;

import java.nio.file.Path;

import org.springframework.stereotype.Component;

/**
 * 责任链节点 3:过度宽泛授权根拒收(§13.3 修复 B,L1/L2 单点实现)。
 * 只拒两种无真实需求的宽根:<b>① 文件系统根</b>(盘根 C:\ / UNC 根)、
 * <b>② 工作区(词法或 realpath)的祖先或自身</b>。命中即静默拒收
 * (不弹窗、不报错,交由沙箱越界拦截兜底)——授权根坍缩成盘根/工作区祖先必为
 * 解析残渣或越权试探,一旦放行会点燃整盘标注/ACL 机器或把工作区挂进沙箱。
 *
 * <p>自 §13.8 放开后,系统目录(homeDir/dataDir/skillsDir/programDir)不再是
 * 「过度宽泛根」,它们与普通工作区外目录一样走授权决议链(弹窗/AI 审议)。
 */
@Component
public class OverBroadRootCheck implements PermissionCheck {

    @Override
    public PermissionDecision check(PermissionContext ctx) {
        if (ctx.realPath() != null
                && isOverBroadRoot(ctx.realPath(), ctx.wsLex(), ctx.wsReal())) {
            return PermissionDecision.denySilently("过度宽泛授权根拒收: " + ctx.realPath());
        }
        return PermissionDecision.skip();
    }

    /**
     * 过度宽泛授权根谓词(§13.3 修复 B,<b>单点实现,L1/L2 共用</b>):
     * ① 文件系统根;② 工作区祖先或自身。命中即拒收。
     * <p>参数可空(消费层缺上下文时按可判定的子集退化)。
     */
    public static boolean isOverBroadRoot(Path root, Path wsLex, Path wsReal) {
        if (root == null) {
            return false;
        }
        Path norm = root.toAbsolutePath().normalize();
        if (norm.getRoot() != null && norm.getRoot().equals(norm)) {
            return true; // ① 文件系统根
        }
        return covers(norm, wsLex) || covers(norm, wsReal); // ② 工作区祖先或自身
    }

    /** a 覆盖 b(b 在 a 之下或与之相等;startsWith 自带平台大小写语义)。null 安全。 */
    private static boolean covers(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        return b.toAbsolutePath().normalize().startsWith(a);
    }
}