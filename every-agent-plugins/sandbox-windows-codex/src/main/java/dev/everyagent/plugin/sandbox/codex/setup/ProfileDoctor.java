package dev.everyagent.plugin.sandbox.codex.setup;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;

import dev.everyagent.plugin.sandbox.codex.win.Advapi32Ex;
import dev.everyagent.plugin.sandbox.codex.win.UserenvEx;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 沙箱账户 profile 一致性治理（FULL setup 阶段，design.md §4.3.1 #8；仅提权 helper 调用）。
 *
 * <p><b>治理什么</b>：沙箱账户被删重建（自愈轮换/外部卸载重装）会拿到<b>新 RID</b>，但
 * {@code C:\Users\<account>} 的旧 NTUSER.DAT 残留——其 hive 内部 ACL 只授权已删的旧 SID。
 * {@code LOGON_WITH_PROFILE} 照常加载它（加载由 SYSTEM 完成），新账户读 HKCU 却全拒读：
 * PowerShell 5.1 启动读 GPO 策略键即 SecurityException 崩溃（0xE0434352），其余程序
 * 静默。判定标准 = {@code NTUSER.DAT} <b>文件属主</b>（profile 创建时系统以账户 SID 落
 * 属主，与 hive 内部 ACL 同源）≠ 当前账户 SID 即外来 profile → 删掉让下次登录重建。
 *
 * <p><b>删除路径</b>：{@code DeleteProfileW(sid)}（卸载在用 hive + 删目录 + 清
 * ProfileList 注册条目，全程系统口径）为主；hive 仍被存活 runner 的登录会话持有会失败
 * （ERROR_ACCESS_DENIED）——重试数毫秒级退避，调用方（worker 自愈链）已先
 * {@code RunnerPools.invalidateAll} 终止全部 runner，正常路径无此竞态。失败再兜底
 * 直接删目录（管理员对旧账户目录持 FullControl），并连带清理
 * {@code C:\Users\<名>.<机器名>} 冲突变体残留（Windows 目录占用时的回退产物，不再被
 * 任何 ProfileList 条目引用）。全部 best-effort：失败只记日志，绝不阻断 setup。
 *
 * <p>纯逻辑经 {@link Ops} seam 注入，跨平台可单测；生产实现 {@link WindowsOps} 仅
 * Windows（helper 守卫下调用）。
 */
public final class ProfileDoctor {

    private static final System.Logger LOG =
            System.getLogger(ProfileDoctor.class.getName());

    /** DeleteProfileW 失败重试（hive 卸载竞态的毫秒级退避）。 */
    private static final int DELETE_PROFILE_ATTEMPTS = 5;
    private static final long DELETE_PROFILE_RETRY_BACKOFF_MS = 300;

    /** 测试 seam：属主读取 / 注册删除 / 目录删除（跨平台单测注入 fake）。 */
    public interface Ops {
        /**
         * @return NTUSER.DAT 文件属主 SID 字符串；文件不存在返回 null；
         *         存在但读不出属主返回 {@link #OWNER_UNKNOWN}。
         */
        String ntuserDatOwnerSid(Path ntuserDat);

        /** DeleteProfileW 封装：成功 true；profile 本就不存在也 true。 */
        boolean deleteRegisteredProfile(String accountSid);

        /** 兜底递归删除目录（best-effort，失败内部吞掉只返回 void）。 */
        void deleteTreeBestEffort(Path dir);
    }

    /** 属主存在但读取失败（无法证明外来——保守放行，不触发重建）。 */
    public static final String OWNER_UNKNOWN = "<owner-unknown>";

    private ProfileDoctor() {
    }

