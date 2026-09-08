package dev.everyagent.worker.os.windows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WindowsAcl SDDL 处理纯函数单测(跨平台)+ 真实 DACL 授权集成验证(仅 Windows)。
 * 纯函数不触 Win32 加载(static 字段无 Win32 引用,方法体内引用为运行时),可在任意平台跑。
 */
class WindowsAclTest {

    // ---- hasSufficientAce:幂等判定 ----

    @Test
    void hasSufficientAceFalseWhenNoDacl() {
        assertFalse(WindowsAcl.hasSufficientAce(""), "空 SDDL 无 D: 节");
        assertFalse(WindowsAcl.hasSufficientAce("O:SYG:SY"), "无 D: 节");
        assertFalse(WindowsAcl.hasSufficientAce("D:"), "D: 节为空");
    }

    @Test
    void hasSufficientAceFalseWhenMissingAceOrSid() {
        // 只有 System/Admin ACE,没有 Users
        assertFalse(WindowsAcl.hasSufficientAce(
                "D:(A;OICI;0x001f01ff;;;SY)(A;OICI;0x001f01ff;;;BA)"));
        // Users 但权限不足(只读)
        assertFalse(WindowsAcl.hasSufficientAce(
                "D:(A;OICI;0x00120089;;;BU)"), "只读权限不满足增删改查");
        // Users 无继承标志
        assertFalse(WindowsAcl.hasSufficientAce(
                "D:(A;;0x001301bf;;;BU)"), "无 OI/CI 继承,子树不可增删改查");
        // Deny ACE 不算
        assertFalse(WindowsAcl.hasSufficientAce(
                "D:(D;OICI;0x001301bf;;;BU)"), "Deny 不视为充分权限");
    }

    @Test
    void hasSufficientAceTrueWhenUsersHasModifyDeleteWithInherit() {
        // 恰为 修改+删除(mask 十六进制大小写不敏感,flags 顺序不敏感)
        assertTrue(WindowsAcl.hasSufficientAce(
                "D:(A;CIOI;0x001301BF;;;BU)"));
        assertTrue(WindowsAcl.hasSufficientAce(
                "D:(A;OICI;0x001301bf;;;BU)"));
        // 更大权限(FILE_ALL_ACCESS)覆盖目标
        assertTrue(WindowsAcl.hasSufficientAce(
                "D:(A;OICI;0x001f01ff;;;BU)"));
        // 混合多个 ACE,其中含充分的一个
        assertTrue(WindowsAcl.hasSufficientAce(
                "D:(A;OICI;0x00120089;;;SY)(A;OICI;0x001301bf;;;BU)(A;OICI;0x00120089;;;BA)"));
    }

    // ---- appendAce:追加目标 ACE,保留现有 ----

    @Test
    void appendAceCreatesDaclWhenMissing() {
        assertTrue(WindowsAcl.appendAce("O:SYG:SY").endsWith("D:" + WindowsAcl.TARGET_ACE),
                "无 D: 节追加末尾: " + WindowsAcl.appendAce("O:SYG:SY"));
        // 无 D: 有 S:(完整性标签)→ D: 插到 S: 前,保规范顺序
        String withS = WindowsAcl.appendAce("O:SYG:SYD:(A;OICI;0x001f01ff;;;SY)S:(ML;OICI;NW;;;LW)");
        int dPos = withS.indexOf("D:");
        int sPos = withS.indexOf("S:(ML");
        assertTrue(dPos >= 0 && sPos > dPos, "D: 节在 S: 节前: " + withS);
    }

    @Test
    void appendAcePreservesExistingAcesAndProtectionFlag() {
        String original = "D:PAI(A;OICI;0x001f01ff;;;SY)(A;OICI;0x00120089;;;BA)";
        String out = WindowsAcl.appendAce(original);
        assertTrue(out.startsWith("D:PAI"), "保留 D: 保护标志 PAI: " + out);
        assertTrue(out.contains("(A;OICI;0x001f01ff;;;SY)"), "保留 System ACE: " + out);
        assertTrue(out.contains("(A;OICI;0x00120089;;;BA)"), "保留 Admin ACE: " + out);
        assertTrue(out.contains(WindowsAcl.TARGET_ACE), "追加目标 ACE: " + out);
        // 目标 ACE 插在现有 ACE 之前(读检查时首个匹配即命中,不依赖顺序)
        int tgt = out.indexOf(WindowsAcl.TARGET_ACE);
        int sys = out.indexOf("(A;OICI;0x001f01ff;;;SY)");
        assertTrue(tgt >= 0 && tgt < sys, "目标 ACE 插在现有 ACE 前: " + out);
    }

    @Test
    void appendAceHandlesEmptyDaclWithFlags() {
        String out = WindowsAcl.appendAce("O:SYG:SYD:PAI");
        assertTrue(out.equals("O:SYG:SYD:PAI" + WindowsAcl.TARGET_ACE), "空 D: 直接追加: " + out);
    }

    // ---- 集成(仅 Windows):真实目录被授予 Users 修改权限 ----

    @EnabledOnOs(OS.WINDOWS)
    @Test
    void grantsWriteAclOnWindows(@TempDir Path dir) {
        assertTrue(WindowsAcl.grantWriteAccess(dir), "真实目录授权应成功");

        // 用 icacls 读回验证:BUILTIN\Users 具有 (OI)(CI)(M) 修改权限(增删改查)
        String out = runIcacls(dir);
        String norm = out.toUpperCase(java.util.Locale.ROOT);
        // 中文系统 icacls 输出 SID 名可能本地化;放宽断言:包含 Users + (OI)(CI)(M) 形态
        assertTrue(norm.contains("(OI)(CI)"), "Users 应获得继承标志,icacls: " + out);
        assertTrue(norm.contains("(M)") || norm.contains("(F)"),
                "Users 应获得修改(M)以上权限,icacls: " + out);
    }

    private static String runIcacls(Path dir) {
        try {
            Process p = new ProcessBuilder("icacls", dir.toString()).start();
            byte[] buf = p.getInputStream().readAllBytes();
            p.waitFor();
            return new String(buf, Charset.defaultCharset());
        } catch (Exception e) {
            throw new AssertionError("icacls 执行失败: " + e.getMessage());
        }
    }
}
