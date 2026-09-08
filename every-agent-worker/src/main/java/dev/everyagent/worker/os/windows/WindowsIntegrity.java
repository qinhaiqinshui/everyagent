package dev.everyagent.worker.os.windows;

import com.sun.jna.platform.win32.AccCtrl;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 工作区/授权根的 Low 完整性标注(架构 §13.6「Low IL 可写性契约」)。
 *
 * <p>背景:命令沙箱进程运行在 Low 完整性级别(WindowsSandbox),而用户文件默认
 * Medium 完整性,Windows MIC 默认策略 NO_WRITE_UP ⇒ <b>不标注则沙箱内连工作区文件
 * 都只能读不能写</b>。本类把目标树标注为 Low 完整性(SDDL {@code S:(ML;OICI;NW;;;LW)},
 * 等价 {@code icacls <dir> /setintegritylevel (OI)(CI)L}):目录带继承标志,
 * 之后新建的文件/子目录自动获得 Low 标签;既有文件逐个补标(有界遍历)。
 *
 * <ul>
 *   <li>沙箱(Low)进程:被标注树内随意读写;树外(Medium)写被 OS 拒 —— 弹窗授权
 *       之外的 OS 级兜底,授权目录经 {@code ensureWritable} 补标后命令写才真正可行;</li>
 *   <li>worker JVM(Medium)自身:写 Low 树不受影响(MIC 只拦 write-up,不拦 write-down),
 *       文件工具/in-JVM 读写零影响;</li>
 *   <li>读操作不受完整性标注影响(Low 读 Medium 默认允许)。</li>
 * </ul>
 *
 * <p>幂等:每 worker 进程每根只标注一次(结果缓存,失败也缓存,免得每条命令重试刷日志);
 * 标注在命令执行前同步完成(首条命令承担一次遍历成本,典型工作区毫秒级)。
 * FAT/exFAT 卷无 ACL/MIC:标注失败仅告警并按需提示(该场景本就无完整性拦截,写天然可用)。
 * 符号链接一律跳过不标(标注会跟随到链接目标,可能把工作区外的真身降标)。
 * 仅 Windows 运行时被调用;本类在所有平台均可编译。
 */
public final class WindowsIntegrity {

    private static final Logger log = LoggerFactory.getLogger(WindowsIntegrity.class);

    /** 目录标注 SDDL:Low 完整性 + (OI)(CI) 继承(NW = NO_WRITE_UP,LW = S-1-16-4096)。 */
    private static final String SDDL_DIR = "S:(ML;OICI;NW;;;LW)";
    /** 文件标注 SDDL:Low 完整性,不继承。 */
    private static final String SDDL_FILE = "S:(ML;;NW;;;LW)";
    /** 既有条目补标上限(与 FsToolSupport walk 上限同量级;超出记日志截断,新文件仍经继承获得)。 */
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_DEPTH = 24;
    /** 超过该长度需 \\?\ 前缀,否则 SetNamedSecurityInfoW 受 MAX_PATH 限制。 */
    private static final int LONG_PATH_THRESHOLD = 248;

    /** 已处理根(normalize 字符串)→ 是否成功。 */
    private static final Map<String, Boolean> DONE = new ConcurrentHashMap<>();

    private WindowsIntegrity() {
    }

    /**
     * 确保目录树可被沙箱(Low IL)进程写入:不存在视为无需处理(返回 true);
     * 已处理过直接返回缓存结果。失败返回 false(调用方据此在命令结果中提示)。
     */
    public static boolean ensureWritable(Path root) {
        if (root == null) {
            return true;
        }
        Path norm = root.toAbsolutePath().normalize();
        if (norm.getRoot() != null && norm.getRoot().equals(norm)) {
            // §13.3 修复 B L3(入口 fail-fast,防御纵深最后一道):文件系统根一律拒标——
            // 标注机器(labelTree)对着盘根跑等于把整个卷打 Low,P1 事故形态。
            // ②(工作区祖先)③(系统目录祖先)需要工作区上下文,由 L1/L2(gate/executor)判定。
            log.warn("[sandbox] L3 拒绝对文件系统根标注 Low 完整性: {}", norm);
            return false;
        }
        if (!Files.exists(norm)) {
            return true; // 目标不存在:命令访问时自然报错,无需标注
        }
        Boolean cached = DONE.get(norm.toString());
        if (cached != null) {
            return cached;
        }
        synchronized (WindowsIntegrity.class) {
            cached = DONE.get(norm.toString());
            if (cached == null) {
                cached = labelTree(norm);
                DONE.put(norm.toString(), cached);
            }
        }
        return cached;
    }

