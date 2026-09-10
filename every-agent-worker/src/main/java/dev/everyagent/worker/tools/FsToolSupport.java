package dev.everyagent.worker.tools;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.Sandbox;
import dev.everyagent.worker.modules.WorkspaceManager;

import dev.everyagent.worker.rpc.NotFoundException;
import dev.everyagent.worker.rpc.SandboxViolationException;
import dev.everyagent.worker.task.TaskEntry;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 工具层共享的文件能力(移植自 novel_agent-n 的 fileAccessGateway):
 * 所有路径相对「任务工作区根」({@link TaskEntry#workspaceRoot}),经 {@link WorkspaceManager}
 * + {@link Sandbox} 沙箱化(越界/符号链接逃逸即拒),与 RPC 层 FsService 同一套安全模型。
 *
 * <p>危险操作授权:工作区外的读/写在解析前经 {@link PermissionGate} 判定(阻塞弹窗授权
 * 或 AI 审议,拒绝/超时抛 PermissionDeniedException → 「[工具执行失败] ...」回灌模型);
 * 已授权目录作为 Sandbox 附加根放行。skills 目录只读经 {@link dev.everyagent.worker.tools.permission.SkillsReadAllowCheck}
 * 放行,此处作为 Sandbox 只读附加根传入——写操作仍被 gate 拒绝,沙箱层的根不构成写通道。
 * 其余系统目录/程序目录与普通工作区外目录同权走授权决议链。agentId 用于授权弹窗的
 * 事件路由(主/子 agent 各自真实 Id)。
 *
 * <p>写/建/移/删操作后广播 {@code fs.changed}(带 kind 与 workspace),前端资源管理器据此刷新。
 * 这是 agent 文件工具的统一 IO 底座,FileTools 复用它,不各自造轮子。
 */
@Component
public class FsToolSupport {

    private final WorkspaceManager workspaces;
    private final WorkerProperties props;
    private final HubPool pool;
    private final PermissionGate gate;

    /** 系统技能目录只读附加根缓存(skills 读免授权,§13.8;懒解析)。 */
    private volatile List<Path> skillsReadonlyRoots;

    public FsToolSupport(WorkspaceManager workspaces, WorkerProperties props, HubPool pool,
            PermissionGate gate) {
        this.workspaces = workspaces;
        this.props = props;
        this.pool = pool;
        this.gate = gate;
    }

    /** 目录列举条目(路径为相对工作区根、'/' 分隔的显示名)。 */
    public record Entry(String name, boolean dir, long size, long mtimeMs, String rel) {
    }

    /** 文件元信息。 */
    public record Stat(boolean dir, long size, long mtimeMs) {
    }

    /** 单次文件读取字节上限(防止 cat/grep/wc 把超大文件整进内存撑爆 JVM)。 */
    public static final long MAX_FILE_READ_BYTES = 5_000_000;

    /** 目录递归遍历深度上限(防 grep -r 等深递归失控)。 */
    public static final int MAX_WALK_DEPTH = 24;

    /** 目录递归遍历文件数上限(防超大目录树遍历失控)。 */
    public static final int MAX_WALK_FILES = 200_000;

    /** 读取结果(文本 + 是否因超过上限被截断)。 */
    public record ReadResult(String text, boolean truncated) {
    }

    /**
     * 按任务工作区根 + 已授权外部根绑定沙箱(附带系统技能目录只读根,skills 读免授权 §13.8;
     * 另并入该工作区外部授权根——用户显式选择=已授权,§7.17,read_file/write_text 等
     * 经 gate 放行环后由沙箱直接放行,与 extraRoots 去重)。
     */
    private Sandbox sandbox(TaskEntry t) throws IOException {
        List<Path> roots = new ArrayList<>(gate.extraRoots(t.taskId));
        for (Path ext : workspaces.externalRootsOf(t.workspaceRoot)) {
            if (!roots.contains(ext)) {
                roots.add(ext); // externalRoots 为 realpath 形态,与授权根重叠时去重
            }
        }
        roots.addAll(skillsReadonlyRoots());
        return new Sandbox(workspaces.resolve(t.workspaceRoot), roots);
    }

    /** 系统技能目录只读附加根(realpath + 词法形态;懒解析一次)。 */
    private List<Path> skillsReadonlyRoots() {
        List<Path> cached = skillsReadonlyRoots;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (skillsReadonlyRoots == null) {
                List<Path> built = new ArrayList<>();
                Path lexical = props.resolveSkillsDir();
                try {
                    Path real = lexical.toRealPath();
                    built.add(real);
                    if (!real.equals(lexical)) {
                        built.add(lexical);
                    }
                } catch (IOException e) {
                    // 技能目录尚未物化:不加根(其下路径本就按 NotFound 报错),不阻断
                }
                skillsReadonlyRoots = List.copyOf(built);
            }
            return skillsReadonlyRoots;
        }
    }

    /** 授权解析已存在路径:工作区内/已授权为无感直通,越界则先经授权门(阻塞)。 */
    private Path resolveExistingAuthorized(TaskEntry t, String agentId, String rel,
            PermissionGate.Op op) throws IOException {
        gate.requirePath(t, agentId, rel, op);
        return sandbox(t).resolveExisting(rel);
    }

    /** 授权解析写入目标(可不存在):同 {@link #resolveExistingAuthorized}。 */
    private Path resolveTargetAuthorized(TaskEntry t, String agentId, String rel,
            PermissionGate.Op op) throws IOException {
        gate.requirePath(t, agentId, rel, op);
        return sandbox(t).resolveTarget(rel);
    }

    /**
     * 读取文本(UTF-8)。op 指定授权档:纯读 READ;「写前读」(update_file 回读、append 拼接)
     * 传 WRITE,随写授权一并覆盖,避免一次写操作弹两次窗。
     */
    public String readText(TaskEntry t, String agentId, String rel, PermissionGate.Op op)
            throws IOException {
        Path f = resolveExistingAuthorized(t, agentId, rel, op);
        return Files.readString(f, StandardCharsets.UTF_8);
    }

    /** 读取文本(UTF-8,读授权档)。 */
    public String readText(TaskEntry t, String agentId, String rel) throws IOException {
        return readText(t, agentId, rel, PermissionGate.Op.READ);
    }

    /** 读取文本(UTF-8),最多 maxBytes 字节;超过则截断并标记 truncated,避免整文件进内存。 */
    public ReadResult readCapped(TaskEntry t, String agentId, String rel, long maxBytes)
            throws IOException {
        Path f = resolveExistingAuthorized(t, agentId, rel, PermissionGate.Op.READ);
        long size = Files.size(f);
        boolean truncated = size > maxBytes;
        long toRead = Math.min(size, maxBytes);
        byte[] buf = new byte[(int) toRead];
        int off = 0;
        try (InputStream in = Files.newInputStream(f)) {
            int n;
            while (off < toRead && (n = in.read(buf, off, (int) (toRead - off))) != -1) {
                off += n;
            }
        }
        return new ReadResult(new String(buf, 0, off, StandardCharsets.UTF_8), truncated);
    }

    /**
     * 路径是否存在(不弹窗)。越界路径按文件系统实况返回存在性(访问仍须授权,
     * 但保证 create_file「文件已存在」覆盖守卫对工作区外路径同样诚实)。
     */
    public boolean exists(TaskEntry t, String rel) {
        try {
            Sandbox sb = sandbox(t);
            try {
                sb.resolveExisting(rel);
                return true;
            } catch (SandboxViolationException e) {
                return Files.exists(sb.root().resolve(rel).normalize());
            } catch (NotFoundException e) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
    }

    /** 写入文本;append=true 时拼接已有内容。写后广播 fs.changed(write)。 */
    public void writeText(TaskEntry t, String agentId, String rel, String content, boolean append)
            throws IOException {
        Sandbox sb = sandbox(t);
        String finalText = content == null ? "" : content;
        if (append) {
            try {
                // 写前读:按写授权档,避免读/写双弹窗
                Path existing = resolveExistingAuthorized(t, agentId, rel, PermissionGate.Op.WRITE);
                finalText = Files.readString(existing, StandardCharsets.UTF_8) + finalText;
            } catch (NotFoundException | SandboxViolationException ignore) {
                // 原文件不存在则直接写入
            }
        }
        Path target = resolveTargetAuthorized(t, agentId, rel, PermissionGate.Op.WRITE);
        Files.createDirectories(target.getParent());
        Files.writeString(target, finalText, StandardCharsets.UTF_8);
        changed(t, sb.display(target), "write");
    }

    /** 创建目录;recursive=true 时递归创建父目录(已存在幂等)。 */
    public void mkdir(TaskEntry t, String agentId, String rel, boolean recursive) throws IOException {
        Sandbox sb = sandbox(t);
        Path target = resolveTargetAuthorized(t, agentId, rel, PermissionGate.Op.WRITE);
        if (recursive) {
            Files.createDirectories(target);
        } else {
            Files.createDirectory(target);
        }
        changed(t, sb.display(target), "mkdir");
    }

    /** 重命名 / 移动;源不可为工作区根/授权根。源与目标分别按写授权(移动即删源)。 */
    public void move(TaskEntry t, String agentId, String from, String to) throws IOException {
        Sandbox sb = sandbox(t);
        Path src = resolveExistingAuthorized(t, agentId, from, PermissionGate.Op.WRITE);
        sb.requireNotRoot(src);
        Path dst = resolveTargetAuthorized(t, agentId, to, PermissionGate.Op.WRITE);
        Files.createDirectories(dst.getParent());
        Files.move(src, dst);
        changed(t, sb.display(src), "delete");
        changed(t, sb.display(dst), "write");
    }

    /** 删除文件或目录;recursive 递归;force 忽略不存在的路径(不报错)。 */
    public void remove(TaskEntry t, String agentId, String rel, boolean recursive, boolean force)
            throws IOException {
        Sandbox sb = sandbox(t);
        Path target;
        try {
            gate.requirePath(t, agentId, rel, PermissionGate.Op.WRITE);
            target = sb.resolveExisting(rel);
        } catch (NotFoundException | SandboxViolationException e) {
            if (force) {
                return;
            }
            throw e;
        }
        sb.requireNotRoot(target);
        deleteRecursively(target);
        changed(t, sb.display(target), "delete");
    }

    /** 单层列举目录(目录在前、名称升序;隐藏项按调用方决定是否过滤)。 */
    public List<Entry> list(TaskEntry t, String agentId, String rel) throws IOException {
        Sandbox sb = sandbox(t);
        Path dir = resolveExistingAuthorized(t, agentId, rel, PermissionGate.Op.READ);
        if (!Files.isDirectory(dir)) {
            throw new NotFoundException("不是目录: " + rel);
        }
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            ds.forEach(children::add);
        }
        children.sort(Comparator
                .comparing((Path p) -> !Files.isDirectory(p))
                .thenComparing(p -> p.getFileName().toString()));
        List<Entry> out = new ArrayList<>();
        for (Path c : children) {
            boolean isDir = Files.isDirectory(c);
            out.add(new Entry(c.getFileName().toString(), isDir,
                    isDir ? 0 : Files.size(c),
                    Files.getLastModifiedTime(c).toMillis(), sb.display(c)));
        }
        return out;
    }

    /** 单文件 / 目录元信息。 */
    public Stat stat(TaskEntry t, String agentId, String rel) throws IOException {
        Path p = resolveExistingAuthorized(t, agentId, rel, PermissionGate.Op.READ);
        boolean isDir = Files.isDirectory(p);
        return new Stat(isDir, isDir ? 0 : Files.size(p), Files.getLastModifiedTime(p).toMillis());
    }

    /** 递归列举目录下全部文件(跳过 .git);单文件直接返回其自身。返回相对显示路径。 */
    public List<String> walk(TaskEntry t, String agentId, String rel) throws IOException {
        Sandbox sb = sandbox(t);
        Path dir = resolveExistingAuthorized(t, agentId, rel, PermissionGate.Op.READ);
        if (!Files.isDirectory(dir)) {
            return List.of(sb.display(dir));
        }
        List<String> out = new ArrayList<>();
        walkRec(sb, dir, out, 0);
        return out;
    }

    private void walkRec(Sandbox sb, Path dir, List<String> out, int depth) throws IOException {
        if (depth >= MAX_WALK_DEPTH || out.size() >= MAX_WALK_FILES) {
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path c : ds) {
                if (out.size() >= MAX_WALK_FILES) {
                    return;
                }
                if (Files.isDirectory(c)) {
                    if (c.getFileName().toString().equals(".git")) {
                        continue;
                    }
                    walkRec(sb, c, out, depth + 1);
                } else {
                    out.add(sb.display(c));
                }
            }
        }
    }

    /** 广播 fs.changed:path=相对显示路径(工作区外为绝对路径), kind=write/mkdir/delete, workspace=工作区根。 */
    public void changed(TaskEntry t, String displayRel, String kind) {
        ObjectNode payload = Json.obj()
                .put("path", displayRel)
                .put("kind", kind)
                .put("workspace", t.workspaceRoot);
        pool.broadcastEvt("fs.changed", payload);
    }

    /** 递归删除(目录则先删子项);不跨符号链接。 */
    private static void deleteRecursively(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) {
                    deleteRecursively(c);
                }
            }
        }
        Files.deleteIfExists(p);
    }
}
