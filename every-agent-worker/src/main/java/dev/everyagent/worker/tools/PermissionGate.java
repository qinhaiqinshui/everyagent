package dev.everyagent.worker.tools;

import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.modules.WorkspaceManager.Root;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.tools.permission.AuthorizeCheck;
import dev.everyagent.worker.tools.permission.CommandCheck;
import dev.everyagent.worker.tools.permission.ExternalRootAllowCheck;
import dev.everyagent.worker.tools.permission.GrantRegistry;
import dev.everyagent.worker.tools.permission.MissingPathCheck;
import dev.everyagent.worker.tools.permission.OverBroadRootCheck;
import dev.everyagent.worker.tools.permission.PathSupport;
import dev.everyagent.worker.tools.permission.PermissionChain;
import dev.everyagent.worker.tools.permission.PermissionContext;
import dev.everyagent.worker.tools.permission.PermissionDecision;
import dev.everyagent.worker.tools.permission.PrivilegeCheck;
import dev.everyagent.worker.tools.permission.SkillsReadAllowCheck;
import dev.everyagent.worker.tools.permission.WorkspaceAllowCheck;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 危险操作授权门面(架构 §5.5 authorization):AI 工具的工作区外文件访问与危险命令
 * 必须经用户弹窗授权或 AI 审议;拒绝/超时抛 {@link PermissionDeniedException},以
 * 「[工具执行失败] ...」文本回灌模型(循环不中断,沿用 novel_agent-n 语义)。
 *
 * <p>本类为<b>薄门面</b>:判定逻辑全部下沉到责任链(架构 §5.5.1)。每条入口一条链,
 * 链上节点顺序执行,<b>任一节点返回 ALLOW/DENY 即短路处理完成</b>;节点返回 SKIP
 * 表示无法处理,继续交给下一个节点;全链 SKIP 时按拒绝兜底。新增授权规则 =
 * 新增一个权限节点插到链的合适位置,不改本门面主体。
 *
 * <ul>
 *   <li>文件路径链:{@link WorkspaceAllowCheck} → {@link MissingPathCheck} →
 *       {@link SkillsReadAllowCheck}(skills 目录只读放行)→
 *       {@link ExternalRootAllowCheck}(工作区外部授权根放行环,用户显式选择=已授权,
 *       §7.8/§7.17)→ {@link OverBroadRootCheck} → {@link AuthorizeCheck};</li>
 *   <li>命令链:{@link CommandCheck}(危险动词 + 越界路径逐项授权);</li>
 *   <li>提权链:{@link PrivilegeCheck}(提权动词 / seccomp setuid exec)。</li>
 * </ul>
 *
 * <p>授权决议链(弹窗 / AI 审议 / 无人值守)与 grant 状态由 {@link GrantRegistry} 承载;
 * 系统目录 / 程序目录与普通工作区外目录同权,全部走授权决议链,不再有硬拒不弹窗;
 * skills 目录只读内容经 {@link SkillsReadAllowCheck} 直接放行(其余操作仍走授权决议链);
 * 工作区外部授权根(用户经 @ 弹窗显式选择,§7.17)经 {@link ExternalRootAllowCheck}
 * 放行环直接放行(完全读写,不再弹窗)。
 * 主/子 agent 按 taskId 共享授权。
 */
@Component
public class PermissionGate {

    /** 文件操作类别(命令串中的路径引用按 EXEC 独立计,不与文件工具的读/写互认)。 */
    public enum Op {
        READ, WRITE, EXEC
    }

    private final WorkspaceManager workspaces;
    private final GrantRegistry grants;

    private final PermissionChain pathChain;
    private final PermissionChain cmdChain;
    private final PermissionChain privChain;

    public PermissionGate(WorkspaceManager workspaces, GrantRegistry grants,
            WorkspaceAllowCheck workspaceAllow, MissingPathCheck missing,
            SkillsReadAllowCheck skillsRead, ExternalRootAllowCheck externalRoots,
            OverBroadRootCheck overBroad,
            AuthorizeCheck authorize,
            CommandCheck commandCheck, PrivilegeCheck privilegeCheck) {
        this.workspaces = workspaces;
        this.grants = grants;
        this.pathChain = new PermissionChain(
                List.of(workspaceAllow, missing, skillsRead, externalRoots, overBroad, authorize));
        this.cmdChain = new PermissionChain(List.of(commandCheck));
        this.privChain = new PermissionChain(List.of(privilegeCheck));
    }

    // ---- 判定入口 ----

