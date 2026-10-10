package dev.everyagent.plugin.sandbox.wslubuntu;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows 路径 ↔ WSL 发行版路径 的静态映射（wsl-ubuntu 沙箱后端专用）。
 *
 * <p>沙箱内的工作区挂载点约定：原路径形态 {@code /mnt/<盘>/...}（模型直接写绝对路径时同样可达）
 * 以及 wsl-direct 原路径挂载点 {@code /c/a/foo}。
 *
 * <p>职责仅是「路径形态翻译」，不做存在性校验：
 * <ul>
 *   <li>{@link #toWsl}:C:\a\b → /mnt/c/a/b（UNC \\server\share 不映射，返回 null）；</li>
 *   <li>{@link #toWindowsToken}:POSIX token → Windows 路径字符串；</li>
 *   <li>{@link #translateCommand}:把命令文本里的路径前缀替换为 Windows 形态；</li>
 *   <li>{@link #toDirectMount}:C:\a\foo → /c/a/foo（drvfs 原路径挂载点）。</li>
 * </ul>
 */
public final class WslPathMapper {

    /** 沙箱内工作区规范挂载点。 */
    public static final String WORKSPACE_MOUNT = "/workspace";

    /** /mnt/<盘>[...](大小写盘符均可)。 */
    private static final Pattern MNT = Pattern.compile("^/mnt/([a-zA-Z])(/.*)?$");
    /** 命令文本中的 /workspace 前缀。 */
    private static final Pattern WS_MOUNT = Pattern.compile(WORKSPACE_MOUNT + "(?![\\w.\\-])");
    /** 命令文本中的 /mnt/<盘>/ 前缀。 */
    private static final Pattern MNT_SLASH = Pattern.compile("/mnt/([a-zA-Z])/");
    /** 命令文本中的裸 /mnt/<盘>。 */
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
            return null;
        }
        char drive = Character.toLowerCase(s.charAt(0));
        String rest = s.substring(2);
        while (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        return "/mnt/" + drive + rest;
    }

    /** POSIX token → Windows 路径字符串;仅识别 /workspace 与 /mnt/<盘>,其余 null。 */
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
            return Character.toUpperCase(m.group(1).charAt(0)) + ":"
                    + (rest.isEmpty() ? "/" : rest);
        }
        return null;
    }

    /** 门禁扫描用的命令翻译（仅路径前缀替换，不动 shell 语义）。 */
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
     * Windows 绝对路径 → wsl-direct 原路径挂载点形态:
     * {@code C:\a\foo → /c/a/foo}。UNC / 相对路径 / 无盘符 → null。
     */
    public static String toDirectMount(Path win) {
        if (win == null) {
            return null;
        }
        String s = win.toString().replace('\\', '/');
        if (s.length() < 2 || s.charAt(1) != ':' || !isLetter(s.charAt(0))) {
            return null;
        }
        char drive = Character.toLowerCase(s.charAt(0));
        String rest = s.substring(2);
        while (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        return "/" + drive + rest;
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
