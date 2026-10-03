package dev.everyagent.plugin.sandbox.codex.setup;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;

import dev.everyagent.plugin.sandbox.codex.win.Advapi32Ex;
import dev.everyagent.plugin.sandbox.codex.win.struct.AclStructs;
import dev.everyagent.plugin.sandbox.codex.win.struct.AclStructs.EXPLICIT_ACCESS_W;
import dev.everyagent.plugin.sandbox.codex.win.struct.AclStructs.TRUSTEE_W;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 状态根目录与三个沙箱目录的 DACL 锁定（对应 codex setup.rs 目录约定 +
 * setup_provisioning.rs::lock_sandbox_dir/lock_persistent_sandbox_dirs/
 * lock_sandbox_bin_dir；分析文档 §5.2.5）。
 *
 * <p>状态根目录默认 {@code %USERPROFILE%\.everyagent-codex-sandbox}（配置
 * {@code codex.home} 可覆盖），含 {@code .sandbox}/
 * {@code .sandbox-secrets}/{@code .sandbox-bin} 三子目录（结构对齐 CODEX_HOME/.sandbox*）。
 *
 * <p>目录矩阵（组=EveryAgentCodexSandboxUsers，real_user=真实用户）：
 * <table border="1">
 * <tr><th>目录</th><th>组 ACE</th><th>real_user 掩码</th><th>DACL 继承</th></tr>
 * <tr><td>.sandbox</td><td>GRANT RWX+DELETE</td><td>RWX（无 DELETE）</td><td>Inherited</td></tr>
 * <tr><td>.sandbox-secrets</td><td><b>DENY</b> RWX+DELETE</td><td>RWX</td><td>Inherited</td></tr>
 * <tr><td>.sandbox-bin</td><td>GRANT R+X</td><td>RWX+DELETE+WRITE_DAC</td><td>Protected</td></tr>
 * </table>
 * （.sandbox-bin 的 owner 不提权也能重刷 Protected DACL。）
 */
public final class SandboxDirs {

    /** 状态根目录名（默认 codexHome，配置键 {@code codex.home} 可覆盖）。 */
    public static final String DEFAULT_HOME_DIR_NAME = ".everyagent-codex-sandbox";

    /** well-known SID：SYSTEM。 */
    public static final String SID_SYSTEM = "S-1-5-18";
    /** well-known SID：Administrators。 */
    public static final String SID_ADMINS = "S-1-5-32-544";

    // ---- 访问掩码（winnt.h；对齐 lock_sandbox_dir 组合） ----

    /** FILE_GENERIC_READ。 */
    public static final int FILE_GENERIC_READ = 0x000120089;
    /** FILE_GENERIC_WRITE。 */
    public static final int FILE_GENERIC_WRITE = 0x000120116;
    /** FILE_GENERIC_EXECUTE。 */
    public static final int FILE_GENERIC_EXECUTE = 0x0001200A0;
    /** DELETE（删除子对象）。 */
    public static final int DELETE = 0x00010000;
    /** WRITE_DAC（重刷 DACL 所需）。 */
    public static final int WRITE_DAC = 0x00040000;

    /** RWX 组合（FILE_GENERIC_READ|WRITE|EXECUTE）。 */
    public static final int MASK_RWX = FILE_GENERIC_READ | FILE_GENERIC_WRITE | FILE_GENERIC_EXECUTE;
    /** RWX + DELETE。 */
    public static final int MASK_RWX_DELETE = MASK_RWX | DELETE;
    /** R + X。 */
    public static final int MASK_RX = FILE_GENERIC_READ | FILE_GENERIC_EXECUTE;
    /** RWX + DELETE + WRITE_DAC（bin 目录 owner）。 */
    public static final int MASK_RWX_DELETE_WRITE_DAC = MASK_RWX_DELETE | WRITE_DAC;

    private SandboxDirs() {
    }

    /** 默认状态根目录：{@code %USERPROFILE%\.everyagent-codex-sandbox}。 */
    public static Path defaultCodexHome() {
        return Path.of(System.getProperty("user.home"), DEFAULT_HOME_DIR_NAME);
    }

    /** {@code <codexHome>/.sandbox}（marker/日志/临时所在）。 */
    public static Path sandboxDir(Path codexHome) {
        return codexHome.resolve(".sandbox");
    }

    /** {@code <codexHome>/.sandbox-secrets}（DPAPI 凭据，组 DENY）。 */
    public static Path sandboxSecretsDir(Path codexHome) {
        return codexHome.resolve(".sandbox-secrets");
    }

    /** {@code <codexHome>/.sandbox-bin}（runner.jar 物化，组 R+X，Protected DACL）。 */
    public static Path sandboxBinDir(Path codexHome) {
        return codexHome.resolve(".sandbox-bin");
    }

    /** 一次性建齐三目录（helper 前置，对齐 run_payload 的 create_dir_all）。 */
    public static void ensureAll(Path codexHome) throws IOException {
        Files.createDirectories(sandboxDir(codexHome));
        Files.createDirectories(sandboxSecretsDir(codexHome));
        Files.createDirectories(sandboxBinDir(codexHome));
    }

    /** 锁定 .sandbox（组 GRANT RWX+DELETE / real_user RWX / 继承保留）。 */
    public static void lockSandboxDir(Path codexHome, String groupSid, String realUser) {
        lockDir(sandboxDir(codexHome), groupSid, true, MASK_RWX_DELETE, realUser, MASK_RWX,
                false);
    }

