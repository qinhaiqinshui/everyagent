package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.git.NativeGit;
import dev.everyagent.worker.git.NativeGit.CredentialSpec;
import dev.everyagent.worker.git.NativeGit.NativeResult;
import dev.everyagent.worker.git.NativeGit.StatusData;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.AuthRequiredException;
import dev.everyagent.worker.rpc.BadParamsException;
import dev.everyagent.worker.rpc.NotFoundException;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.rpc.RpcContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作区 git 模块(架构 §5.4 表):status/log/diff/commit/pull/push 快操作。
 * 实现由 JGit 迁移为<b>原生 git argv 直传</b>(docs/GIT_NATIVE_MIGRATION.md §7.12):
 * 每个 RPC 构造 git 参数 → {@link NativeGit} 在宿主 OS 执行(经 OsSandbox.spawnNative) →
 * 解析 stdout 为现有契约 JSON。前半的沙箱 jail、workspace 绑定、凭证链语义全部保留。
 * clone/大型迁移按文档建为 Task,不在本模块;push/pull 凭证走原生 git 全套凭证体系
 * (本机 credential.helper / ssh-agent / ~/.ssh + 工作区加密凭证,不经协议)。
 */
@Component
public class GitService {

    private static final int LOG_MAX = 200;

    private final WorkspaceManager workspaces;
    private final GitCredentialStore credentials;
    private final NativeGit git;

    public GitService(RpcDispatcher dispatcher, WorkspaceManager workspaces,
            GitCredentialStore credentials, NativeGit git) {
        this.workspaces = workspaces;
        this.credentials = credentials;
        this.git = git;
        dispatcher.register(RpcMethods.GIT_STATUS, this::status);
        dispatcher.register(RpcMethods.GIT_LOG, this::log);
        dispatcher.register(RpcMethods.GIT_DIFF, this::diff);
        dispatcher.register(RpcMethods.GIT_COMMIT, this::commit);
        dispatcher.register(RpcMethods.GIT_PULL, this::pull);
        dispatcher.register(RpcMethods.GIT_PUSH, this::push);
        dispatcher.register(RpcMethods.GIT_DISCARD, this::discard);
        dispatcher.register(RpcMethods.GIT_INIT, this::init);
        dispatcher.register(RpcMethods.GIT_CLONE, this::clone);
        dispatcher.register(RpcMethods.GIT_REMOTE_ADD, this::remoteAdd);
        dispatcher.register(RpcMethods.GIT_REMOTE_LIST, this::remoteList);
        dispatcher.register(RpcMethods.GIT_CREDENTIAL_SAVE, this::credentialSave);
    }

    private void status(RpcContext ctx) throws IOException {
        Sandbox sb = sandbox(ctx);
        StatusData s = statusData(sb);
        ObjectNode o = Json.obj();
        o.put("branch", branch(sb));
        o.set("added", arr(s.added()));
        o.set("changed", arr(s.changed()));
        o.set("modified", arr(s.modified()));
        o.set("removed", arr(s.removed()));
        o.set("missing", arr(s.missing()));
        o.set("untracked", arr(s.untracked()));
        o.set("conflicting", arr(s.conflicting()));
        ctx.ok(o);
    }

    private void log(RpcContext ctx) throws IOException {
        int max = (int) Math.min(ctx.optLongParam("max", 50), LOG_MAX);
        Sandbox sb = sandbox(ctx);
        NativeResult r = git.runRead(sb.root(), List.of(
                "log", "-n", String.valueOf(max), "-z",
                "--format=%H%x1f%h%x1f%an%x1f%ae%x1f%ct%x1f%s"), CredentialSpec.none());
        if (r.exitCode() != 0) {
            if (NativeGit.isNotRepo(r)) {
                throw new NotFoundException("工作区不是 git 仓库");
            }
            throw new RuntimeException("git.log 失败: " + (r.stderr() == null ? "" : r.stderr()));
        }
        ArrayNode commits = Json.arr();
        for (String rec : r.stdout().split("\0", -1)) {
            if (rec.isEmpty()) {
                continue;
            }
            String[] f = rec.split("\u001f", -1);
            if (f.length < 6) {
                continue;
            }
            ObjectNode n = Json.obj();
            n.put("id", f[0]);
            n.put("shortId", f[1]);
            n.put("author", f[2]);
            n.put("email", f[3]);
            try {
                n.put("ts", Long.parseLong(f[4]) * 1000L);
            } catch (NumberFormatException e) {
                n.put("ts", 0L);
            }
            n.put("message", f[5]);
            commits.add(n);
        }
        ctx.ok(Json.obj().set("commits", commits));
    }

