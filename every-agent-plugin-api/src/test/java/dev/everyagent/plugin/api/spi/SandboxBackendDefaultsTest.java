package dev.everyagent.plugin.api.spi;

import dev.everyagent.plugin.api.spi.SandboxBackend.Access;
import dev.everyagent.plugin.api.spi.SandboxBackend.PathGrant;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SandboxBackend} 默认实现契约单测（§7.8 / §7.10）。
 *
 * <p>默认实现必须对「不关心权限与翻译」的后端（DIRECT）完全无副作用：
 * grant/revoke 为 no-op，toSandbox/toHost 恒等——上层因此可以无条件调用，
 * 不必判断后端类型。
 */
class SandboxBackendDefaultsTest {

    /** 只实现 id() 的最小后端 = DIRECT 形态。 */
    private final SandboxBackend direct = () -> "direct";

    @Test
    void grantAndRevokeAreNoOpByDefault() {
        assertDoesNotThrow(() -> direct.grant(List.of(
                new PathGrant(Path.of("C:/tmp/a.txt"), Access.READ_WRITE))));
        assertDoesNotThrow(() -> direct.revoke(List.of(Path.of("C:/tmp/a.txt"))));
        // 空集合同样安全（重放时集合可能为空）
        assertDoesNotThrow(() -> direct.grant(List.of()));
        assertDoesNotThrow(() -> direct.revoke(List.of()));
    }

    @Test
    void translateIsIdentityByDefault() {
        Path host = Path.of("C:/Users/x/out.txt");
        assertEquals(host.toString(), direct.toSandbox(host), "默认恒等：无需翻译的后端零成本");
        assertEquals(host, direct.toHost(host.toString()), "默认恒等：反向亦然");
    }

    @Test
    void roundTripIsStableForIdentityBackend() {
        Path host = Path.of("C:/Users/x/out.txt");
        assertEquals(host, direct.toHost(direct.toSandbox(host)), "恒等后端往返必须幂等");
    }
}