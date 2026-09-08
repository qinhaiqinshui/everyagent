package dev.everyagent.worker.tools.permission;

import dev.everyagent.worker.task.TaskEntry;
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
 * realPath 为「授权根粒度」的 realpath(目标提升到父目录后),WorkspaceAllowCheck /
 * OverBroadRootCheck 以此判定;wsLex/wsReal 为工作区词法根/realpath。
 */
public final class PermissionContext {

    /** 请求形态。 */
    public enum Kind { PATH, COMMAND, PRIVILEGE, PRIVILEGE_EXEC }

    private final Kind kind;
    private final TaskEntry task;
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
    private final String command;
    private final String execPath;

    private PermissionContext(Builder b) {
        this.kind = Objects.requireNonNull(b.kind, "kind");
        this.task = b.task;
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
        this.command = b.command;
        this.execPath = b.execPath;
    }

    public Kind kind() { return kind; }
    public TaskEntry task() { return task; }
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
    public String command() { return command; }
    public String execPath() { return execPath; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Kind kind;
        private TaskEntry task;
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
        private String command;
        private String execPath;

        public Builder kind(Kind kind) { this.kind = kind; return this; }
        public Builder task(TaskEntry task) { this.task = task; return this; }
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
        public Builder command(String command) { this.command = command; return this; }
        public Builder execPath(String execPath) { this.execPath = execPath; return this; }

        public PermissionContext build() {
            return new PermissionContext(this);
        }
    }
}