    /**
     * 确保账户 profile「属主正确」：外来（属主 ≠ 当前账户 SID）即删除注册与目录，
     * 下次登录由 LOGON_WITH_PROFILE 重建干净 profile；健康/无 profile 则零动作。
     *
     * @param username 沙箱账户名（目录名同）
     * @param accountSid 账户当前 SID 字符串
     * @param usersRoot profile 根目录（Windows 默认 {@code %SystemDrive%\Users}）
     * @param ops 平台操作 seam
     */
    public static void ensureAccountProfileFresh(String username, String accountSid,
            Path usersRoot, Ops ops) {
        Path profileDir = usersRoot.resolve(username);
        Path ntuserDat = profileDir.resolve("NTUSER.DAT");
        String owner = ops.ntuserDatOwnerSid(ntuserDat);
        if (owner == null) {
            return; // 无 hive：从未登录过（首启建 profile）或目录已被清——无外来 profile 可言
        }
        if (accountSid.equals(owner) || OWNER_UNKNOWN.equals(owner)) {
            return; // 属主正确，或读不出（保守不动作）
        }
        LOG.log(System.Logger.Level.WARNING,
                "[profile-doctor] 外来 profile 检出: {0} 属主 {1} ≠ 当前账户 {2}"
                        + " (账户重建换 RID 的残留)——删除后由下次登录重建",
                new Object[] { ntuserDat, owner, accountSid });
        boolean deleted = false;
        for (int i = 1; i <= DELETE_PROFILE_ATTEMPTS && !deleted; i++) {
            deleted = ops.deleteRegisteredProfile(accountSid);
            if (!deleted && i < DELETE_PROFILE_ATTEMPTS) {
                try {
                    Thread.sleep(DELETE_PROFILE_RETRY_BACKOFF_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (!deleted) {
            LOG.log(System.Logger.Level.WARNING,
                    "[profile-doctor] DeleteProfileW 未成（hive 在用或不存在注册条目），兜底直接删目录");
        }
        // 兜底与残留清理：主目录（DeleteProfileW 失败/漏删）+ 冲突变体目录（不再被引用）
        if (Files.isDirectory(profileDir)) {
            ops.deleteTreeBestEffort(profileDir);
        }
        try (DirectoryStream<Path> variants = Files.newDirectoryStream(usersRoot,
                username + ".*")) {
            for (Path variant : variants) {
                if (Files.isDirectory(variant)) {
                    LOG.log(System.Logger.Level.WARNING,
                            "[profile-doctor] 清理冲突变体残留目录: {0}", variant);
                    ops.deleteTreeBestEffort(variant);
                }
            }
        } catch (IOException ignored) {
            // 变体清理失败不阻断——它们不被 ProfileList 引用，只是磁盘残留
        }
    }

    /** 生产实现（Windows + 提权 helper 内使用）。 */
    public static final class WindowsOps implements Ops {

        @Override
        public String ntuserDatOwnerSid(Path ntuserDat) {
            if (!Files.isRegularFile(ntuserDat)) {
                return null;
            }
            WinNT.HANDLE handle = Kernel32.INSTANCE.CreateFile(ntuserDat.toString(),
                    WinNT.READ_CONTROL, 0, null, WinNT.OPEN_EXISTING,
                    WinNT.FILE_FLAG_BACKUP_SEMANTICS, null);
            if (handle == null || handle.getPointer() == null
                    || com.sun.jna.Pointer.nativeValue(handle.getPointer()) == -1
                    || com.sun.jna.Pointer.nativeValue(handle.getPointer()) == 0) {
                LOG.log(System.Logger.Level.WARNING,
                        "[profile-doctor] 打不开 {0} (err={1})——视为属主未知,保守放行",
                        new Object[] { ntuserDat, Kernel32.INSTANCE.GetLastError() });
                return OWNER_UNKNOWN;
            }
            try {
                PointerByReference owner = new PointerByReference();
                PointerByReference sd = new PointerByReference();
                int rc = Advapi32.INSTANCE.GetSecurityInfo(handle,
                        dev.everyagent.plugin.sandbox.codex.acl.AclMasks.SE_FILE_OBJECT,
                        Advapi32Ex.INSTANCE.OWNER_SECURITY_INFORMATION,
                        owner, null, null, null, sd);
                if (rc != 0) {
                    Kernel32.INSTANCE.LocalFree(sd.getValue());
                    LOG.log(System.Logger.Level.WARNING,
                            "[profile-doctor] GetSecurityInfo({0}) 失败 rc={1}——视为属主未知",
                            new Object[] { ntuserDat, rc });
                    return OWNER_UNKNOWN;
                }
                try {
                    Pointer sid = owner.getValue();
                    if (sid == null || Pointer.nativeValue(sid) == 0) {
                        return OWNER_UNKNOWN;
                    }
                    return Advapi32Util.convertSidToStringSid(new WinNT.PSID(sid));
                } finally {
                    Kernel32.INSTANCE.LocalFree(sd.getValue());
                }
            } finally {
                Kernel32.INSTANCE.CloseHandle(handle);
            }
        }

        @Override
        public boolean deleteRegisteredProfile(String accountSid) {
            int rc = UserenvEx.INSTANCE.DeleteProfileW(accountSid, null, null);
            if (rc == 0) {
                return true;
            }
            // 2 = ERROR_FILE_NOT_FOUND：注册条目本就不存在（已清/从未注册）视为成功
            LOG.log(System.Logger.Level.WARNING,
                    "[profile-doctor] DeleteProfileW({0}) rc={1}", new Object[] { accountSid, rc });
            return rc == 2;
        }

        @Override
        public void deleteTreeBestEffort(Path dir) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 单项失败继续——目录级兜底，不追求完美
                    }
                });
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "[profile-doctor] 兜底删目录失败 {0}: {1}",
                        new Object[] { dir, e.toString() });
            }
        }
    }
}
