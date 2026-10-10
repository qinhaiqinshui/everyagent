package dev.everyagent.worker.tools.permission;

import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationRequest;
import dev.everyagent.worker.tools.PermissionGate.Op;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 一次权限判定请求的完整上下文(责任链节点只读消费,各入口按需填字段):
 * <ul>
 *   <li>文件/路径授权({@link Kind#PATH}):op/norm/realPath/rel + 授权决议字段;</li>
 *   <li>命令授权({@link Kind#COMMAND}):command + 授权决议字段;</li>
 *   <li>提权授权({@link Kind#PRIVILEGE}/{@link Kind#PRIVILEGE_EXEC}):command 或 execPath;</li>
 * </ul>
 * realPath 为「最深已存在祖先目录」的 realpath(链节点 WorkspaceAllowCheck /
 * OverBroadRootCheck / SkillsReadAllowCheck / ExternalRootAllowCheck 以此做前缀判定,
 * 同时也是沙箱可达根的来源);<b>授权判定用的 key 是独立的 {@code grantKey}</b>
 * (文件工具链 = 目标路径本身的授权单元,§7.8);wsLex/wsReal 为工作区词法根/realpath。
 *
 * <p>域中性(§6.4):不携带 worker {@code TaskEntry}——路径判定用 {@code workspaceRoot}
 * 字符串,授权决议字段经 {@code authReq}(AuthorizationRequest,内含 ExecContext)
 * 由 AuthorizeCheck/CommandCheck/PrivilegeCheck 透传给 {@link GrantRegistry}。
 */
public final class PermissionContext {

    /** 请求形态。 */
    public enum Kind { PATH, COMMAND, PRIVILEGE, PRIVILEGE_EXEC }

    private final Kind kind;
    private final String workspaceRoot;
    private final AuthorizationRequest authReq;
    private final String agentId;
    private final Op op;
    private final String rel;
    private final Path norm;
    private final Path realPath;
    private final Path wsLex;
    private final Path wsReal;
    private final String grantKey;
    private final String prompt;
    private final List<Path> rootsOnGrant;
    private final List<Path> execRootsOnGrant;
    /** 授权后下发给沙箱的根(§7.8:沙箱可访问范围 = 授权范围;不放大 P5)。 */
    private final List<Path> sandboxRootsOnGrant;
    private final String command;
    private final String execPath;

    private PermissionContext(Builder b) {
        this.kind = Objects.requireNonNull(b.kind, "kind");
        this.workspaceRoot = b.workspaceRoot;
        this.authReq = b.authReq;
        this.agentId = b.agentId;
        this.op = b.op;
        this.rel = b.rel;
        this.norm = b.norm;
        this.realPath = b.realPath;
        this.wsLex = b.wsLex;
        this.wsReal = b.wsReal;
        this.grantKey = b.grantKey;
        this.prompt = b.prompt;
        this.rootsOnGrant = b.rootsOnGrant == null ? List.of() : List.copyOf(b.rootsOnGrant);
        this.execRootsOnGrant = b.execRootsOnGrant == null ? List.of() : List.copyOf(b.execRootsOnGrant);
        this.sandboxRootsOnGrant =
                b.sandboxRootsOnGrant == null ? List.of() : List.copyOf(b.sandboxRootsOnGrant);
        this.command = b.command;
        this.execPath = b.execPath;
    }

    public Kind kind() { return kind; }
    /** 工作区根路径(路径判定用;域中性字符串,非 TaskEntry)。 */
    public String workspaceRoot() { return workspaceRoot; }
    /** 授权决议请求(内含 ExecContext;责任链节点透传给 GrantRegistry 用)。 */
    public AuthorizationRequest authReq() { return authReq; }
    public String agentId() { return agentId; }
    public Op op() { return op; }
    public String rel() { return rel; }
    public Path norm() { return norm; }
    public Path realPath() { return realPath; }
    public Path wsLex() { return wsLex; }
    public Path wsReal() { return wsReal; }
    public String grantKey() { return grantKey; }
    public String prompt() { return prompt; }
    public List<Path> rootsOnGrant() { return rootsOnGrant; }
    public List<Path> execRootsOnGrant() { return execRootsOnGrant; }
    /**
     * 授权后下发给沙箱后端的根(路径级,§7.8)。与 {@link #rootsOnGrant()} 的区别:
     * 后者是「AI 可访问区域」的逻辑允许名单(目录级,供路径校验),本项是<b>真正下发给沙箱
     * 内核机制</b>的范围,按 <b>P5 不放大</b> 计算——需创建新文件时无法在请求粒度落地,
     * 故选空(不下发),绝不放大到父目录。
     */
    public List<Path> sandboxRootsOnGrant() { return sandboxRootsOnGrant; }
    public String command() { return command; }
    public String execPath() { return execPath; }

    /**
     * 以本上下文为模板组装授权请求:沿用 {@link #authReq()} 携带的 ExecContext 与
     * {@link #agentId()},替换授权专属参数(grantKey/prompt)。命令/提权链节点
     * 按候选逐个组装时使用(CommandCheck/PrivilegeCheck)。
     */
    public AuthorizationRequest authRequest(String grantKey, String prompt) {
        AuthorizationRequest base = authReq;
        return new AuthorizationRequest(base != null ? base.context() : null,
                agentId, grantKey, prompt);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Kind kind;
        private String workspaceRoot;
        private AuthorizationRequest authReq;
        private String agentId;
        private Op op;
        private String rel;
        private Path norm;
        private Path realPath;
        private Path wsLex;
        private Path wsReal;
        private String grantKey;
        private String prompt;
        private List<Path> rootsOnGrant;
        private List<Path> execRootsOnGrant;
        private List<Path> sandboxRootsOnGrant;
        private String command;
        private String execPath;

        public Builder kind(Kind kind) { this.kind = kind; return this; }
        public Builder workspaceRoot(String workspaceRoot) { this.workspaceRoot = workspaceRoot; return this; }
        public Builder authReq(AuthorizationRequest authReq) { this.authReq = authReq; return this; }
        public Builder agentId(String agentId) { this.agentId = agentId; return this; }
        public Builder op(Op op) { this.op = op; return this; }
        public Builder rel(String rel) { this.rel = rel; return this; }
        public Builder norm(Path norm) { this.norm = norm; return this; }
        public Builder realPath(Path realPath) { this.realPath = realPath; return this; }
        public Builder wsLex(Path wsLex) { this.wsLex = wsLex; return this; }
        public Builder wsReal(Path wsReal) { this.wsReal = wsReal; return this; }
        public Builder grantKey(String grantKey) { this.grantKey = grantKey; return this; }
        public Builder prompt(String prompt) { this.prompt = prompt; return this; }
        public Builder rootsOnGrant(List<Path> rootsOnGrant) { this.rootsOnGrant = rootsOnGrant; return this; }
        public Builder execRootsOnGrant(List<Path> execRootsOnGrant) { this.execRootsOnGrant = execRootsOnGrant; return this; }
        public Builder sandboxRootsOnGrant(List<Path> roots) { this.sandboxRootsOnGrant = roots; return this; }
        public Builder command(String command) { this.command = command; return this; }
        public Builder execPath(String execPath) { this.execPath = execPath; return this; }

        public PermissionContext build() {
            return new PermissionContext(this);
        }
    }
}