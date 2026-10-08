package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinNT;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AclPrimitives/DenyAcePrimitives 真实 DACL 操作单测——<b>Windows 专属</b>
 * （assumeTrue 守卫，非 Windows 平台整体跳过；设计文档 §8 测试策略）。
 * 在 @TempDir 上加/删 ACE 后读回校验（对齐 acl.rs acl_tests 的行为面）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AclPrimitivesTest {

    /** Everyone SID——well-known，测试不需解析本地账户。 */
    private static final String EVERYONE = "S-1-1-0";
    private static final String USERS = "S-1-5-32-545";

    @BeforeAll
    void windowsOnly() {
        Assumptions.assumeTrue(com.sun.jna.Platform.isWindows(),
                "真实 DACL 操作仅 Windows 执行");
    }

    @TempDir
    Path tmp;

    private Pointer psid(String sid) {
        return WindowsAclOperations.INSTANCE.psid(sid);
    }

    /**
     * deny-ACE 测试的 deny 目标——合成 SID，与测试进程身份严格不相交（design.md §8
     * 「deny 对象纪律」）。历史上这里用 Everyone：DENY_WRITE_MASK 经 FILE_GENERIC_WRITE
     * 含 READ_CONTROL，deny Everyone 连同属主隐式自救一起封死——后续 fetchDacl/revokeAce/
     * JUnit @TempDir 清理全数 Access Denied，每跑一次漏一个仅管理员可清的砖目录
     * （.everyagent/tmp/junit-*，历史残留见 scripts/clean-bricked-tmp.ps1）。
     */
    private static final String DENY_TARGET = "S-1-5-21-9-9-9-9";

    @Test
    void denyWriteAceRoundTripAndIdempotence() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("roundtrip"));
        Pointer target = psid(DENY_TARGET);
        try {
            assertTrue(DenyAcePrimitives.addDenyWriteAce(dir, target), "首轮应写入");
            try (AclPrimitives.FetchedDacl fetched = AclPrimitives.fetchDacl(dir)) {
                assertTrue(fetched.view.hasDenyMaskForSid(target, AclMasks.DENY_WRITE_MASK));
                // 「不含读位」只能断 FILE_READ_DATA(0x1):hasDenyMaskForSid 是相交判定,而
                // READ_CONTROL 同在于 FILE_GENERIC_READ/WRITE 两个掩码——拿整个 DENY_READ_MASK
                // 比对恒有交集,旧断言在 deny 写入后必然失败(对齐 acl.rs 的分位断言语义)。
                assertFalse(fetched.view.hasDenyMaskForSid(target, 0x0001),
                        "deny-write 不封文件数据读位");
            }
            assertFalse(DenyAcePrimitives.addDenyWriteAce(dir, target), "幂等：不重复写");
        } finally {
            DenyAcePrimitives.revokeAce(dir, target);
        }
        try (AclPrimitives.FetchedDacl fetched = AclPrimitives.fetchDacl(dir)) {
            assertFalse(fetched.view.hasDenyMaskForSid(target, AclMasks.DENY_WRITE_MASK));
        }
        // 砖自由回归护栏：deny 撤净后目录必须可删。若有人把 deny 对象改回 Everyone 等
        // 泛主体，此行以 AccessDeniedException 立即失败，而不是每次运行漏一个砖目录。
        assertDoesNotThrow(() -> Files.delete(dir), "deny 撤销后目录必须可删(防砖回归)");
    }

    @Test
    void denyReadAceRejectsFilesystemRoot() throws IOException {
        Path root = Path.of("C:\\");
        Assumptions.assumeTrue(Files.exists(root), "C:\\ 不存在的环境跳过");
        IOException error = assertThrows(IOException.class,
                () -> DenyAcePrimitives.addDenyReadAce(root, psid(EVERYONE)));
        assertTrue(error.getMessage().contains("refusing to apply a deny-read ACE"), 
                error.getMessage());
    }

    @Test
    void ensureAllowWriteAcesGrantsWithoutDeleteChild() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("grant"));
        Pointer users = psid(USERS);

        assertTrue(AclPrimitives.ensureAllowWriteAces(dir, List.of(users)));
        assertFalse(AclPrimitives.pathWriteAcesNeedRefresh(dir, List.of(users)),
                "刷新判定应收敛");
        try (AclPrimitives.FetchedDacl fetched = AclPrimitives.fetchDacl(dir)) {
            assertTrue(fetched.view.maskAllows(List.of(users), AclMasks.WRITE_ALLOW_MASK, true,
                    AclDaclView.Scope.EFFECTIVE));
            assertFalse(fetched.view.maskAllows(List.of(users), AclMasks.FILE_DELETE_CHILD, true,
                    AclDaclView.Scope.EXPLICIT), "父目录不得授 FILE_DELETE_CHILD");
        }
        assertFalse(AclPrimitives.ensureAllowWriteAces(dir, List.of(users)), "幂等");

        DenyAcePrimitives.revokeAce(dir, users);
    }

    @Test
    void readExecuteGrantAndBuiltinSkipCheck() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("read"));
        Pointer everyone = psid(EVERYONE);
        try {
            assertTrue(AclPrimitives.ensureReadExecuteAces(dir, List.of(psid(USERS))));
            // 授予后：Users 已持完整 RX（内建主体自检语义）
            assertTrue(WindowsAclOperations.INSTANCE.pathMaskAllows(dir, List.of(USERS),
                    AclMasks.READ_EXECUTE_MASK, true));
        } finally {
            DenyAcePrimitives.revokeAce(dir, psid(USERS));
        }
        assertFalse(WindowsAclOperations.INSTANCE.pathMaskAllows(
                dir, List.of("S-1-5-21-9-9-9-9"), AclMasks.READ_EXECUTE_MASK, true),
                "未授权主体不得命中");
    }

    @Test
    void nullDeviceGrantIsBestEffort() {
        assertDoesNotThrow(() -> DenyAcePrimitives.allowNullDevice(psid(EVERYONE)));
    }
}
