package dev.everyagent.worker.os;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.SandboxProvider;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.registry.SandboxProviderRegistry;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OsSandbox 后端选择的时序回归测试(架构 §7.10「后端选择时机」)。
 *
 * <p><b>历史 bug</b>:生效后端在 {@code OsSandbox.@PostConstruct} 里一次性 {@code select()} 定论,
 * 而 {@code PluginLoader → WorkerServices → OsSandbox} 的依赖链决定了插件的
 * {@code SandboxProvider} 必然在其后才注册 —— 结果恒定「无可用 SPI 后端」,delegate 永远为 null,
 * 各后端的命令工具 {@code appliesTo}(按 {@code sandbox().id()}) 全不成立,挂载转发也从未发生。
 *
 * <p>本测试刻意复刻「组件先初始化、插件后注册」的真实顺序,锁死惰性解析 + 代次重选语义。
 */
class OsSandboxBackendSelectTest {

    /** 记录 select/create 次数的假后端提供者。 */
    private static final class FakeProvider implements SandboxProvider {
        private final String id;
        private final int priority;
        final AtomicInteger created = new AtomicInteger();

        FakeProvider(String id, int priority) {
            this.id = id;
            this.priority = priority;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public SandboxBackend create(SandboxConfig config) {
            created.incrementAndGet();
            return new FakeBackend(id);
        }
    }

    /** 挂载语义可断言的假后端:沙箱内路径统一加 "/sbx" 前缀。 */
    private static final class FakeBackend implements SandboxBackend {
        private final String id;

        FakeBackend(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Map<Path, String> mount(List<MountRequest> requests) {
            return Map.of(requests.get(0).hostPath(), "/sbx" + requests.get(0).hostPath());
        }
    }

    private static WorkerProperties props(boolean enabled) {
        WorkerProperties p = new WorkerProperties();
        p.getSandbox().setEnabled(enabled);
        return p;
    }

    @Test
    void backendResolvedLazilyAfterPluginsRegister() {
        SandboxProviderRegistry registry = new SandboxProviderRegistry();
        OsSandbox sandbox = new OsSandbox(props(true), registry);
        sandbox.logBackendAtStartup(); // 启动期:注册表还是空的(真实时序)

        assertEquals("direct", sandbox.id(), "尚无 provider 注册时应为 DIRECT");

        FakeProvider provider = new FakeProvider("wsl-ubuntu", 10);
        registry.register(provider); // 插件在 PluginLoader 的 @PostConstruct 里注册(晚于上面)

        assertEquals("wsl-ubuntu", sandbox.id(), "插件晚注册也必须被采纳(按代次重解析)");
        assertEquals(1, provider.created.get(), "胜出者才被 create()");
    }

    @Test
    void selectionCachedPerGenerationAndReSelectedOnUnregister() {
        SandboxProviderRegistry registry = new SandboxProviderRegistry();
        OsSandbox sandbox = new OsSandbox(props(true), registry);
        FakeProvider low = new FakeProvider("windows-mic", 5);
        FakeProvider high = new FakeProvider("wsl-ubuntu", 10);
        registry.register(low);
        registry.register(high);

        assertEquals("wsl-ubuntu", sandbox.id(), "auto 取 priority 最高者");
        assertSame(sandbox.backend(), sandbox.backend(), "同代次复用缓存,不重复 select/create");
        assertEquals(1, high.created.get(), "代次未变不应再 create()");

        registry.unregister(high);
        assertEquals("windows-mic", sandbox.id(), "注销后按新代次重选");
    }

    @Test
    void mountForwardedToSpiBackend() {
        SandboxProviderRegistry registry = new SandboxProviderRegistry();
        OsSandbox sandbox = new OsSandbox(props(true), registry);
        registry.register(new FakeProvider("wsl-ubuntu", 10));

        Path host = Path.of("/c/Users/dev/workspace");
        SandboxBackend.MountRequest req =
                new SandboxBackend.MountRequest(host, SandboxBackend.Access.READ_WRITE);
        Map<Path, String> mounted = sandbox.mount(List.of(req));

        assertEquals("/sbx" + host, mounted.get(host), "mount 必须转发给 SPI 后端,不能吞成原路径");

        // 无 SPI 后端时退化为 SPI 默认实现(原路径直通)
        OsSandbox direct = new OsSandbox(props(true), new SandboxProviderRegistry());
        assertEquals(host.toString(), direct.mount(List.of(req)).get(host), "DIRECT 下 mount 原样直通");
    }

    @Test
    void disabledSandboxNeverSelectsBackend() {
        SandboxProviderRegistry registry = new SandboxProviderRegistry();
        FakeProvider provider = new FakeProvider("windows-mic", 5);
        registry.register(provider);
        OsSandbox sandbox = new OsSandbox(props(false), registry);
        sandbox.logBackendAtStartup();

        assertNull(sandbox.backend(), "沙箱未启用时恒无 SPI 后端");
        assertEquals("direct", sandbox.id());
        assertEquals(0, provider.created.get(), "未启用不应 create() 任何后端");
    }
}