    /** 锁定 .sandbox-secrets（组 DENY RWX+DELETE / real_user RWX / 继承保留）。 */
    public static void lockSecretsDir(Path codexHome, String groupSid, String realUser) {
        lockDir(sandboxSecretsDir(codexHome), groupSid, false, MASK_RWX_DELETE, realUser,
                MASK_RWX, false);
    }

    /** 锁定 .sandbox-bin（组 GRANT R+X / real_user RWX+DELETE+WRITE_DAC / Protected）。 */
    public static void lockBinDir(Path codexHome, String groupSid, String realUser) {
        lockDir(sandboxBinDir(codexHome), groupSid, true, MASK_RX, realUser,
                MASK_RWX_DELETE_WRITE_DAC, true);
    }

    /**
     * 通用目录锁定（对齐 lock_sandbox_dir）：四条 EXPLICIT_ACCESS_W
     * （组/SYSTEM/Administrators/real_user，OI|CI）经 SetEntriesInAclW 重建 DACL，
     * SetNamedSecurityInfoW 写回；protected=true 时切断继承。
     *
     * @throws SetupErrorReport.SetupException 任何 Win32 失败（HELPER_SANDBOX_LOCK_FAILED）
     */
    public static void lockDir(Path dir, String groupSid, boolean groupGrant, int groupMask,
            String realUser, int realUserMask, boolean protectedDacl) {
        Entry[] entries = {
            new Entry(groupSid, groupGrant ? Advapi32Ex.GRANT_ACCESS : Advapi32Ex.DENY_ACCESS,
                    groupMask),
            new Entry(SID_SYSTEM, Advapi32Ex.GRANT_ACCESS, MASK_RWX_DELETE),
            new Entry(SID_ADMINS, Advapi32Ex.GRANT_ACCESS, MASK_RWX_DELETE),
            new Entry(sidOf(realUser), Advapi32Ex.GRANT_ACCESS, realUserMask),
        };
        EXPLICIT_ACCESS_W[] eas = new EXPLICIT_ACCESS_W[entries.length];
        WinNT.PSIDByReference[] sidRefs = new WinNT.PSIDByReference[entries.length];
        try {
            for (int i = 0; i < entries.length; i++) {
                sidRefs[i] = new WinNT.PSIDByReference();
                if (!Advapi32.INSTANCE.ConvertStringSidToSid(entries[i].sid(), sidRefs[i])) {
                    throw lockFailure(dir, "ConvertStringSidToSid for " + entries[i].sid());
                }
                eas[i] = explicitAccess(sidRefs[i].getValue().getPointer(), entries[i].mode(),
                        entries[i].mask());
            }
            PointerByReference newAcl = new PointerByReference();
            int set = Advapi32Ex.INSTANCE.SetEntriesInAclW(eas.length,
                    AclStructs.contiguous(eas), null, newAcl);
            if (set != 0) {
                throw lockFailure(dir, "SetEntriesInAclW sandbox dir failed: " + set);
            }
            try {
                int securityInfo = protectedDacl
                        ? Advapi32Ex.DACL_SECURITY_INFORMATION
                                | Advapi32Ex.PROTECTED_DACL_SECURITY_INFORMATION
                        : Advapi32Ex.DACL_SECURITY_INFORMATION;
                int result = Advapi32.INSTANCE.SetNamedSecurityInfo(dir.toString(),
                        1 /* SE_FILE_OBJECT */, securityInfo, null, null, newAcl.getValue(),
                        null);
                if (result != 0) {
                    throw lockFailure(dir, "SetNamedSecurityInfoW sandbox dir failed: " + result);
                }
            } finally {
                Kernel32.INSTANCE.LocalFree(newAcl.getValue());
            }
        } finally {
            for (WinNT.PSIDByReference ref : sidRefs) {
                if (ref != null && ref.getValue() != null) {
                    Kernel32.INSTANCE.LocalFree(ref.getValue().getPointer());
                }
            }
        }
    }

    private static EXPLICIT_ACCESS_W explicitAccess(Pointer psid, int mode, int mask) {
        EXPLICIT_ACCESS_W ea = new EXPLICIT_ACCESS_W();
        ea.grfAccessPermissions = mask;
        ea.grfAccessMode = mode;
        ea.grfInheritance = Advapi32Ex.OBJECT_INHERIT_ACE | Advapi32Ex.CONTAINER_INHERIT_ACE;
        TRUSTEE_W trustee = ea.Trustee;
        trustee.pMultipleTrustee = null;
        trustee.MultipleTrusteeOperation = 0;
        trustee.TrusteeForm = 0; // TRUSTEE_IS_SID
        trustee.TrusteeType = 0; // 由 SID 形态决定，类型槽不参与解析
        trustee.ptstrName = psid;
        ea.write();
        return ea;
    }

    private static String sidOf(String accountName) {
        return com.sun.jna.platform.win32.Advapi32Util.getAccountByName(accountName).sidString;
    }

    private static SetupErrorReport.SetupException lockFailure(Path dir, String detail) {
        return new SetupErrorReport.SetupException(SetupErrorReport.HELPER_SANDBOX_LOCK_FAILED,
                "lock sandbox dir " + dir + " failed: " + detail);
    }

    private record Entry(String sid, int mode, int mask) {
    }
}
