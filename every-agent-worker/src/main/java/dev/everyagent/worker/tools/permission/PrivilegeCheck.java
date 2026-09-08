package dev.everyagent.worker.tools.permission;

import dev.everyagent.worker.tools.PermissionDeniedException;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 责任链节点(提权):命令含提权动词(sudo/su/doas/runas 等,见 {@link #usesPrivilege})
 * 或 seccomp 上报 setuid exec 时,须经 {@link GrantRegistry} 授权决议链(弹窗/AI 审议)
 * 后才允许以提权方式执行;拒绝/超时抛 {@link PermissionDeniedException} 回灌模型。
 * <p>grant key 统一为 {@code priv::<动词|程序 basename>}(如 priv::sudo),与文本扫描 /
 * seccomp 桥接两条途径同 key,任一途径授权后另一途径不再弹窗。
 */
@Component
public class PrivilegeCheck implements PermissionCheck {

    /** 提权动词(bash/POSIX + Windows/PowerShell;词边界防误伤 lsusb/result 等)。 */
    private static final Pattern PRIVILEGE_VERB =
            Pattern.compile("(?i)\\b(sudo|su|doas|pkexec|gksudo|gksu|runas|gsudo)\\b");

    private final GrantRegistry grants;

    public PrivilegeCheck(GrantRegistry grants) {
        this.grants = grants;
    }

    @Override
    public PermissionDecision check(PermissionContext ctx) {
        try {
            if (ctx.kind() == PermissionContext.Kind.PRIVILEGE_EXEC) {
                return checkExec(ctx);
            }
            return checkCommand(ctx);
        } catch (PermissionDeniedException e) {
            return PermissionDecision.deny(e);
        }
    }

    /** 命令文本提权(requirePrivilege 场景)。 */
    private PermissionDecision checkCommand(PermissionContext ctx) {
        String command = ctx.command();
        if (command == null || command.isBlank()) {
            return PermissionDecision.allow("空命令无需授权");
        }
        String verb = privilegeVerb(command);
        if (verb == null) {
            return PermissionDecision.allow("无提权动词");
        }
        String prompt = "AI 请求以管理员/root 权限执行命令: " + PathSupport.abbreviate(command) + "\n"
                + "提权类别: " + verb + "(sudo/su 等提权动词)。"
                + "授权后同类提权命令(" + verb + ")在所选范围内不再询问。";
        grants.authorize(ctx.task(), ctx.agentId(), PathSupport.privKey(verb),
                prompt, List.of(), List.of());
        return PermissionDecision.allow("提权授权通过");
    }

    /** seccomp 内核级提权(requirePrivilegeExec 场景):由 exec 路径提取 basename 作为 grant key。 */
    private PermissionDecision checkExec(PermissionContext ctx) {
        String execPath = ctx.execPath();
        if (execPath == null || execPath.isBlank()) {
            return PermissionDecision.skip();
        }
        String name = baseName(execPath);
        if (name.isEmpty()) {
            return PermissionDecision.skip();
        }
        String prompt = "AI 请求以管理员/root 权限执行 setuid 程序: " + execPath + "\n"
                + "提权类别: " + name + "(setuid 提权)。"
                + "授权后将以 WSL root 在发行版内执行该命令(沙箱内提权不可行);"
                + "同类提权程序(" + name + ")在所选范围内不再询问。";
        grants.authorize(ctx.task(), ctx.agentId(), PathSupport.privKey(name),
                prompt, List.of(), List.of());
        return PermissionDecision.allow("提权授权通过");
    }

    /** 命令是否含提权动词(词边界,大小写不敏感;bash/POSIX 与 Windows/PowerShell 通用)。 */
    public static boolean usesPrivilege(String command) {
        return privilegeVerb(command) != null;
    }

    /** 命令中首个命中的提权动词小写;无则返回 null。 */
    private static String privilegeVerb(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        Matcher m = PRIVILEGE_VERB.matcher(command);
        return m.find() ? m.group().trim().toLowerCase() : null;
    }

    /** 从 exec 路径提取 basename(去掉目录与 Windows 盘符);无合法名返回空串。 */
    public static String baseName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        String base = slash >= 0 ? p.substring(slash + 1) : p;
        return base.trim();
    }
}