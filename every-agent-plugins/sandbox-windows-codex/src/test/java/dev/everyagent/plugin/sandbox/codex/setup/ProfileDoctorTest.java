package dev.everyagent.plugin.sandbox.codex.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProfileDoctor} 纯逻辑单测（{@link ProfileDoctor.Ops} seam 注入 fake，
 * 目录用 @TempDir 真实文件系统——变体清理 glob 与 Files.isDirectory 判定需要真目录）。
 */
class ProfileDoctorTest {

    private static final String NEW_SID = "S-1-5-21-1-1-1-1014";
    private static final String OLD_SID = "S-1-5-21-1-1-1-1009";

    /** fake seam：属主表 + DeleteProfileW 调用记录 + 可控失败次数；目录删除真删。 */
    private static final class FakeOps implements ProfileDoctor.Ops {
        final Map<Path, String> owners = new HashMap<>();
        final List<String> deleteProfileCalls = new ArrayList<>();
        final List<Path> deletedTrees = new ArrayList<>();
        /** 前 N 次 DeleteProfileW 返回失败（模拟 hive 在用），之后成功；-1 = 恒失败。 */
        int failDeleteProfileTimes = 0;

        @Override
        public String ntuserDatOwnerSid(Path ntuserDat) {
            return owners.get(ntuserDat);
        }

        @Override
        public boolean deleteRegisteredProfile(String accountSid) {
            deleteProfileCalls.add(accountSid);
            if (failDeleteProfileTimes < 0) {
                return false;
            }
            if (failDeleteProfileTimes > 0) {
                failDeleteProfileTimes--;
                return false;
            }
            return true;
        }

        @Override
        public void deleteTreeBestEffort(Path dir) {
            deletedTrees.add(dir);
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 与生产 best-effort 语义一致
                    }
                });
            } catch (IOException ignored) {
                // 同上
            }
        }
    }

    private static Path mkProfile(Path usersRoot, String name) throws IOException {
        Path dir = usersRoot.resolve(name);
        Files.createDirectories(dir);
        Files.createFile(dir.resolve("NTUSER.DAT"));
        return dir;
    }

    /** 无 profile（从未登录/已清）→ 零动作。 */
    @Test
    void noProfileIsNoop(@TempDir Path usersRoot) {
        FakeOps ops = new FakeOps();
        ProfileDoctor.ensureAccountProfileFresh("EACodexOnline", NEW_SID, usersRoot, ops);
        assertTrue(ops.deleteProfileCalls.isEmpty(), "不应调 DeleteProfileW");
        assertTrue(ops.deletedTrees.isEmpty(), "不应删任何目录");
    }

    /** 属主正确 → 零动作（健康 profile 不重建，保住账户级缓存）。 */
    @Test
    void ownedProfileIsKept(@TempDir Path usersRoot) throws IOException {
        Path profile = mkProfile(usersRoot, "EACodexOnline");
        FakeOps ops = new FakeOps();
        ops.owners.put(profile.resolve("NTUSER.DAT"), NEW_SID);
        ProfileDoctor.ensureAccountProfileFresh("EACodexOnline", NEW_SID, usersRoot, ops);
        assertTrue(ops.deleteProfileCalls.isEmpty());
        assertTrue(ops.deletedTrees.isEmpty());
        assertTrue(Files.isDirectory(profile), "目录应保留");
    }

    /** 属主读取失败（OWNER_UNKNOWN）→ 保守放行。 */
    @Test
    void ownerUnknownIsNoop(@TempDir Path usersRoot) throws IOException {
        Path profile = mkProfile(usersRoot, "EACodexOnline");
        FakeOps ops = new FakeOps();
        ops.owners.put(profile.resolve("NTUSER.DAT"), ProfileDoctor.OWNER_UNKNOWN);
        ProfileDoctor.ensureAccountProfileFresh("EACodexOnline", NEW_SID, usersRoot, ops);
        assertTrue(ops.deleteProfileCalls.isEmpty());
        assertTrue(Files.isDirectory(profile));
    }

    /** 外来 profile（属主=旧 SID）→ DeleteProfileW(当前 SID) + 目录清理 + 变体残留清理。 */
    @Test
    void foreignProfileIsDeletedWithVariants(@TempDir Path usersRoot) throws IOException {
        Path profile = mkProfile(usersRoot, "EACodexOnline");
        Path variant = mkProfile(usersRoot, "EACodexOnline.DESKTOP-XYZ");
        FakeOps ops = new FakeOps();
        ops.owners.put(profile.resolve("NTUSER.DAT"), OLD_SID);
        ProfileDoctor.ensureAccountProfileFresh("EACodexOnline", NEW_SID, usersRoot, ops);
        assertEquals(List.of(NEW_SID), ops.deleteProfileCalls,
                "以当前账户 SID 调 DeleteProfileW（系统按注册条目定位删除）");
        assertTrue(ops.deletedTrees.contains(profile), "主目录兜底删除");
        assertFalse(Files.isDirectory(variant), "冲突变体残留目录应被清理");
        assertFalse(Files.isDirectory(profile));
    }

    /** DeleteProfileW 先失败（hive 在用）→ 毫秒级退避重试。 */
    @Test
    void deleteProfileRetriedOnTransientFailure(@TempDir Path usersRoot) throws IOException {
        Path profile = mkProfile(usersRoot, "EACodexOnline");
        FakeOps ops = new FakeOps();
        ops.owners.put(profile.resolve("NTUSER.DAT"), OLD_SID);
        ops.failDeleteProfileTimes = 2; // 前两次失败（模拟 hive 卸载竞态）
        ProfileDoctor.ensureAccountProfileFresh("EACodexOnline", NEW_SID, usersRoot, ops);
        assertEquals(3, ops.deleteProfileCalls.size(), "两次失败后第三次成功");
    }

    /** DeleteProfileW 恒失败 → 兜底直接删目录（管理员对旧账户目录持 FullControl）。 */
    @Test
    void foreignProfileFallsBackToDirectDelete(@TempDir Path usersRoot) throws IOException {
        Path profile = mkProfile(usersRoot, "EACodexOnline");
        FakeOps ops = new FakeOps();
        ops.owners.put(profile.resolve("NTUSER.DAT"), OLD_SID);
        ops.failDeleteProfileTimes = -1; // 恒失败
        ProfileDoctor.ensureAccountProfileFresh("EACodexOnline", NEW_SID, usersRoot, ops);
        assertFalse(Files.isDirectory(profile), "兜底删除应清掉主目录");
    }

    /** 变体目录只在「确定外来」时连带清理——健康 profile 下不碰（可能仍被引用）。 */
    @Test
    void variantDirsUntouchedWhenProfileHealthy(@TempDir Path usersRoot) throws IOException {
        Path profile = mkProfile(usersRoot, "EACodexOnline");
        Path variant = mkProfile(usersRoot, "EACodexOnline.DESKTOP-XYZ");
        FakeOps ops = new FakeOps();
        ops.owners.put(profile.resolve("NTUSER.DAT"), NEW_SID);
        ProfileDoctor.ensureAccountProfileFresh("EACodexOnline", NEW_SID, usersRoot, ops);
        assertTrue(Files.isDirectory(variant), "健康时不动变体目录");
    }
}
