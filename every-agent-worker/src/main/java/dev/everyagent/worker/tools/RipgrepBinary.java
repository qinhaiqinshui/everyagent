package dev.everyagent.worker.tools;

import dev.everyagent.worker.config.WorkerProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ripgrep 二进制定位器(程序附属文件,架构 §5.10)。
 *
 * <p>定位顺序:
 * <ol>
 *   <li>配置覆盖 {@code worker.tools.rg-path}(开发迭代换版本免重新打包);配置非法/不存在 → 视为缺失;</li>
 *   <li>程序根 {@code <程序根>/runtime/bin/}(程序根 = JVM 工作目录 user.dir):Windows 取
 *       {@code rg.exe},Linux 取 {@code rg}(随安装/解压分发、运行时只读引用,不再从
 *       classpath 提取)。</li>
 * </ol>
 *
 * <p>缺失返回 null,上层(主/子 agent 装配处)据此不把 rg 注入 bash/powershell 子进程命令 PATH,
 * 启动日志告警一次。程序根 runtime/ 已被 {@link PermissionGate} 忽略前缀覆盖(§5.5)→ rg 经 PATH 注入
 * 供 bash/powershell 直接调用,引用 rg 全路径不会触发授权弹窗。
 */
@Component
public class RipgrepBinary {

    private static final Logger log = LoggerFactory.getLogger(RipgrepBinary.class);

    private final Path resolved;

    public RipgrepBinary(WorkerProperties props) {
        this.resolved = resolve(props);
    }

    /** 可执行文件路径;缺失返回 null(不注入 PATH)。 */
    public Path path() {
        return resolved;
    }

    /** 是否可用。 */
    public boolean available() {
        return resolved != null;
    }

    private static Path resolve(WorkerProperties props) {
        // ① 配置覆盖
        String cfg = props.getTools().getRgPath();
        if (cfg != null && !cfg.isBlank()) {
            Path p = Path.of(cfg.trim()).toAbsolutePath().normalize();
            if (Files.isRegularFile(p) && isExecutable(p)) {
                log.info("[rg] 使用配置路径 {}", p);
                return p;
            }
            log.warn("[rg] 配置 worker.tools.rg-path 不存在或不可执行: {} → 不注入 rg 到命令 PATH", cfg);
            return null;
        }
        // ② 程序根 runtime/bin/(随安装/解压分发,只读引用)
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        String name = win ? "rg.exe" : "rg";
        Path p = props.resolveRuntimeDir().resolve("bin").resolve(name);
        if (Files.isRegularFile(p) && isExecutable(p)) {
            return p;
        }
        log.warn("[rg] 程序根缺少 {} → powershell/bash 中 rg 不可用(PATH 未注入)"
                + "(放置: <程序根>/runtime/bin/{};或配置 worker.tools.rg-path;程序根 = JVM 工作目录,"
                + "IDE 运行 = 仓库根,desktop 打包 = resources 目录)", p, name);
        return null;
    }

    private static boolean isExecutable(Path p) {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return Files.isReadable(p); // Windows 无 POSIX 执行位,可读即视为可执行
        }
        return Files.isExecutable(p);
    }
}
