package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ACL 施加目标域守卫(架构 §7.10「ACL 目标域守卫」;fail-closed)——<b>盘根与系统关键
 * 目录绝不作为任何 ACL(allow/deny、write/read)的施加目标</b>。
 *
 * <p>事故背景(2026-10-23):{@code C:\} 根 DACL 被写入异常 ACE,全机 Java 进程
 * {@code toRealPath} 全灭、EveryAgent 文件/命令工具链瘫痪。危害链:工作区外部授权根
 * (含历史整盘 {@code C:\} 授权)→ {@code SandboxPathRegistry}(READ_WRITE)→ codex
 * 后端写根 → setup payload writeRoots → {@link ProvisioningAcl} 对系统目录
 * {@code SET_ACCESS} 一路无守卫直达根 DACL。此前仅 {@link DenyReadPlanner} 对
 * deny-read 拒盘根;write / deny-write / read 三类目标无任何防护。
 *
 * <p>判定规则(词法 + realpath 双形态,防 reparse point 绕过):
 * <ul>
 *   <li><b>文件系统根</b>(normalize 后等于自身 root,如 {@code C:\}、UNC 根):拒绝;</li>
 *   <li><b>全覆盖定位</b>——{@code %SystemRoot%}(默认 {@code C:\Windows})与
 *       {@code %ProgramFiles%} / {@code %ProgramFiles(x86)%} / {@code %ProgramW6432%}:
 *       <b>自身、其下、其祖先</b>一律拒绝(祖先放行等于整体覆盖系统树;WindowsApps
 *       已被 Program Files 覆盖,System32\config 已被 SystemRoot 覆盖);</li>
 *   <li><b>仅自身定位</b>——{@code %ProgramData%} 与 {@code <SystemDrive>\Users}:
 *       自身与其祖先拒绝,<b>其下放行</b>({@code ProgramData\...\WER} 报告目录与
 *       {@code C:\Users\<沙箱账户>} profile 是 codex 原版合法 ACL 目标)。</li>
 * </ul>
 *
 * <p>失败语义由调用方分级(§7.10):写根/deny-write/deny-read 命中即抛(setup
 * fail-closed);读根仅在即将真正写 ACE 时命中(内建主体已持 RX 的系统目录如
 * {@code C:\Windows} 照常跳过,行为不变),由 {@link ProvisioningAcl} 转 best-effort
 * 错误条目跳过该根。
 *
 * <p>系统定位经环境变量解析、不硬编码盘符(非 Windows 单测环境取缺省值
 * {@code C:\...},与 Linux 临时路径天然不冲突)。跨平台纯逻辑;{@link #override}
 * 供测试注入定位。
 */
public final class AclTargetGuard {

    private AclTargetGuard() {
    }

    /** 测试注入的系统定位(包私有;生产恒 null 走 {@link SystemLocations#fromEnv})。 */
    static volatile SystemLocations override;

    /**
     * 系统关键定位快照(生产 {@link #fromEnv};测试注入)。
     *
     * @param fullCover  全覆盖定位(自身+其下+其祖先均拒):SystemRoot、Program Files 系
     * @param surfaceOnly 仅自身定位(自身+其祖先拒,其下放行):ProgramData、Users 根
     */
    public record SystemLocations(List<Path> fullCover, List<Path> surfaceOnly) {

        public SystemLocations {
            fullCover = List.copyOf(fullCover);
            surfaceOnly = List.copyOf(surfaceOnly);
        }

        /** 生产构造:环境变量解析(缺省回退 {@code C:\} 系列约定位置)。 */
        public static SystemLocations fromEnv() {
            String systemDrive = envOrDefault("SystemDrive", "C:");
            List<Path> full = new ArrayList<>();
            addDistinct(full, Path.of(envOrDefault("SystemRoot", systemDrive + "\\Windows")));
            addDistinct(full,
                    Path.of(envOrDefault("ProgramFiles", systemDrive + "\\Program Files")));
            addDistinct(full, Path.of(envOrDefault("ProgramFiles(x86)",
                    systemDrive + "\\Program Files (x86)")));
            addDistinct(full,
                    Path.of(envOrDefault("ProgramW6432", systemDrive + "\\Program Files")));
            List<Path> surface = new ArrayList<>();
            addDistinct(surface,
                    Path.of(envOrDefault("ProgramData", systemDrive + "\\ProgramData")));
            addDistinct(surface, Path.of(systemDrive, "Users"));
            return new SystemLocations(full, surface);
        }

        private static void addDistinct(List<Path> list, Path path) {
            String key = key(path);
            for (Path existing : list) {
                if (key(existing).equals(key)) {
                    return;
                }
            }
            list.add(path);
        }
    }

    /** fail-closed 入口:目标不安全即抛 IOException(what 用于错误文案,如 "write root")。 */
    public static void requireSafeTarget(Path target, String what) throws IOException {
        requireSafeTarget(target, what,
                override != null ? override : SystemLocations.fromEnv());
    }

    /** 可注入定位的判定形态(单测直测)。 */
    static void requireSafeTarget(Path target, String what, SystemLocations locations)
            throws IOException {
        String lexical = denyReason(target, locations);
        if (lexical != null) {
            throw new IOException(denialMessage(what, target, lexical));
        }
        Path norm = target.toAbsolutePath().normalize();
        Path real;
        try {
            real = target.toRealPath();
        } catch (IOException | RuntimeException e) {
            return; // 缺失/被拒:词法形态已判定;realpath 不可得时交由后续流程自然报错
        }
        if (!real.equals(norm)) { // reparse/symlink 变体:canonical 形态再判一次
            String canonical = denyReason(real, locations);
            if (canonical != null) {
                throw new IOException(denialMessage(what, target, canonical + " (realpath)"));
            }
        }
    }

    /** 不安全原因;安全返回 null。包私有供测试直测。 */
    static String denyReason(Path target, SystemLocations locations) {
        Path norm = target.toAbsolutePath().normalize();
        if (norm.getRoot() != null && norm.getRoot().equals(norm)) {
            return "filesystem root";
        }
        String k = key(norm);
        for (Path dir : locations.fullCover()) {
            String d = key(dir);
            if (k.equals(d) || k.startsWith(d + "/") || d.startsWith(k + "/")) {
                return "system location " + dir;
            }
        }
        for (Path dir : locations.surfaceOnly()) {
            String d = key(dir);
            if (k.equals(d) || d.startsWith(k + "/")) {
                return "system location " + dir;
            }
        }
        return null;
    }

    /** 统一错误文案(含修复指引,直达 setup_error.json / worker 日志)。 */
    private static String denialMessage(String what, Path target, String reason) {
        return "refusing to apply ACL to " + what + " " + target + ": " + reason
                + " is not a valid ACL target (盘根与系统关键目录禁止改写 DACL;"
                + "请检查工作区外部授权根/沙箱根配置,移除系统目录后重试)";
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 比较键:绝对化 + normalize + '\'→'/' + 去尾 '/' + 小写(对齐 DenyReadPlanner.lexicalPathKey 语义)。 */
    static String key(Path path) {
        String k = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        while (k.endsWith("/") && k.length() > 3) { // 保留盘根形态 "c:/"
            k = k.substring(0, k.length() - 1);
        }
        return k.toLowerCase(Locale.ROOT);
    }
}
