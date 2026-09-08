package dev.everyagent.worker.os.wsl;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows 路径 ↔ WSL 发行版路径 的静态映射(wsl-bwrap 沙箱后端专用)。
 *
 * <p>沙箱内的工作区挂载点约定:{@code /workspace}(规范入口,bash 内短且无空格)以及
 * 工作区在 WSL 下的原生形态 {@code /mnt/<盘>/...}(模型直接写绝对路径时同样可达)。
 * 授权根(execRoots)一律按原生 {@code /mnt/...} 形态挂载,与命令文本一致。
 *
 * <p>职责仅是「路径形态翻译」,不做存在性校验:
 * <ul>
 *   <li>{@link #toWsl}:C:\a\b → /mnt/c/a/b(UNC \\server\share 不映射,返回 null);</li>
 *   <li>{@link #toWindowsToken}:POSIX token → Windows 路径字符串(正斜杠盘符形态,
 *       供 PermissionGate 的 WIN_ABS 扫描/Path.of 解析直接使用);识别 {@code /workspace}
 *       与 {@code /mnt/<盘>};其余(/etc 等发行版内部路径)返回 null——这类路径落在
 *       只读发行版层,门禁按工作区相对处理,无需翻译;</li>
 *   <li>{@link #translateCommand}:把命令文本里的 {@code /workspace}、{@code /mnt/<盘>}
 *       前缀替换为 Windows 形态,产出<b>仅供 PermissionGate 扫描</b>的命令副本
 *       (真正执行的命令原文不动)。替换为正斜杠盘符形态,不引入引号/反斜杠,
 *       词法 token 完整性不受影响。</li>
 * </ul>
 */
public final class WslPathMapper {

    /** 沙箱内工作区规范挂载点(与 WslBwrapSandbox 的 --bind 目标一致)。 */
    public static final String WORKSPACE_MOUNT = "/workspace";

    /** /mnt/<盘>[...](大小写盘符均可)。 */
    private static final Pattern MNT = Pattern.compile("^/mnt/([a-zA-Z])(/.*)?$");
    /** 命令文本中的 /workspace 前缀(边界:后不接字母数字 _ . -,防 /workspace-x 误替换)。 */
    private static final Pattern WS_MOUNT = Pattern.compile(WORKSPACE_MOUNT + "(?![\\w.\\-])");
    /** 命令文本中的 /mnt/<盘>/ 前缀。 */
    private static final Pattern MNT_SLASH = Pattern.compile("/mnt/([a-zA-Z])/");
    /** 命令文本中的裸 /mnt/<盘>(盘符根,不带后续斜杠)。 */
    private static final Pattern MNT_BARE = Pattern.compile("/mnt/([a-zA-Z])(?![\\w/.\\-])");

    private WslPathMapper() {
    }

    /** Windows 绝对路径 → WSL 原生挂载形态;UNC / 相对路径 / 无盘符 → null。 */
    public static String toWsl(Path win) {
        if (win == null) {
            return null;
        }
        String s = win.toString().replace('\\', '/');
        if (s.length() < 2 || s.charAt(1) != ':' || !isLetter(s.charAt(0))) {
            return null; // UNC(//开头)或相对路径:不映射
        }
        char drive = Character.toLowerCase(s.charAt(0));
        String rest = s.substring(2);
        while (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1); // C:/ → /mnt/c;C:/a/ → /mnt/c/a(与 wslpath 惯例一致)
        }
        return "/mnt/" + drive + rest;
    }

    /** POSIX token → Windows 路径字符串(正斜杠形态);仅识别 /workspace 与 /mnt/<盘>,其余 null。 */
    public static String toWindowsToken(String token, Path workspaceRoot) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        if (workspaceRoot != null) {
            String ws = forward(workspaceRoot);
            if (token.equals(WORKSPACE_MOUNT)) {
                return ws;
            }
            if (token.startsWith(WORKSPACE_MOUNT + "/")) {
                return ws + token.substring(WORKSPACE_MOUNT.length());
            }
        }
        Matcher m = MNT.matcher(token);
        if (m.find()) {
            String rest = m.group(2) == null ? "" : m.group(2);
            // 盘根必须带斜杠:裸 "D:" 在 Windows 语义里是「D 盘当前目录」而非盘根
            return Character.toUpperCase(m.group(1).charAt(0)) + ":" + (rest.isEmpty() ? "/" : rest);
        }
        return null;
    }

    /**
     * 门禁扫描用的命令翻译:逐字符前缀替换,不动 shell 语义(仅喂给 PermissionGate,
     * 执行的命令保持原文)。引号内的 {@code "/workspace/a b.txt"} 同样被替换成完整
     * Windows 路径——引号段由 QUOTED 正则整体提取,token 完整性不受空格影响。
     * 无法翻译的部分(/etc 等)原样保留。
     */
    public static String translateCommand(String command, Path workspaceRoot) {
        if (command == null || command.isEmpty()) {
            return command;
        }
        String out = command;
        if (workspaceRoot != null) {
            String ws = Matcher.quoteReplacement(forward(workspaceRoot));
            out = WS_MOUNT.matcher(out).replaceAll(ws);
        }
        out = replaceUpper(MNT_SLASH.matcher(out));
        out = replaceUpper(MNT_BARE.matcher(out));
        return out;
    }

    /** /mnt/<盘>… → <大写盘>:/… 的 Matcher 循环(replaceAll 无法做大小写转换;两种形态都补足盘根斜杠)。 */
    private static String replaceUpper(Matcher m) {
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String repl = Character.toUpperCase(m.group(1).charAt(0)) + ":/";
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String forward(Path p) {
        return p.toString().replace('\\', '/');
    }

    /**
     * Windows 绝对路径 → wsl-direct 原路径挂载点形态(盘符作首级目录,去冒号,反斜杠转正斜杠):
     * {@code C:\a\foo → /c/a/foo}、{@code D:\b\foo → /d/b/foo}。UNC / 相对路径 / 无盘符 → null。
     */
    public static String toDirectMount(Path win) {
        if (win == null) {
            return null;
        }
        String s = win.toString().replace('\\', '/');
        if (s.length() < 2 || s.charAt(1) != ':' || !isLetter(s.charAt(0))) {
            return null; // UNC(//开头)或相对路径:不映射
        }
        char drive = Character.toLowerCase(s.charAt(0));
        String rest = s.substring(2);
        while (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1); // C:/ → /c;盘根挂载点为 /c
        }
        return "/" + drive + rest;
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
