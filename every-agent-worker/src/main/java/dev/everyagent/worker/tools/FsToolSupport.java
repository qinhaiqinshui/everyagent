package dev.everyagent.worker.tools;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.Sandbox;
import dev.everyagent.worker.modules.SkillsReadonlyRoots;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.os.SandboxPathRegistry;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.exception.NotFoundException;
import dev.everyagent.plugin.api.util.AtomicFiles;
import dev.everyagent.worker.rpc.SandboxViolationException;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 工具层共享的文件能力(移植自 novel_agent-n 的 fileAccessGateway):
 * 所有路径相对「任务工作区根」({@code ExecContext.workspaceRoot()}),经 {@link WorkspaceManager}
 * + {@link Sandbox} 沙箱化(越界/符号链接逃逸即拒),与 RPC 层 FsService 同一套安全模型。
 *
 * <p>危险操作授权:工作区外的读/写在解析前经 {@link PermissionGate} 判定(阻塞弹窗授权
 * 或 AI 审议,拒绝/超时抛 PermissionDeniedException → 「[工具执行失败] ...」回灌模型);
 * 已授权目录作为 Sandbox 附加根放行。skills 目录只读经 {@link dev.everyagent.worker.tools.permission.SkillsReadAllowCheck}
 * 放行,此处作为 Sandbox 只读附加根传入——写操作仍被 gate 拒绝,沙箱层的根不构成写通道。
 * 其余系统目录/程序目录与普通工作区外目录同权走授权决议链。agentId 用于授权弹窗的
 * 事件路由(主/子 agent 各自真实 Id)。
 *
 * <p>路径翻译:AI 在沙箱内可能使用沙箱内路径（如 /c/Users/.../file）,经
 * {@link SandboxPathRegistry#toHostPath} 翻译为宿主路径;无映射则原样保留,
 * 让 Java NIO 自然报错,AI 改用沙箱命令工具。DIRECT/无沙箱场景原样返回。
 *
 * <p>写/建/移/删操作后广播 {@code fs.changed}(带 kind 与 workspace),前端资源管理器据此刷新。
 */
@Component
public class FsToolSupport {

    private final WorkspaceManager workspaces;
    private final WorkerProperties props;
    private final HubPool pool;
    private final PermissionGate gate;
    private final OsSandbox osSandbox;
    private final SandboxPathRegistry pathRegistry;

    /** 系统技能目录只读附加根解析(skills 读免授权,§13.8;懒解析、共享实现)。 */
    private final SkillsReadonlyRoots skillsReadonlyRoots;

    /**
     * 同进程内「按路径串行」的写锁:read-modify-write(update_file)必须整体串行,
     * 否则两个写入者(主 agent 与派生 agent 并发、多个工具调用并发)会各自读到旧快照、
     * 各写一份,造成丢失更新 / 截断。键为规范化路径串;仅本进程内有效(跨进程由原子替换兜底)。
     */
    private final ConcurrentHashMap<String, ReentrantLock> pathLocks = new ConcurrentHashMap<>();

    public FsToolSupport(WorkspaceManager workspaces, WorkerProperties props, HubPool pool,
            PermissionGate gate) {
        this(workspaces, props, pool, gate, null, null);
    }

    // 双构造器须显式指定注入用哪个,否则 Spring 无法抉择回退找无参构造(测试用 4 参重载)
    @Autowired
    public FsToolSupport(WorkspaceManager workspaces, WorkerProperties props, HubPool pool,
            PermissionGate gate, OsSandbox osSandbox, SandboxPathRegistry pathRegistry) {
        this.workspaces = workspaces;
        this.props = props;
        this.pool = pool;
        this.gate = gate;
        this.osSandbox = osSandbox;
        this.pathRegistry = pathRegistry;
        this.skillsReadonlyRoots = new SkillsReadonlyRoots(props);
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
    private Sandbox sandbox(ExecContext t) throws IOException {
        List<Path> roots = new ArrayList<>(gate.extraRoots(t.subjectId()));
        for (Path ext : workspaces.externalRootsOf(t.workspaceRoot())) {
            if (!roots.contains(ext)) {
                roots.add(ext);
            }
        }
        roots.addAll(skillsReadonlyRoots.get());
        return new Sandbox(workspaces.resolve(t.workspaceRoot()), roots);
    }

    /**
     * AI 视角路径 → 宿主路径翻译。
     * 沙箱内路径（如 /c/Users/.../file）经 SandboxPathRegistry 翻译为宿主路径;
     * 无映射则原样返回（注册表外路径,Java NIO 自然报错）。
     * DIRECT/无沙箱场景原样返回。
     */
    private String resolveSandboxPath(String rel) {
        if (pathRegistry == null || rel == null || rel.isBlank()) {
            return rel;
        }
        String host = pathRegistry.toHostPath(rel);
        return host != null ? host : rel;
    }

    /** 授权解析已存在路径:工作区内/已授权为无感直通,越界则先经授权门(阻塞)。 */
    private Path resolveExistingAuthorized(ExecContext t, String agentId, String rel,
            PermissionGate.Op op) throws IOException {
        String resolved = resolveSandboxPath(rel);
        gate.requirePath(t, agentId, resolved, op);
        return sandbox(t).resolveExisting(resolved);
    }

    /** 授权解析写入目标(可不存在):同 {@link #resolveExistingAuthorized}。 */
    private Path resolveTargetAuthorized(ExecContext t, String agentId, String rel,
            PermissionGate.Op op) throws IOException {
        String resolved = resolveSandboxPath(rel);
        gate.requirePath(t, agentId, resolved, op);
        return sandbox(t).resolveTarget(resolved);
    }

    /**
     * 取某路径对应的进程内写锁(供 read-modify-write 使用:读取→修改→写回需整体串行)。
     * 键取规范化后的沙箱→宿主翻译路径,同一文件的不同书写形式(如 ./a.md 与 a.md)也收敛到同一把锁。
     */
    public ReentrantLock pathLock(String rel) {
        String key = rel == null ? "" : rel;
        try {
            key = java.nio.file.Path.of(resolveSandboxPath(key)).normalize().toString();
        } catch (RuntimeException ignore) {
            // 非法路径:退回原始串作键(仍能串行化同名请求,不影响正确性)
        }
        return pathLocks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    public String readText(ExecContext t, String agentId, String rel, PermissionGate.Op op)
            throws IOException {
        Path f = resolveExistingAuthorized(t, agentId, rel, op);
        return Files.readString(f, StandardCharsets.UTF_8);
    }

    public String readText(ExecContext t, String agentId, String rel) throws IOException {
        return readText(t, agentId, rel, PermissionGate.Op.READ);
    }

    public ReadResult readCapped(ExecContext t, String agentId, String rel, long maxBytes)
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

    public boolean exists(ExecContext t, String rel) {
        try {
            Sandbox sb = sandbox(t);
            String resolved = resolveSandboxPath(rel);
            try {
                sb.resolveExisting(resolved);
                return true;
            } catch (SandboxViolationException e) {
                return Files.exists(sb.root().resolve(resolved).normalize());
            } catch (NotFoundException e) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
    }

    public void writeText(ExecContext t, String agentId, String rel, String content, boolean append)
            throws IOException {
        Sandbox sb = sandbox(t);
        String finalText = content == null ? "" : content;
        if (append) {
            try {
                Path existing = resolveExistingAuthorized(t, agentId, rel, PermissionGate.Op.WRITE);
                finalText = Files.readString(existing, StandardCharsets.UTF_8) + finalText;
            } catch (NotFoundException | SandboxViolationException ignore) {
            }
        }
        Path target = resolveTargetAuthorized(t, agentId, rel, PermissionGate.Op.WRITE);
        Files.createDirectories(target.getParent());
        // 原子写:先写同目录临时文件,再原子替换。禁止 truncate 后原地重写——一旦写入被中断
        // (取消/崩溃/AV 与索引短暂持锁)或并发写入者交错,原地写会暴露「截断/空的部分文件」,
        // 而调用方仍可能视作成功,下一次读取就把截断态固化下来。与 TaskStore / GrantRegistry /
        // AgentLedger 同一惯例。(回归:`FsWriteAtomicityTest.atomicWriteNeverExposesPartialContent`
        // 已验证:改回原地写即会读到 0 字符的半截文件。)
        Path tmp = Files.createTempFile(target.getParent(),
                "." + target.getFileName().toString() + ".", ".tmp");
        try {
            Files.writeString(tmp, finalText, StandardCharsets.UTF_8);
            AtomicFiles.replace(tmp, target);
        } finally {
            Files.deleteIfExists(tmp); // 成功时 tmp 已被 move 走;失败时清理残留
        }
        // 写后校验:落盘内容必须与预期逐字符一致。把「静默截断 / 并发覆盖」变成显式失败,
        // 而不是留下一个没人发现的短文件。
        String landed = Files.readString(target, StandardCharsets.UTF_8);
        if (!landed.equals(finalText)) {
            throw new IOException("写入校验失败:落盘内容与预期不一致(疑似写入中断或并发修改): "
                    + sb.display(target) + "(期望 " + finalText.length()
                    + " 字符,实际 " + landed.length() + " 字符)");
        }
        changed(t, sb.display(target), "write");
    }

    public void mkdir(ExecContext t, String agentId, String rel, boolean recursive) throws IOException {
        Sandbox sb = sandbox(t);
        Path target = resolveTargetAuthorized(t, agentId, rel, PermissionGate.Op.WRITE);
        if (recursive) {
            Files.createDirectories(target);
        } else {
            Files.createDirectory(target);
        }
        changed(t, sb.display(target), "mkdir");
    }

    public void move(ExecContext t, String agentId, String from, String to) throws IOException {
        Sandbox sb = sandbox(t);
        Path src = resolveExistingAuthorized(t, agentId, from, PermissionGate.Op.WRITE);
        sb.requireNotRoot(src);
        Path dst = resolveTargetAuthorized(t, agentId, to, PermissionGate.Op.WRITE);
        Files.createDirectories(dst.getParent());
        Files.move(src, dst);
        changed(t, sb.display(src), "delete");
        changed(t, sb.display(dst), "write");
    }

    public void remove(ExecContext t, String agentId, String rel, boolean recursive, boolean force)
            throws IOException {
        Sandbox sb = sandbox(t);
        String resolved = resolveSandboxPath(rel);
        Path target;
        try {
            gate.requirePath(t, agentId, resolved, PermissionGate.Op.WRITE);
            target = sb.resolveExisting(resolved);
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

    public List<Entry> list(ExecContext t, String agentId, String rel) throws IOException {
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

    public Stat stat(ExecContext t, String agentId, String rel) throws IOException {
        Path p = resolveExistingAuthorized(t, agentId, rel, PermissionGate.Op.READ);
        boolean isDir = Files.isDirectory(p);
        return new Stat(isDir, isDir ? 0 : Files.size(p), Files.getLastModifiedTime(p).toMillis());
    }

    public List<String> walk(ExecContext t, String agentId, String rel) throws IOException {
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

    public void changed(ExecContext t, String displayRel, String kind) {
        ObjectNode payload = Json.obj()
                .put("path", displayRel)
                .put("kind", kind)
                .put("workspace", t.workspaceRoot());
        pool.broadcastEvt("fs.changed", payload);
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) {
                    deleteRecursively(c);
                }
            }
        }
        deleteOne(p);
    }

    private static void deleteOne(Path p) throws IOException {
        try {
            Files.deleteIfExists(p);
        } catch (AccessDeniedException e) {
            try {
                Files.setAttribute(p, "dos:readonly", false);
            } catch (UnsupportedOperationException | IOException ignored) {
                throw e;
            }
            Files.deleteIfExists(p);
        }
    }
}
