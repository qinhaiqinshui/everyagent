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

    @Test
    void denyWriteAceRoundTripAndIdempotence() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("roundtrip"));
        Pointer everyone = psid(EVERYONE);

        assertTrue(DenyAcePrimitives.addDenyWriteAce(dir, everyone), "首轮应写入");
        try (AclPrimitives.FetchedDacl fetched = AclPrimitives.fetchDacl(dir)) {
            assertTrue(fetched.view.hasDenyMaskForSid(everyone, AclMasks.DENY_WRITE_MASK));
            assertFalse(fetched.view.hasDenyMaskForSid(everyone, AclMasks.DENY_READ_MASK),
                    "deny-write 不含读位");
        }
        assertFalse(DenyAcePrimitives.addDenyWriteAce(dir, everyone), "幂等：不重复写");

        DenyAcePrimitives.revokeAce(dir, everyone);
        try (AclPrimitives.FetchedDacl fetched = AclPrimitives.fetchDacl(dir)) {
            assertFalse(fetched.view.hasDenyMaskForSid(everyone, AclMasks.DENY_WRITE_MASK));
        }
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
