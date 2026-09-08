package dev.everyagent.worker.os.windows;

import com.sun.jna.platform.win32.AccCtrl;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作区可写 ACL 授权(架构 §13.6「沙箱内增删改查权限」的 DACL 半边)。
 *
 * <p>背景:命令沙箱进程是 Restricted Token(去特权)+ Low 完整性(WindowsSandbox),
 * 其 token 保留普通用户 SID 与组成员(含 BUILTIN\Users)。{@link WindowsIntegrity}
 * 只做完整性标注(把工作区降为 Low,解决 MIC 的 NO_WRITE_UP 拦截)——但若工作区树的
 * DACL 未授予沙箱进程写/删权限(默认继承被破坏、从 FAT/网络盘迁来、ACL 残缺等),
 * 沙箱内仍会「授权了也写不进去」。本类补上 DACL 半边:给工作区根目录**追加**一个
 * 可继承的 Allow ACE——本地 Users 组 {@code (OI)(CI)} 修改+删除权限(文件对象上
 * 等价 {@code icacls <path> /grant *S-1-5-32-545:(OI)(CI)M})——使工作区整棵树
 * (含之后新建/迁移进来的文件)对沙箱进程可增删改查。
 *
 * <ul>
 *   <li><b>追加式</b>:先读现有 DACL 转 SDDL,仅在尚未具备充分权限时追加目标 ACE
 *       再写回——不覆盖/不丢弃用户对工作区已有的 ACL 设置(与完整性标注的「替换式」
 *       不同,此处必须保守);</li>
 *   <li><b>幂等</b>:SDDL 已含覆盖目标权限位的继承 Allow ACE 则跳过,重复调用不叠加;</li>
 *   <li><b>只写根</b>:目标 ACE 带 {@code (OI)(CI)} 继承,设目录 DACL 时系统会把新继承
 *       ACE 传播到既有子对象(重置继承),无需递归遍历;</li>
 *   <li><b>仅 Windows 运行时被调用</b>;本类所有平台均可编译,纯函数可跨平台单测。</li>
 * </ul>
 *
 * <p><b>实现:jna-platform</b>:DACL 读写走平台自带映射
 * ({@code Advapi32.GetNamedSecurityInfo} / {@code SetNamedSecurityInfo},对象名直传
 * String,UTF-16 编码由其 UNICODE_OPTIONS 保证),SDDL ↔ SD 双向转换与 DACL 提取经
 * {@link Win32Ex} 补充的原语 + {@code SECURITY_DESCRIPTOR_RELATIVE}。纯函数测试
 * (hasSufficientAce / appendAce)在任意平台加载本类不触库加载(原生库在 Win32Ex 内
 * 懒加载)。
 *
 * <p>授予 {@code BUILTIN\Users} 而非 {@code Everyone}/当前用户 SID:本地 Users 覆盖
 * 一切正常本地/域账户(沙箱进程 token 必带),又不向匿名/来宾开放;worker 自身本就是
 * 工作区 owner,追加该 ACE 不改变 owner 既有权限,只是把「增删改查」显式授予沙箱进程
 * 所在的本地用户集合。
 */
public final class WindowsAcl {

    private static final Logger log = LoggerFactory.getLogger(WindowsAcl.class);

    private static final Win32Ex.SandboxAdvapi32 A = Win32Ex.SandboxAdvapi32.I;
    private static final Win32Ex.SandboxKernel32 K = Win32Ex.SandboxKernel32.I;

    // ---- 常量 ----
    /** SecurityInfo 位:读取/设置 DACL(访问控制表)。 */
    private static final int DACL_SECURITY_INFORMATION = 0x00000004;
    /** SDDL 版本(与 Convert/GetSecurityDescriptor* 配套)。 */
    private static final int SDDL_REVISION_1 = 1;

    /** 目标权限位:文件对象上的 修改+删除(增删改查)。= FILE_GENERIC_READ|WRITE|EXECUTE|DELETE。 */
    static final int MODIFY_DELETE_MASK = 0x001301BF;
    /** SDDL 里 BUILTIN\Users 的宏(S-1-5-32-545)。 */
    static final String BUILTIN_USERS_SDDL = "BU";
    /** 要追加的 ACE 文本(小写权限位;SDDL 大小写不敏感)。继承 = 容器+对象,权限 = 修改+删除。 */
    static final String TARGET_ACE = "(A;OICI;0x001301bf;;;BU)";

    /** DACL ACE 提取:{(type)};{flags};{mask};;;{sid})。 */
    private static final Pattern ACE = Pattern.compile(
            "\\(([^;]*);([^;]*);([^;]*);;;([^)]*)\\)");

