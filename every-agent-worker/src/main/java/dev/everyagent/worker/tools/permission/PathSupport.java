package dev.everyagent.worker.tools.permission;

import dev.everyagent.worker.tools.PermissionGate.Op;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 授权路径纯函数工具(门面与各节点共用,不依赖 Spring 组件):
 * 授权根粒度提升 / 最深已存在祖先 / grant key 构造 / 文案缩写。
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
     * 授权根粒度:已存在文件提升到父目录(与弹窗文案「及其子目录」一致,同目录不再反复弹);
     * 盘符根下的文件不提升(避免一次授权覆盖整个盘),目录即自身。
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