    /**
     * 读取某文件相对 HEAD 的完整变更内容(before = HEAD blob 文本,after = 工作区文件文本)。
     * 对齐 old 链路:直接读两份全文,`git show HEAD:<path>` 拿 before(新文件 = HEAD 无 blob → created)。
     */
    private void diff(RpcContext ctx) throws IOException {
        Sandbox sb = sandbox(ctx);
        String path = ctx.optStrParam("path", null);
        if (path == null || path.isEmpty()) {
            throw new BadParamsException("git.diff 需要 path 参数");
        }
        Path worktreePath = sb.resolveExisting(path); // 沙箱内真实路径(同时校验不越界)
        String before = "";
        NativeResult show = git.runRead(sb.root(), List.of("show", "HEAD:" + path), CredentialSpec.none());
        if (show.exitCode() == 0) {
            before = show.stdout();
        } else if (NativeGit.isNotRepo(show)) {
            throw new NotFoundException("工作区不是 git 仓库");
        }
        // before 空 = 新文件(HEAD 无该 blob 或仓库尚无提交)
        String changeType = before.isEmpty() ? "created" : "updated";
        String after = Files.exists(worktreePath) && !Files.isDirectory(worktreePath)
                ? Files.readString(worktreePath, StandardCharsets.UTF_8)
                : "";
        ctx.ok(Json.obj()
                .put("filePath", path)
                .put("changeType", changeType)
                .put("beforeContent", before)
                .put("afterContent", after)
                .put("empty", before.equals(after)));
    }

    private void commit(RpcContext ctx) throws IOException {
        Sandbox sb = sandbox(ctx);
        String message = ctx.strParam("message");
        JsonNode pathsNode = ctx.params().path("paths");
        List<String> paths = new ArrayList<>();
        if (pathsNode.isArray()) {
            pathsNode.forEach(n -> paths.add(n.asString()));
        }
        List<String> addArgs = new ArrayList<>(List.of("add", "-A", "--"));
        if (paths.isEmpty()) {
            addArgs.add(".");
        } else {
            for (String p : paths) {
                sb.resolveLoose(p); // 已删除(缺失)文件也允许提交删除,仅校验沙箱不越界
            }
            addArgs.addAll(paths);
        }
        NativeResult add = git.runWrite(sb.root(), addArgs, CredentialSpec.none());
        if (!add.ok()) {
            if (NativeGit.isNotRepo(add)) {
                throw new NotFoundException("工作区不是 git 仓库");
            }
            throw new RuntimeException("git add 失败: " + (add.stderr() == null ? "" : add.stderr()));
        }
        NativeResult c = git.runWrite(sb.root(), List.of("commit", "-m", message), CredentialSpec.none());
        if (c.exitCode() != 0) {
            if (NativeGit.isNotRepo(c)) {
                throw new NotFoundException("工作区不是 git 仓库");
            }
            throw new RuntimeException("git commit 失败: " + (c.stderr() == null ? "" : c.stderr()));
        }
        NativeResult head = git.runRead(sb.root(), List.of("rev-parse", "HEAD"), CredentialSpec.none());
        String fullId = head.ok() ? head.stdout().trim() : "";
        ctx.ok(Json.obj()
                .put("commitId", fullId)
                .put("shortId", fullId.length() > 8 ? fullId.substring(0, 8) : fullId)
                .put("message", message));
    }