    /** 已处理根(normalize 字符串)→ 是否成功;每 worker 进程每根一次,与 WindowsIntegrity 同语义。 */
    private static final java.util.Map<String, Boolean> DONE = new ConcurrentHashMap<>();

    private WindowsAcl() {
    }

    /**
     * 确保工作区根及其整棵子树对沙箱进程可「增删改查」:目录不存在视为无需处理(返回 true);
     * 已处理过直接返回缓存结果(幂等,零每命令开销)。失败返回 false(调用方据此告警)。
     */
    public static boolean grantWriteAccess(Path root) {
        if (root == null) {
            return true;
        }
        Path norm = root.toAbsolutePath().normalize();
        if (norm.getRoot() != null && norm.getRoot().equals(norm)) {
            // §13.3 修复 B L3(入口 fail-fast):文件系统根一律拒授——给盘根追加 Users 可写
            // ACE 等于放开整卷增删改查(2026-08-31 实测 C:\ 根残留即此形态)。
            // ②③(工作区/系统目录祖先)由 L1/L2(gate/executor,持有上下文)判定。
            log.warn("[sandbox] L3 拒绝对文件系统根追加可写 ACL: {}", norm);
            return false;
        }
        if (!Files.exists(norm)) {
            return true; // 目标不存在:命令访问时自然报错,无需授权
        }
        String key = norm.toString();
        Boolean cached = DONE.get(key);
        if (cached != null) {
            return cached;
        }
        synchronized (WindowsAcl.class) {
            cached = DONE.get(key);
            if (cached == null) {
                cached = doGrant(norm);
                DONE.put(key, cached); // 失败也缓存:免得每条命令重试刷日志(与 ensureWritable 同语义)
            }
        }
        return cached;
    }

    /** 单次实际授权:读现有 DACL → 幂等检查 → 追加写回。 */
    private static boolean doGrant(Path norm) {
        String name = longPathForm(norm);
        PointerByReference ppSd = new PointerByReference();
        int err = A.GetNamedSecurityInfo(name, AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT,
                DACL_SECURITY_INFORMATION, null, null, null, null, ppSd);
        if (err != 0) {
            log.warn("[acl] 读取工作区 DACL 失败 err={} {}", err, norm);
            return false;
        }
        try {
            String sddl = toSddl(ppSd.getValue());
            if (sddl == null) {
                return false;
            }
            if (hasSufficientAce(sddl)) {
                return true; // 幂等:已具备充分权限
            }
            return apply(appendAce(sddl), name, norm);
        } finally {
            K.LocalFree(ppSd.getValue());
        }
    }

    // ---- Win32 调用(jna-platform + Win32Ex) ----

    /** SD → SDDL 文本(失败返回 null)。 */
    private static String toSddl(com.sun.jna.Pointer sd) {
        PointerByReference ppSddl = new PointerByReference();
        if (!A.ConvertSecurityDescriptorToStringSecurityDescriptorW(sd,
                SDDL_REVISION_1, DACL_SECURITY_INFORMATION, ppSddl, new IntByReference())) {
            log.warn("[acl] SD 转 SDDL 失败 err={}", K.GetLastError());
            return null;
        }
        try {
            return ppSddl.getValue().getWideString(0); // *W 出参:UTF-16 字符串
        } finally {
            K.LocalFree(ppSddl.getValue());
        }
    }

