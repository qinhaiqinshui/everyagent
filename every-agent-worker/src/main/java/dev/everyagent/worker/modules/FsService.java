package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.contract.rpc.Rpc;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.proto.Channels;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.BadParamsException;
import dev.everyagent.worker.rpc.NotFoundException;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/**
 * 工作区文件模块(架构 §5.4 表):fs.list/tree/read/write/mkdir/move/delete,
 * 全部经 Sandbox jailed;每次调用按 workspace 参数绑定工作区根(多工作区并行);
 * 写操作后广播 fs.changed(带 workspace 字段,前端按当前工作区过滤)。
 */
@Component
public class FsService {

    private static final Logger log = LoggerFactory.getLogger(FsService.class);

    /** 单帧内联上限:超过则 rpc.data 分批回传(架构 §5.4)。 */
    private static final int INLINE_MAX = 256 * 1024;
    private static final int CHUNK = 192 * 1024;
    /** 单次写入上限,防协议滥用。 */
    private static final int MAX_WRITE = 16 * 1024 * 1024;

    private final WorkspaceManager workspaces;
    private final HubPool pool;
    private final WorkerProperties props;

    public FsService(RpcDispatcher dispatcher, WorkspaceManager workspaces, HubPool pool,
            WorkerProperties props) {
        this.workspaces = workspaces;
        this.pool = pool;
        this.props = props;

        dispatcher.register(RpcMethods.FS_LIST, this::list);
        dispatcher.register(RpcMethods.FS_REVEAL, this::reveal);
        dispatcher.register(RpcMethods.FS_READ, this::read);
        dispatcher.register(RpcMethods.FS_WRITE, this::write);
        dispatcher.register(RpcMethods.FS_MKDIR, this::mkdir);
        dispatcher.register(RpcMethods.FS_MOVE, this::move);
        dispatcher.register(RpcMethods.FS_DELETE, this::delete);
        dispatcher.register(RpcMethods.FS_BROWSE, this::browse);
    }

    // ---- 方法实现 ----