    // ---- 内部 ----

    /** 标注根目录 + 既有子树;根标注失败返回 false,子项失败只计数告警。 */
    private static boolean labelTree(Path root) {
        long t0 = System.nanoTime();
        int[] fail = { 0 };
        int[] count = { 0 };
        boolean[] capped = { false };
        boolean rootOk = Files.isDirectory(root)
                ? labelLow(root, true)
                : labelLow(root, false); // 防御:授权根理论都是目录,文件根单独标注
        if (rootOk && Files.isDirectory(root)) {
            try {
                Files.walkFileTree(root, java.util.Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (dir.equals(root)) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (capped[0]) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        count[0]++;
                        if (count[0] > MAX_ENTRIES) {
                            capped[0] = true;
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (!labelLow(dir, true)) {
                            fail[0]++;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (capped[0]) {
                            return FileVisitResult.SKIP_SIBLINGS;
                        }
                        if (attrs.isSymbolicLink()) {
                            return FileVisitResult.CONTINUE; // 链接不标(标注会落到链接目标真身)
                        }
                        count[0]++;
                        if (count[0] > MAX_ENTRIES) {
                            capped[0] = true;
                            return FileVisitResult.SKIP_SIBLINGS;
                        }
                        if (!labelLow(file, false)) {
                            fail[0]++;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        return FileVisitResult.CONTINUE; // 单项不可访问(锁定等):跳过,不阻断
                    }
                });
            } catch (IOException e) {
                log.warn("[integrity] 遍历中断(已标部分生效): {} {}", root, e.getMessage());
            }
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        if (fail[0] > 0 || capped[0]) {
            log.warn("[integrity] {} 标注完成: {} 项,失败 {} 项{}(失败项沙箱内写入会被拒) 耗时 {}ms",
                    root, count[0], fail[0], capped[0] ? ",超出 " + MAX_ENTRIES + " 上限已截断" : "", ms);
        } else {
            log.info("[integrity] {} 标注完成: {} 项,耗时 {}ms", root, count[0], ms);
        }
        return rootOk;
    }

    /**
     * 对单个文件/目录设置 Low 完整性标签(SDDL 文本与对象名均直接以 String 传给
     * jna-platform 的 *W 接口,UTF-16 映射由其 UNICODE_OPTIONS 保证)。
     * 设完整性标签不要求 SeSecurityPrivilege(不高于调用方 IL 即可,Medium 进程可设 Low)。
     */
    static boolean labelLow(Path p, boolean dir) {
        Win32Ex.SandboxAdvapi32 A = Win32Ex.SandboxAdvapi32.I;
        Win32Ex.SandboxKernel32 K = Win32Ex.SandboxKernel32.I;
        PointerByReference ppSd = new PointerByReference();
        if (!A.ConvertStringSecurityDescriptorToSecurityDescriptorW(dir ? SDDL_DIR : SDDL_FILE,
                Win32Ex.SDDL_REVISION_1, ppSd, null)) {
            log.warn("[integrity] SDDL 转换失败 err={} {}", K.GetLastError(), p);
            return false;
        }
        try {
            // ConvertString… 产出必然是自相对 SD:经 SECURITY_DESCRIPTOR_RELATIVE 取 SACL
            WinNT.ACL sacl = new WinNT.SECURITY_DESCRIPTOR_RELATIVE(ppSd.getValue()).getSystemACL();
            if (sacl == null) {
                log.warn("[integrity] 取 SACL 失败 {}", p);
                return false;
            }
            int err = A.SetNamedSecurityInfo(longPathForm(p),
                    AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT, WinNT.LABEL_SECURITY_INFORMATION,
                    null, null, null, sacl.getPointer());
            if (err != 0) {
                log.warn("[integrity] 标注失败 err={} {}", err, p);
                return false;
            }
            return true;
        } finally {
            K.LocalFree(ppSd.getValue());
        }
    }

    /** 超长路径加 \\?\ 前缀(UNC 形态转 \\?\UNC\),避开 MAX_PATH 限制。 */
    private static String longPathForm(Path p) {
        String s = p.toString();
        if (s.length() < LONG_PATH_THRESHOLD) {
            return s;
        }
        if (s.startsWith("\\\\")) {
            return "\\\\?\\UNC\\" + s.substring(2);
        }
        return "\\\\?\\" + s;
    }
}
