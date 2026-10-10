package dev.everyagent.worker.tools.permission;

import dev.everyagent.worker.tools.PermissionGate.Op;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 授权路径纯函数工具(门面与各节点共用,不依赖 Spring 组件):
 * 授权单元推导 / 最深已存在祖先 / grant key 构造 / 授权范围文案 / 文案缩写。
 *
 * <p>两种粒度并存,各有归属(§7.8):
 * <ul>
 *   <li>{@link #grantUnitOf}——<b>文件工具链</b>的授权单元 = 目标路径本身(已存在前缀取
 *       realpath)。「新建/覆写一个文件」的授权不放大成该目录下其它文件的写权限;</li>
 *   <li>{@link #grantRootOf}——<b>命令 EXEC 链</b>的授权根 = 已存在目标提升到父目录
 *       (命令串只能静态看到已存在路径,按其所在目录归并,同目录命令不再反复弹)。</li>
 * </ul>
 */
public final class PathSupport {

    private PathSupport() {
    }

    /** 最深已存在祖先(自身存在即自身;一路向上;null = 连盘符根都不存在)。 */
    public static Path deepestExisting(Path p) {
        Path cur = p;
        while (cur != null) {
            if (Files.exists(cur)) {
                return cur;
            }
            cur = cur.getParent();
        }
        return null;
    }

    /**
     * <b>命令 EXEC 链</b>的授权根:已存在文件提升到父目录(命令串只能静态看到已存在路径,
     * 按所在目录归并 → 同目录命令不再反复弹);盘符根下的文件不提升(避免一次授权覆盖整个盘),
     * 目录即自身。仅 {@code CommandCheck} 使用(文件工具链走 {@link #grantUnitOf})。
     */
    public static Path grantRootOf(Path anchor) {
        if (Files.isRegularFile(anchor)) {
            Path parent = anchor.getParent();
            if (parent != null && parent.getParent() != null) {
                return parent;
            }
        }
        return anchor;
    }

    /**
     * <b>文件工具链</b>的授权单元(§7.8)= <b>目标路径本身</b>:已存在前缀取 realpath,
     * 其余段按词法规范化拼接。
     *
     * <p>不再像 {@link #grantRootOf} 那样把「已存在文件」提升到父目录——那会让一次
     * 「新建/覆写单个文件」的授权静默放大成「该目录下任意文件的写权限」(同目录平级文件
     * 共享同一把 key)。授权单元下沉后:同目录的另一个文件 = 另一个单元 = 另一次授权;
     * 同一路径重复访问仍共用同一把 key(run/task 两档语义不变)——
     * 代价是同目录多文件需多次授权,由弹窗的「范围」维度或批量化另行优化。
     *
     * @param norm   目标路径(已 normalize;可不存在)
     * @param anchor {@link #deepestExisting}({@code norm}) 的结果(目标存在即目标自身)
     */
    public static Path grantUnitOf(Path norm, Path anchor) throws IOException {
        Path realAnchor = anchor.toRealPath();
        Path remainder = anchor.relativize(norm); // 目标存在 → 空;不存在 → 待建段
        return remainder.toString().isEmpty()
                ? realAnchor
                : realAnchor.resolve(remainder).normalize();
    }

    /**
     * 授权范围文案(与 {@link #grantUnitOf} 判定<b>同源</b>,§7.8:文案不得比实际宽)。
     * 目标存在且为目录 → 该目录本身;目标为已存在文件 → 该文件;目标不存在 → 该待建路径。
     */
    public static String scopeNote(Path norm, Path anchor) {
        if (!Files.exists(norm)) {
            return "(仅此待建路径,不含其所在目录内的其它文件)";
        }
        return Files.isDirectory(anchor)
                ? "(仅该目录本身,不含其子目录内的文件)"
                : "(仅此文件,不含其所在目录内的其它文件)";
    }

    public static String pathKey(Path real, Op op) {
        return "p::" + op.name().toLowerCase() + "::" + real;
    }

    public static String verbKey(String verb) {
        return "c::" + verb;
    }

    public static String privKey(String verb) {
        return "priv::" + verb;
    }

    public static String opDesc(Op op) {
        return switch (op) {
            case READ -> "读取";
            case WRITE -> "写入";
            case EXEC -> "访问";
        };
    }

    public static String abbreviate(String s) {
        return s == null ? "" : (s.length() <= 200 ? s : s.substring(0, 200) + "...");
    }
}