    private void list(RpcContext ctx) throws IOException {
        Sandbox sb = sandbox(ctx);
        Path dir = sb.resolveExisting(ctx.optStrParam("path", "."));
        if (!Files.isDirectory(dir)) {
            throw new NotFoundException("不是目录: " + ctx.optStrParam("path", "."));
        }
        ArrayNode entries = Json.arr();
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            ds.forEach(children::add);
        }
        children.sort(Comparator
                .comparing((Path p) -> !Files.isDirectory(p))
                .thenComparing(p -> p.getFileName().toString()));
        for (Path c : children) {
            entries.add(entry(c));
        }
        ctx.ok(Json.obj().set("path", Json.toJson(sb.display(dir))).set("entries", entries));
    }

    /**
     * 浏览目录(方案 B):不经 workspace 沙箱,用于「新建工作区选择目录」前的逐层浏览。
     * - path 缺省/空:返回当前文件系统的所有根/盘符(第一层)。
     * - 否则:列出该绝对路径下的直接子目录(仅目录,不列文件)。
     * - 可选 includeFiles=true:文件与目录一起列出,条目带 kind(file/directory,按文件名混排),
     *   响应加 supportsFiles:true 能力标记(前端探测用);缺省 false 时响应与旧契约逐字节兼容。
     * 依赖运行 worker 进程的文件系统权限;无权限/路径非法时 IO 异常透传为 RPC 错误。
     */
    private void browse(RpcContext ctx) throws IOException {
        String raw = ctx.optStrParam("path", "").trim();
        // RPC 层暂无 optBoolParam,按 optStrParam 既有模式解析;
        // 兼容 JSON 布尔(true/false)与字符串("true"/"false")两种携带方式。
        boolean includeFiles = "true".equalsIgnoreCase(ctx.optStrParam("includeFiles", "false").trim());
        if (raw.isEmpty()) {
            ArrayNode roots = Json.arr();
            for (Path root : java.nio.file.FileSystems.getDefault().getRootDirectories()) {
                ObjectNode e = Json.obj().put("path", root.toString()).put("name", root.toString());
                if (includeFiles) {
                    e.put("kind", "directory"); // 盘符/根必为目录
                }
                roots.add(e);
            }
            ObjectNode res = Json.obj().put("isRoot", true);
            if (includeFiles) {
                res.put("supportsFiles", true);
            }
            ctx.ok(res.set("entries", roots));
            return;
        }
        Path dir = Path.of(raw).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new NotFoundException("不是目录: " + dir);
        }
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path c : ds) {
                if (includeFiles || Files.isDirectory(c)) {
                    children.add(c);
                }
            }
        }
        children.sort(Comparator.comparing((Path p) -> p.getFileName().toString()));
        ArrayNode entries = Json.arr();
        for (Path c : children) {
            ObjectNode e = Json.obj().put("path", c.toString()).put("name", c.getFileName().toString());
            if (includeFiles) {
                e.put("kind", Files.isDirectory(c) ? "directory" : "file");
            }
            entries.add(e);
        }
        ObjectNode res = Json.obj().put("isRoot", false).put("path", dir.toString());
        if (includeFiles) {
            res.put("supportsFiles", true);
        }
        ctx.ok(res.set("entries", entries));
    }

    /**
     * 定位文件/目录(懒加载树的 reveal 模式):输入工作区内路径,
     * 返回从根到目标逐段 stat 的节点链(旁支零查找,不列任何子目录内容)。
     * 目标不存在抛 NotFoundException;路径畸形(中间段为文件)时链上如实标记。
     */
    private void reveal(RpcContext ctx) throws IOException {
        Sandbox sb = sandbox(ctx);
        String rel = ctx.strParam("path");
        Path target = sb.resolveExisting(rel); // 校验存在 + 沙箱(realpath 形式)
        Path root = sb.root();
        // 用未 realpath 的规范化路径逐段构造,避免根为符号链接时 relativize 跨根抛错。
        Path norm = root.resolve(rel).normalize();
        ArrayNode chain = Json.arr();
        Path cur = root;
        for (Path segment : root.relativize(norm)) {
            cur = cur.resolve(segment);
            chain.add(entry(cur));
        }
        ctx.ok(Json.obj().put("path", sb.display(target)).set("chain", chain));
    }

    private void read(RpcContext ctx) throws IOException {
        Sandbox sb = sandbox(ctx);
        Path file = sb.resolveExisting(ctx.strParam("path"));
        if (!Files.isRegularFile(file)) {
            throw new NotFoundException("不是文件: " + ctx.strParam("path"));
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length <= INLINE_MAX) {
            ctx.ok(Json.obj()
                    .put("path", sb.display(file))
                    .put("size", bytes.length)
                    .put("base64", Base64.getEncoder().encodeToString(bytes)));
            return;
        }
        // 大文件:base64 分批 rpc.data,末帧 ok 汇总
        Base64.Encoder enc = Base64.getEncoder();
        for (int off = 0; off < bytes.length; off += CHUNK) {
            int len = Math.min(CHUNK, bytes.length - off);
            ObjectNode part = Json.obj()
                    .put("offset", off)
                    .put("size", len)
                    .put("base64", enc.encodeToString(java.util.Arrays.copyOfRange(bytes, off, off + len)));
            ctx.data(List.of(part), off + len < bytes.length);
        }
        ctx.ok(Json.obj().put("path", sb.display(file)).put("size", bytes.length));
    }

    private void write(RpcContext ctx) throws IOException {
        Sandbox sb = sandbox(ctx);
        Path target = sb.resolveTarget(ctx.strParam("path"));
        // contentBase64 允许空串/缺省 = 写空文件(新建空文件场景),不是参数缺失。
        byte[] content = decode(ctx.optStrParam("contentBase64", ""));
        if (content.length > MAX_WRITE) {
            throw new BadParamsException("单次写入超过上限 " + MAX_WRITE + " 字节");
        }
        Files.createDirectories(target.getParent());
        Files.write(target, content);
        changed(ctx, sb, sb.display(target), "write");
        ctx.ok(Json.obj().put("path", sb.display(target)).put("size", content.length));
    }

    private void mkdir(RpcContext ctx) throws IOException {
        Sandbox sb = sandbox(ctx);
        Path target = sb.resolveTarget(ctx.strParam("path"));
        Files.createDirectories(target);
        changed(ctx, sb, sb.display(target), "mkdir");
        ctx.ok(Json.obj().put("path", sb.display(target)));
    }

    private void move(RpcContext ctx) throws IOException {
        Sandbox sb = sandbox(ctx);
        Path from = sb.resolveExisting(ctx.strParam("from"));
        sb.requireNotRoot(from);
        Path to = sb.resolveTarget(ctx.strParam("to"));
        Files.createDirectories(to.getParent());
        Files.move(from, to);
        String display = sb.display(from);
        changed(ctx, sb, display, "delete");
        changed(ctx, sb, sb.display(to), "write");
        ctx.ok(Json.obj().put("from", display).put("to", sb.display(to)));
    }

    private void delete(RpcContext ctx) throws IOException {
        Sandbox sb = sandbox(ctx);
        Path target = sb.resolveExisting(ctx.strParam("path"));
        sb.requireNotRoot(target);
        deleteRecursively(target);
        changed(ctx, sb, sb.display(target), "delete");
        ctx.ok(Json.obj().put("path", sb.display(target)));
    }

    // ---- 内部 ----

    /** 按调用的 workspace 参数(必填)绑定沙箱。 */
    private Sandbox sandbox(RpcContext ctx) throws IOException {
        return new Sandbox(workspaces.resolve(ctx.strParam("workspace")));
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new BadParamsException("contentBase64 不是合法 base64");
        }
    }

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

    private ObjectNode entry(Path p) throws IOException {
        ObjectNode o = Json.obj();
        o.put("name", p.getFileName().toString());
        o.put("dir", Files.isDirectory(p));
        o.put("size", Files.isDirectory(p) ? 0 : Files.size(p));
        o.put("modifiedTs", Files.getLastModifiedTime(p).toMillis());
        return o;
    }

    /** fs.changed 通知发往操作发起者的全部 hub 连接(按 ownerKey 扇出)。 */
    private void changed(RpcContext ctx, Sandbox sb, String path, String kind) {
        ObjectNode payload = Json.obj().put("path", path).put("kind", kind)
                .put("workspace", sb.root().toString());
        pool.pubForOwner(ctx.ownerKey(),
                Channels.workerEvt(ctx.ownerKey(), props.getWorkerId()),
                "fs.changed", null, payload, null);
    }
}
