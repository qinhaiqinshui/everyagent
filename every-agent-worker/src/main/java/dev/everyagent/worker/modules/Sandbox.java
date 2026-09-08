package dev.everyagent.worker.modules;

import dev.everyagent.worker.rpc.NotFoundException;
import dev.everyagent.worker.rpc.SandboxViolationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 工作区沙箱(架构 §13.6/§5.9):fs.* / git.* 一律 jailed 到构造时绑定的
 * WorkspaceManager.Root(多工作区并行,每次调用按 workspace 参数解析出 Root)。
 * 路径先规范化再校验前缀;已存在路径走 realpath 防符号链接逃逸;
 * 写入目标(可不存在)对父目录做 realpath 后再拼文件名。
 *
 * <p>授权附加根:用户经授权弹窗(kind=authorization)放行的工作区外目录(realpath)
 * 作为额外合法根传入,前缀/realpath 双校验改为「工作区根 ∨ 任一附加根」。
 * 系统技能目录只读根(§13.8)同经此参数传入——只读性由 PermissionGate 写硬拒保证,
 * 沙箱只管防逃逸。沙箱不可变、按调用构造,授权发生在上一次调用的阻塞期间,
 * 后续调用自然看到新根。
 */
public final class Sandbox {

    private final WorkspaceManager.Root root;
    private final List<Path> extraRoots;

    public Sandbox(WorkspaceManager.Root root) {
        this(root, List.of());
    }

    public Sandbox(WorkspaceManager.Root root, List<Path> extraRoots) {
        this.root = root;
        this.extraRoots = extraRoots == null ? List.of() : List.copyOf(extraRoots);
    }

    /** 绑定的工作区根(normalize 后绝对路径)。 */
    public Path root() {
        return root.path();
    }

    /** 附加根(授权目录 + 系统只读根;空 = 无越界授权)。 */
    public List<Path> extraRoots() {
        return extraRoots;
    }

    /** 相对工作区根的显示路径(事件/返回值用 / 分隔);工作区外(授权根内)返回绝对路径。 */
    public String display(Path p) throws IOException {
        Path realRoot = root.realPath();
        if (!p.startsWith(realRoot)) {
            return p.toString().replace('\\', '/');
        }
        String rel = realRoot.relativize(p).toString().replace('\\', '/');
        return rel.isEmpty() ? "." : rel;
    }

    /** 解析已存在路径:normalize + realpath,双前缀校验(工作区根 ∨ 任一授权附加根)。 */
    public Path resolveExisting(String rel) throws IOException {
        Path norm = root.path().resolve(rel).normalize();
        if (!allowed(norm)) {
            throw new SandboxViolationException("路径越界: " + rel);
        }
        Path real;
        try {
            real = norm.toRealPath();
        } catch (java.nio.file.NoSuchFileException e) {
            throw new NotFoundException("路径不存在: " + rel);
        }
        if (!allowedReal(real)) {
            throw new SandboxViolationException("符号链接逃逸: " + rel);
        }
        return real;
    }

    /** 解析写入目标(可不存在):父目录(按需创建)realpath + 文件名,防中间符号链接逃逸。 */
    public Path resolveTarget(String rel) throws IOException {
        Path norm = root.path().resolve(rel).normalize();
        if (!allowed(norm) || isRootItself(norm)) {
            throw new SandboxViolationException("路径越界: " + rel);
        }
        Path parent = norm.getParent();
        if (!Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Path target = parent.toRealPath().resolve(norm.getFileName());
        if (!allowedReal(target)) {
            throw new SandboxViolationException("路径越界: " + rel);
        }
        return target;
    }

    /** 破坏性操作(delete/move 源)禁止作用于工作区根本身或任一授权根本身。 */
    public void requireNotRoot(Path p) {
        if (p.equals(root.path()) || p.equals(root.realPath())) {
            throw new SandboxViolationException("不能对工作区根执行该操作");
        }
        for (Path extra : extraRoots) {
            if (p.equals(extra)) {
                throw new SandboxViolationException("不能对授权目录根本身执行该操作");
            }
        }
    }

    /** 词法前缀放行:工作区根 ∨ 任一授权附加根。 */
    private boolean allowed(Path norm) {
        if (norm.startsWith(root.path())) {
            return true;
        }
        for (Path extra : extraRoots) {
            if (norm.startsWith(extra)) {
                return true;
            }
        }
        return false;
    }

    /** realpath 前缀放行:工作区根 realpath ∨ 任一授权附加根(本就是 realpath)。 */
    private boolean allowedReal(Path real) {
        if (real.startsWith(root.realPath())) {
            return true;
        }
        for (Path extra : extraRoots) {
            if (real.startsWith(extra)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRootItself(Path norm) {
        if (norm.equals(root.path())) {
            return true;
        }
        for (Path extra : extraRoots) {
            if (norm.equals(extra)) {
                return true;
            }
        }
        return false;
    }
}