    /**
     * 文件工具路径授权:目标(以 realpath 判定)在工作区内 → 直接放行;工作区外
     * (含系统目录 / 程序目录 / skills,均已放开)→ 走授权决议链(弹窗或 AI 审议),
     * 拒绝/超时抛 {@link PermissionDeniedException}。过度宽泛授权根(盘根/工作区祖先)
     * 静默拒收交沙箱兜底。
     */
    public void requirePath(TaskEntry t, String agentId, String rel, Op op) throws IOException {
        Root ws = workspaces.resolve(t.workspaceRoot);
        Path norm = ws.path().resolve(rel).normalize();
        Path anchor = PathSupport.deepestExisting(norm);
        if (anchor == null) {
            return; // 连盘符根都不存在,交给 Sandbox/IO 层报错
        }
        anchor = PathSupport.grantRootOf(anchor);
        Path real = anchor.toRealPath();
        Path lexical = anchor.normalize();
        List<Path> roots = lexical.equals(real) ? List.of(real) : List.of(real, lexical);
        String prompt = "AI 请求" + PathSupport.opDesc(op) + "工作区外路径: " + norm + "\n"
                + "授权范围: " + real + " 及其子目录内的" + PathSupport.opDesc(op) + "操作。";
        PermissionContext ctx = PermissionContext.builder()
                .kind(PermissionContext.Kind.PATH)
                .task(t)
                .agentId(agentId)
                .op(op)
                .rel(rel)
                .norm(norm)
                .realPath(real)
                .wsLex(ws.path())
                .wsReal(ws.realPath())
                .grantKey(PathSupport.pathKey(real, op))
                .prompt(prompt)
                .rootsOnGrant(roots)
                .execRootsOnGrant(List.of())
                .build();
        handle(pathChain.evaluate(ctx));
    }

    /**
     * 命令执行门禁(bash / powershell 在 spawn 之前):危险动词仅在工作区外引用时才需授权;
     * 越界已存在路径(含系统目录)逐个授权;拒绝/超时抛 {@link PermissionDeniedException}。
     */
    public void requireCommand(TaskEntry t, String agentId, String command) throws IOException {
        PermissionContext ctx = PermissionContext.builder()
                .kind(PermissionContext.Kind.COMMAND)
                .task(t)
                .agentId(agentId)
                .command(command)
                .build();
        handle(cmdChain.evaluate(ctx));
    }

    /** 命令含提权动词时授权(默认拒、按命令弹窗;worker 全局放行时不走到这里)。 */
    public void requirePrivilege(TaskEntry t, String agentId, String command) {
        PermissionContext ctx = PermissionContext.builder()
                .kind(PermissionContext.Kind.PRIVILEGE)
                .task(t)
                .agentId(agentId)
                .command(command)
                .build();
        handle(privChain.evaluate(ctx));
    }

    /** seccomp 内核级提权(setuid exec):与 {@link #requirePrivilege} 同一授权链。 */
    public void requirePrivilegeExec(TaskEntry t, String agentId, String execPath) {
        PermissionContext ctx = PermissionContext.builder()
                .kind(PermissionContext.Kind.PRIVILEGE_EXEC)
                .task(t)
                .agentId(agentId)
                .execPath(execPath)
                .build();
        handle(privChain.evaluate(ctx));
    }

    /** 命令是否含提权动词(词边界;g 供给 CommandExecutor 决定是否走 requirePrivilege)。 */
    public static boolean usesPrivilege(String command) {
        return PrivilegeCheck.usesPrivilege(command);
    }

    /** 从 exec 路径提取 basename(seccomp 提权授权用)。 */
    public static String baseName(String path) {
        return PrivilegeCheck.baseName(path);
    }

    // ---- 授权状态委托(GrantRegistry) ----

    /** 新一条用户输入到达:本轮(run)授权即失效(任务级不受影响)。 */
    public void beginRun(String taskId) {
        grants.beginRun(taskId);
    }

    /** 任务终态:内存驱逐(任务级授权已在磁盘,再运行时 lazy 重载)。 */
    public void untrack(String taskId) {
        grants.untrack(taskId);
    }

    /** 已授权外部根(realpath + 词法形态),供 Sandbox 附加放行。 */
    public List<Path> extraRoots(String taskId) {
        return grants.extraRoots(taskId);
    }

    /** 已授权的命令 EXEC 根(realpath),供命令执行器做 Windows Low 完整性标注(§13.6)。 */
    public List<Path> execRoots(String taskId) {
        return grants.execRoots(taskId);
    }

    /** EXEC 根安全过滤视图(单点谓词见 {@link OverBroadRootCheck#isOverBroadRoot})。 */
    public List<Path> execRootsSandboxed(TaskEntry t) {
        return grants.execRootsSandboxed(t);
    }

    // ---- 决策收口 ----

    /**
     * 链结论收口:ALLOW → 放行;DENY 携带异常 → 抛出回灌模型;DENY 无异常(静默拒收)
     * → 返回交沙箱兜底;全链 SKIP → 按拒绝兜底(链尾节点兜底,理论上不达)。
     */
    private static void handle(PermissionDecision d) {
        if (d.isAllow()) {
            return;
        }
        if (d.isDeny()) {
            RuntimeException denial = d.denial();
            if (denial != null) {
                throw denial;
            }
            return; // 静默拒收(如过度宽泛授权根):交沙箱越界拦截兜底
        }
        // 全链 SKIP:链尾节点兜底,理论上不达
        throw new PermissionDeniedException("拒绝");
    }
}