    private void pull(RpcContext ctx) throws IOException {
        Sandbox sb = sandbox(ctx);
        String url = originUrl(sb);
        NativeResult r = withAuth(ctx, sb, url, List.of("pull", "--no-rebase"));
        if (r.exitCode() != 0 && NativeGit.isNotRepo(r)) {
            throw new NotFoundException("工作区不是 git 仓库");
        }
        boolean conflicting = hasConflicts(sb);
        ObjectNode o = Json.obj();
        o.put("successful", r.exitCode() == 0 && !conflicting);
        o.put("mergeStatus", conflicting ? "CONFLICTING"
                : (r.exitCode() == 0 ? "MERGED" : "FAILED"));
        o.put("fetchMessages", firstLine(r.stdout()));
        ctx.ok(o);
    }

    private void push(RpcContext ctx) throws IOException {
        Sandbox sb = sandbox(ctx);
        String url = originUrl(sb);
        NativeResult r = withAuth(ctx, sb, url, List.of("push", "--porcelain", "origin"));
        if (r.exitCode() != 0) {
            if (NativeGit.isNotRepo(r)) {
                throw new NotFoundException("工作区不是 git 仓库");
            }
            throw new RuntimeException("git.push 失败: " + (r.stderr() == null ? "" : r.stderr()));
        }
        ArrayNode arr = Json.arr();
        String remote = url == null ? "origin" : url;
        for (String line : r.stdout().split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("To ") || t.equals("Done")) {
                continue;
            }
            String[] parts = t.split("\t", -1);
            if (parts.length >= 3) {
                String flag = parts[0];
                String refSpec = parts[1];
                String ref = refSpec;
                int colon = refSpec.indexOf(':');
                if (colon >= 0) {
                    ref = refSpec.substring(colon + 1);
                }
                String status = switch (flag) {
                    case "=" -> "UP_TO_DATE";
                    case "!" -> "REJECTED";
                    case "+" -> "FORCED";
                    default -> "OK";
                };
                arr.add(Json.obj().put("remote", remote).put("ref", ref).put("status", status));
            }
        }
        ctx.ok(Json.obj().set("updates", arr));
    }

    /**
     * 放弃指定路径的更改:已跟踪的变更文件恢复为 HEAD 内容(equivalent to
     * {@code git restore --source=HEAD --staged --worktree}),未跟踪(untracked)与
     * 已暂存新增(added)跳过——对齐 VS Code:未跟踪文件没有「放弃更改」,只有删除。
     */
    private void discard(RpcContext ctx) throws IOException {
        Sandbox sb = sandbox(ctx);
        JsonNode pathsNode = ctx.params().path("paths");
        List<String> paths = new ArrayList<>();
        if (pathsNode.isArray()) {
            pathsNode.forEach(n -> paths.add(n.asString()));
        }
        if (paths.isEmpty()) {
            throw new BadParamsException("放弃更改需要指定文件路径");
        }
        java.util.Set<String> trackedChanged = statusData(sb).trackedChanged();
        ArrayNode discarded = Json.arr();
        ArrayNode skipped = Json.arr();
        for (String p : paths) {
            sb.resolveLoose(p); // 已删除/丢失文件需能被 restore 恢复,仅校验沙箱不越界
            if (!trackedChanged.contains(normalizeRel(p))) {
                skipped.add(p);
                continue;
            }
            NativeResult r = git.runWrite(sb.root(), List.of(
                    "restore", "--source=HEAD", "--staged", "--worktree", "--", p), CredentialSpec.none());
            if (!r.ok()) {
                throw new RuntimeException("git.discard 失败: " + (r.stderr() == null ? "" : r.stderr()));
            }
            discarded.add(p);
        }
        ctx.ok(Json.obj().set("discarded", discarded).set("skipped", skipped));
    }

    /** 在工作区根初始化本地仓库(可指定初始分支名;git init + symbolic-ref 全版本兼容)。 */
    private void init(RpcContext ctx) throws IOException {
        Sandbox sb = sandbox(ctx);
        String initialBranch = ctx.optStrParam("initialBranch", null);
        String branch = initialBranch == null || initialBranch.isEmpty() ? "main" : initialBranch;
        Path root = sb.root();
        if (Files.exists(root.resolve(".git"))) {
            throw new BadParamsException("工作区已是 git 仓库");
        }
        NativeResult r = git.runWrite(sb.root(), List.of("init"), CredentialSpec.none());
        if (!r.ok()) {
            throw new RuntimeException("git init 失败: " + (r.stderr() == null ? "" : r.stderr()));
        }
        NativeResult sr = git.runWrite(sb.root(), List.of("symbolic-ref", "HEAD", "refs/heads/" + branch),
                CredentialSpec.none());
        if (!sr.ok()) {
            throw new RuntimeException("初始化初始分支失败: " + (sr.stderr() == null ? "" : sr.stderr()));
        }
        ctx.ok(Json.obj().put("initialized", true).put("branch", branch));
    }

    /**
     * 克隆远程仓库到工作区内指定目录(默认工作区根)。dir 必须是工作区内的空目录,
     * 否则拒绝(对齐 VS Code:克隆到非空目录不允许)。凭证走完整解析链。
     */
    private void clone(RpcContext ctx) throws IOException {
        Sandbox sb = sandbox(ctx);
        String url = ctx.strParam("url");
        if (url == null || url.trim().isEmpty()) {
            throw new BadParamsException("克隆需要远端 URL");
        }
        String dirParam = ctx.optStrParam("dir", "");
        Path target = dirParam == null || dirParam.isEmpty()
                ? sb.root()
                : sb.resolveTarget(dirParam);
        if (!Files.exists(target)) {
            // 允许克隆到不存在的子目录(git 会创建),但必须是沙箱内。
            sb.requireNotRoot(target);
        } else {
            if (!Files.isDirectory(target)) {
                throw new BadParamsException("目标不是目录");
            }
            try (var stream = Files.newDirectoryStream(target)) {
                if (stream.iterator().hasNext()) {
                    throw new BadParamsException("目标目录非空,无法克隆(请用空目录)");
                }
            }
        }
        NativeResult r = withAuth(ctx, sb, url, List.of("clone", url.trim(), target.toString()));
        if (!r.ok()) {
            throw new RuntimeException("git.clone 失败: " + (r.stderr() == null ? "" : r.stderr()));
        }
        ctx.ok(Json.obj().put("cloned", true).put("dir", sb.display(target)));
    }

    /** 关联远程仓库(推送前若无远程,前端引导填入)。 */
    private void remoteAdd(RpcContext ctx) throws IOException {
        Sandbox sb = sandbox(ctx);
        String name = ctx.optStrParam("name", "origin");
        String url = ctx.strParam("url");
        if (url == null || url.trim().isEmpty()) {
            throw new BadParamsException("关联远程需要 URL");
        }
        NativeResult r = git.runWrite(sb.root(), List.of("remote", "add", name, url.trim()),
                CredentialSpec.none());
        if (!r.ok()) {
            throw new RuntimeException("git.remote.add 失败: " + (r.stderr() == null ? "" : r.stderr()));
        }
        ctx.ok(Json.obj().put("added", true).put("name", name).put("url", url.trim()));
    }

    /** 列出已关联远程(供前端判断是否需要引导关联)。 */
    private void remoteList(RpcContext ctx) throws IOException {
        Sandbox sb = sandbox(ctx);
        NativeResult r = git.runRead(sb.root(), List.of("remote", "-v"), CredentialSpec.none());
        if (r.exitCode() != 0) {
            if (NativeGit.isNotRepo(r)) {
                throw new NotFoundException("工作区不是 git 仓库");
            }
            throw new RuntimeException("git.remote.list 失败: " + (r.stderr() == null ? "" : r.stderr()));
        }
        ArrayNode remotes = Json.arr();
        java.util.LinkedHashMap<String, String> seen = new java.util.LinkedHashMap<>();
        for (String line : r.stdout().split("\n")) {
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            String name = line.substring(0, tab);
            String rest = line.substring(tab + 1);
            int sp = rest.lastIndexOf(' ');
            String url = sp > 0 ? rest.substring(0, sp) : rest;
            seen.putIfAbsent(name, url.trim());
        }
        seen.forEach((n, u) -> remotes.add(Json.obj().put("name", n).put("url", u)));
        ctx.ok(Json.obj().set("remotes", remotes));
    }

    /**
     * 保存 git 远端凭证(加密落盘工作区 .everyagent/.git-credentials.enc,仅写不读回;见
     * docs/ARCHITECTURE.md §7.12)。前端在 AUTH_REQUIRED 弹窗里勾选保存后调用,
     * 后续 clone/pull/push 由凭证解析链自动复用(经 askpass env 注入,不经协议)。
     */
    private void credentialSave(RpcContext ctx) throws IOException {
        Sandbox sb = sandbox(ctx);
        String username = ctx.strParam("username");
        String password = ctx.strParam("password");
        String host = ctx.optStrParam("host", null);
        if (host == null || host.isEmpty()) {
            host = hostOf(ctx.strParam("url"));
        }
        if (host == null || host.isEmpty()) {
            throw new BadParamsException("无法确定凭证 host(请传 host 或 url)");
        }
        try {
            credentials.save(sb.root(), host, username, password);
            ctx.ok(Json.obj().put("saved", true).put("host", host));
        } catch (Exception e) {
            throw new RuntimeException("git.credential.save 失败: " + e.getMessage(), e);
        }
    }

    // ---- 凭证解析链(架构 §7.12) ----

    /**
     * 带认证兜底的远端操作(clone/pull/push 公共路径):
     * ① RPC 临时凭证参数(username/password,前端重试未勾选保存时带)直接注入 askpass;
     * ② 否则本机默认档(不注入,git 自行走 credential.helper / credential manager /
     *    ssh-agent / ~/.ssh);
     * ③ 认证失败 → 读工作区加密凭证(.git-credentials.enc)对应 host 条目重试一次;
     * ④ 仍失败/无凭证 → 抛 {@link AuthRequiredException} → rpc.err code=AUTH_REQUIRED
     *    (消息带 host),前端据此弹窗收集账号密码。
     */
    private NativeResult withAuth(RpcContext ctx, Sandbox sb, String url, List<String> args)
            throws IOException {
        String username = ctx.optStrParam("username", null);
        String password = ctx.optStrParam("password", null);
        String host = url == null ? null : hostOf(url);
        // ① RPC 临时凭证
        if (username != null && password != null) {
            NativeResult r = git.runWrite(sb.root(), args, CredentialSpec.of(username, password));
            if (!NativeGit.isAuthFailure(r)) {
                return r;
            }
        } else {
            // ② 本机默认(不注入凭证)
            NativeResult r = git.runWrite(sb.root(), args, CredentialSpec.none());
            if (!NativeGit.isAuthFailure(r)) {
                return r;
            }
        }
        // ③ 工作区加密凭证兜底
        if (host != null && !host.isEmpty()) {
            var stored = credentials.load(sb.root(), host);
            if (stored.isPresent()) {
                NativeResult r = git.runWrite(sb.root(), args,
                        CredentialSpec.of(stored.get().username(), stored.get().password()));
                if (!NativeGit.isAuthFailure(r)) {
                    return r;
                }
            }
        }
        // ④ 抛 AUTH_REQUIRED(透传:由 RpcDispatcher 转 rpc.err code=AUTH_REQUIRED 前端弹窗)
        throw new AuthRequiredException(host == null ? url : host);
    }

    /** 自动同步静默档凭证:本机默认(credential.helper/ssh-agent)优先,工作区加密凭证兜底。不弹窗。 */
    private CredentialSpec silentCredential(Sandbox sb, String url) {
        if (url != null) {
            String host = hostOf(url);
            if (host != null) {
                var stored = credentials.load(sb.root(), host);
                if (stored.isPresent()) {
                    return CredentialSpec.of(stored.get().username(), stored.get().password());
                }
            }
        }
        return CredentialSpec.none();
    }

    /** 从远程 URL 提取 host(http(s) 与 scp 语法 user@host:path 统一;失败返回 null)。 */
    private static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        String s = url.trim();
        String rest;
        if (s.startsWith("http://") || s.startsWith("https://")) {
            rest = s.substring(s.indexOf("://") + 3);
        } else if (s.startsWith("ssh://")) {
            rest = s.substring(6);
        } else {
            // scp 语法 [user@]host:path
            int at = s.indexOf('@');
            if (at >= 0) {
                s = s.substring(at + 1);
            }
            int colon = s.indexOf(':');
            return colon > 0 ? s.substring(0, colon) : null;
        }
        int slash = rest.indexOf('/');
        String hostPort = slash >= 0 ? rest.substring(0, slash) : rest;
        int at = hostPort.lastIndexOf('@');
        if (at >= 0) {
            hostPort = hostPort.substring(at + 1);
        }
        int colon = hostPort.lastIndexOf(':');
        String host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
        return host.isEmpty() ? null : host;
    }

    /** 当前仓库 origin 远程 URL(未关联远程返回 null)。 */
    private String originUrl(Sandbox sb) {
        try {
            NativeResult r = git.runRead(sb.root(), List.of("remote", "get-url", "origin"),
                    CredentialSpec.none());
            return r.ok() ? r.stdout().trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ---- 内部 ----

    /** 按调用的 workspace 参数(必填)绑定沙箱。 */
    private Sandbox sandbox(RpcContext ctx) throws IOException {
        return new Sandbox(workspaces.resolve(ctx.strParam("workspace")));
    }

    /** 当前分支名(非 git 仓库 / detached 返回空串;不因分支获取失败使 status 失败)。 */
    private String branch(Sandbox sb) {
        try {
            NativeResult r = git.runRead(sb.root(), List.of("symbolic-ref", "--short", "-q", "HEAD"),
                    CredentialSpec.none());
            return r.ok() ? r.stdout().trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** status 数据(非 git 仓库抛 NotFoundException)。 */
    private StatusData statusData(Sandbox sb) throws IOException {
        NativeResult r = git.runRead(sb.root(), List.of(
                "status", "--porcelain=v1", "-z", "--untracked-files=all"), CredentialSpec.none());
        if (r.exitCode() != 0) {
            if (NativeGit.isNotRepo(r)) {
                throw new NotFoundException("工作区不是 git 仓库");
            }
            throw new RuntimeException("git.status 失败: " + (r.stderr() == null ? "" : r.stderr()));
        }
        return NativeGit.parseStatus(r.stdout());
    }

    /** 是否有未合并冲突(porcelain 存在 unmerged 状态码)。 */
    private boolean hasConflicts(Sandbox sb) throws IOException {
        NativeResult r = git.runRead(sb.root(), List.of("status", "--porcelain=v1", "-z"),
                CredentialSpec.none());
        return !NativeGit.parseStatus(r.stdout()).conflicting().isEmpty();
    }

    private static String normalizeRel(String p) {
        return p == null ? null : p.replace('\\', '/');
    }

    private static String firstLine(String s) {
        if (s == null) {
            return null;
        }
        int nl = s.indexOf('\n');
        return nl >= 0 ? s.substring(0, nl) : s;
    }

    private static ArrayNode arr(java.util.Collection<String> items) {
        ArrayNode a = Json.arr();
        items.forEach(a::add);
        return a;
    }

    // ---- 自动同步(对齐 old /自动同步 的 syncGitRemote)----

    /** 自动同步结果状态(语义对齐 old GitSyncResult.status)。 */
    public enum GitSyncStatus {
        /** 工作区不是 git 仓库,跳过。 */
        NOT_INITIALIZED,
        /** 未配置 origin 远端,仅本地提交即止。 */
        NO_REMOTE,
        /** 已提交(并推送)成功。 */
        SUCCESS,
        /** 无变更 / 仅本地提交无其他动作,正常跳过。 */
        NOOP,
        /** 拉取产生冲突,已中止合并、未推送。 */
        CONFLICT,
        /** 拉取 / 推送 / 其他异常。 */
        ERROR
    }

    /** 自动同步结果(id + 人类可读消息)。 */
    public record SyncResult(GitSyncStatus status, String message) {
    }

    /**
     * 任务完成后自动同步(对齐 old {@code syncGitRemote} 的静默分支,最佳努力、绝不抛出):
     * <ol>
     *   <li>非 git 仓库 → {@link GitSyncStatus#NOT_INITIALIZED};</li>
     *   <li>有本地变更 → add -A 全部(含删除) + commit(自动消息);</li>
     *   <li>未配置 origin → 仅本地提交返回({@code NO_REMOTE} / {@code SUCCESS});</li>
     *   <li>有远端 → pull(--no-rebase;冲突则 {@code reset --hard} 中止合并、保留本地提交、
     *       跳过推送)→ push。</li>
     * </ol>
     * 凭证走静默档(本机默认 + 工作区加密凭证),无 UI 弹窗;任一异常兜为
     * {@link GitSyncStatus#ERROR},不影响调用方(任务终态)。
     *
     * @param workspaceRoot 工作区根(worker 机器绝对路径,与 fs/git RPC 的 workspace 同义)。
     * @param commitMessage 本次自动提交消息。
     */
    public SyncResult syncRemote(String workspaceRoot, String commitMessage) {
        try {
            Sandbox sb = new Sandbox(workspaces.resolve(workspaceRoot));
            NativeResult gitDir = git.runRead(sb.root(), List.of("rev-parse", "--git-dir"),
                    CredentialSpec.none());
            if (gitDir.exitCode() != 0) {
                return new SyncResult(GitSyncStatus.NOT_INITIALIZED, "工作区不是 git 仓库");
            }
            String origin = originUrl(sb);
            boolean hasRemote = origin != null;
            StatusData st = statusData(sb);
            boolean dirty = !st.clean();
            if (dirty) {
                NativeResult add = git.runWrite(sb.root(), List.of("add", "-A", "--", "."),
                        CredentialSpec.none());
                if (!add.ok()) {
                    return new SyncResult(GitSyncStatus.ERROR, "自动同步 add 失败: " + add.stderr());
                }
                NativeResult commit = git.runWrite(sb.root(), List.of("commit", "-m", commitMessage),
                        CredentialSpec.none());
                if (commit.exitCode() != 0) {
                    return new SyncResult(GitSyncStatus.ERROR, "自动提交失败: " + commit.stderr());
                }
            }
            if (!hasRemote) {
                return dirty
                        ? new SyncResult(GitSyncStatus.SUCCESS, "本地提交完成；当前仓库未配置远端")
                        : new SyncResult(GitSyncStatus.NOOP, "无本地未提交更改且未配置远端");
            }
            CredentialSpec silent = silentCredential(sb, origin);
            NativeResult pull = git.runWrite(sb.root(), List.of("pull", "--no-rebase"), silent);
            if (pull.exitCode() != 0) {
                boolean conflicted = hasConflicts(sb);
                if (conflicted) {
                    git.runWrite(sb.root(), List.of("reset", "--hard"), CredentialSpec.none());
                    return new SyncResult(GitSyncStatus.CONFLICT,
                            "拉取产生冲突,已中止合并并保留本地提交,未推送");
                }
                return new SyncResult(GitSyncStatus.ERROR, "拉取失败: " + pull.stderr());
            }
            NativeResult push = git.runWrite(sb.root(), List.of("push", "--porcelain", "origin"), silent);
            if (!push.ok()) {
                return new SyncResult(GitSyncStatus.ERROR, "推送失败: " + push.stderr());
            }
            return new SyncResult(GitSyncStatus.SUCCESS, "已提交并推送到远端");
        } catch (Exception e) {
            return new SyncResult(GitSyncStatus.ERROR, "自动同步失败: " + e.getMessage());
        }
    }
}