    /** 追加后的 SDDL → SD → 取 DACL → SetNamedSecurityInfo 写回(返回是否成功)。 */
    private static boolean apply(String sddl, String name, Path norm) {
        PointerByReference ppSd = new PointerByReference();
        if (!A.ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl,
                SDDL_REVISION_1, ppSd, new IntByReference())) {
            log.warn("[acl] 追加后 SDDL 转 SD 失败 err={}", K.GetLastError());
            return false;
        }
        try {
            // ConvertString… 产出必然是自相对 SD:经 SECURITY_DESCRIPTOR_RELATIVE 取 DACL
            WinNT.ACL dacl = new WinNT.SECURITY_DESCRIPTOR_RELATIVE(ppSd.getValue()).getDiscretionaryACL();
            if (dacl == null) {
                log.warn("[acl] 取追加后 DACL 失败 {}", norm);
                return false;
            }
            int err = A.SetNamedSecurityInfo(name, AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT,
                    DACL_SECURITY_INFORMATION, null, null, dacl.getPointer(), null);
            if (err != 0) {
                log.warn("[acl] 设置工作区 DACL 失败 err={} {}", err, norm);
                return false;
            }
            log.info("[acl] 工作区已授予沙箱可写 ACL(增删改查): {}", norm);
            return true;
        } finally {
            K.LocalFree(ppSd.getValue());
        }
    }

    // ---- SDDL 处理(纯函数,跨平台可单测) ----

    /**
     * 现有 DACL 是否已含「授予 BUILTIN\Users 的、覆盖目标权限位的、可继承 Allow ACE」。
     * 只比较十六进制权限位(Convert…ToString 输出为十六进制);非十六进制 mask(如 SDDL 宏)
     * 保守视为不充分(允许追加,无害)。
     */
    static boolean hasSufficientAce(String sddl) {
        String dacl = daclSection(sddl);
        if (dacl == null || dacl.isEmpty()) {
            return false;
        }
        Matcher m = ACE.matcher(dacl);
        while (m.find()) {
            if (!"A".equalsIgnoreCase(m.group(1).trim())) {
                continue; // 只认 Allow(非 Deny/审计)
            }
            if (!BUILTIN_USERS_SDDL.equalsIgnoreCase(m.group(4).trim())) {
                continue; // 只认本地 Users
            }
            String flags = m.group(2).toUpperCase(Locale.ROOT);
            if (!flags.contains("OI") || !flags.contains("CI")) {
                continue; // 需容器+对象双继承,子树才能增删改查
            }
            long mask = parseMask(m.group(3));
            if (mask != 0 && (mask & MODIFY_DELETE_MASK) == MODIFY_DELETE_MASK) {
                return true; // 已覆盖目标权限位
            }
        }
        return false;
    }

    /** 在 SDDL 的 D: 节追加目标 ACE(保留现有 ACE 与 D: 保护标志;无 D: 节则新建)。 */
    static String appendAce(String sddl) {
        int dStart = daclStart(sddl);
        if (dStart < 0) {
            // 无 D: 节:插到 S: 节前(规范顺序 O,G,D,S),无 S: 则追加末尾
            int sStart = sddl.indexOf("S:");
            return sStart >= 0
                    ? sddl.substring(0, sStart) + "D:" + TARGET_ACE + sddl.substring(sStart)
                    : sddl + "D:" + TARGET_ACE;
        }
        // 有 D: 节:rest = ":PAI(A;...)(A;...)" 或 ":PAI" 或 ":"(dStart 指向 'D')
        String before = sddl.substring(0, dStart);
        String rest = sddl.substring(dStart); // 以 "D:" 开头
        int paren = rest.indexOf('(');
        if (paren >= 0) {
            // 在第一个既有 ACE 前插入目标 ACE
            return before + rest.substring(0, paren) + TARGET_ACE + rest.substring(paren);
        }
        // 空 ACE(或仅保护标志):直接追加
        return sddl + TARGET_ACE;
    }

    /** D: 节起始下标(指向 'D'),无 D: 节返回 -1。 */
    private static int daclStart(String sddl) {
        // "D:" 两字符序列在 SDDL 里只出现在 D: 节标记处(SID 文本不含 "D:"——
        // 宏 WD 是 "WD"(D 后非 ':'),SID 字符串 S-1-5-… 的 S 后是 '-' 不是 ':');
        // 故用 indexOf("D:") 精确定位,避免 "SYD:PAI" 里 SY 后的 D 被误判为节标记。
        return sddl.indexOf("D:");
    }

    /** 提取 D: 节(含 "D:" 前缀;无则返回 null)。 */
    private static String daclSection(String sddl) {
        int start = daclStart(sddl);
        if (start < 0) {
            return null;
        }
        // D: 节到下一个节标记(S:)为止;S: 同样只在节标记处出现(SID 不含 "S:")
        int end = sddl.indexOf("S:", start + 2);
        return end >= 0 ? sddl.substring(start, end) : sddl.substring(start);
    }

    /** SDDL 权限位 → long;仅支持十六进制(0x…)与空/通配;其余(宏)返回 0。 */
    private static long parseMask(String mask) {
        String s = mask.trim();
        if (s.isEmpty() || s.equals("0")) {
            return 0;
        }
        if (s.toLowerCase(Locale.ROOT).startsWith("0x")) {
            try {
                return Long.parseLong(s.substring(2), 16);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /** 超长路径加 \\\\?\\ 前缀(UNC 形态转 \\\\?\\UNC\\),避开 MAX_PATH 限制。 */
    private static String longPathForm(Path p) {
        String s = p.toString();
        if (s.length() < 248) {
            return s;
        }
        if (s.startsWith("\\\\")) {
            return "\\\\?\\UNC\\" + s.substring(2);
        }
        return "\\\\?\\" + s;
    }
}
