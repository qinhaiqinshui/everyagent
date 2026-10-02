package dev.everyagent.worker.os;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.SandboxBackend.Access;
import dev.everyagent.plugin.api.spi.SandboxBackend.MountRequest;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SandboxPathRegistry「意图登记 + 惰性物化」的回归测试(架构 §7.10)。
 *
 * <p>锁死三条语义:
 * <ol>
 *   <li>{@code register/sync/unregister} 只记意图、零 IO——mount 推迟到首次翻译查询,
 *       且整表只按「后端 id + 意图版本」代次物化一次(启动期后端尚未定论也不会定型);</li>
 *   <li>登记根的子路径两侧都能按最长前缀推导({@code toSandboxPath} / {@code toHostPath});</li>
 *   <li>后端切换(如 direct → wsl-ubuntu)或意图变化即整表重建,不残留旧形态映射;
 *       mount 抛异常时退化原路径,不阻断工具线程。</li>
 * </ol>
 */
class SandboxPathRegistryTest {

    /** 可切换委托的测试后端:id/mount 行为可换,用于模拟「启动后插件才注册出真后端」。 */
    private static final class SwitchableBackend implements SandboxBackend {
        private volatile String id = "direct";
        private volatile boolean prefixView = false;
        private volatile boolean throwOnMount = false;
        final AtomicInteger mountCalls = new AtomicInteger();
        final List<List<MountRequest>> mounted = new ArrayList<>();

        void useSandboxViews() {
            this.id = "wsl-ubuntu";
            this.prefixView = true;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Map<Path, String> mount(List<MountRequest> requests) {
            mountCalls.incrementAndGet();
            mounted.add(List.copyOf(requests));
            if (throwOnMount) {
                throw new IllegalStateException("mount 失败(测试注入)");
            }
            Map<Path, String> out = new LinkedHashMap<>();
            for (MountRequest r : requests) {
                String host = r.hostPath().toString().replace('\\', '/');
                out.put(r.hostPath(), prefixView ? "/sbx" + host : r.hostPath().toString());
            }
            return out;
        }
    }

    private static final Path WS = Path.of("/home/dev/workspace");
    private static final Path SKILLS = Path.of("/home/dev/.everyagent/skills");

    @Test
    void registerRecordsIntentOnlyAndMountsLazilyOnce() {
        SwitchableBackend backend = new SwitchableBackend();
        backend.useSandboxViews();
        SandboxPathRegistry reg = new SandboxPathRegistry(backend);

        reg.register(SandboxPathRegistry.OWNER_WORKSPACES, WS, Access.READ_WRITE);
        reg.register(SandboxPathRegistry.OWNER_SKILLS, SKILLS, Access.READ_WRITE);
        assertEquals(0, backend.mountCalls.get(), "登记只记意图,不得触发 mount");

        assertEquals("/sbx" + WS, reg.toSandboxPath(WS), "首次查询触动物化");
        assertEquals(1, backend.mountCalls.get(), "两条意图应合并成一次批量 mount");
        assertEquals(2, backend.mounted.get(0).size());

        reg.toSandboxPath(SKILLS);
        reg.toHostPath("/sbx" + WS + "/a.md");
        assertEquals(1, backend.mountCalls.get(), "代次未变不得重复 mount");
    }

    @Test
    void childPathsTranslateBothWaysByLongestPrefix() {
        SwitchableBackend backend = new SwitchableBackend();
        backend.useSandboxViews();
        SandboxPathRegistry reg = new SandboxPathRegistry(backend);
        reg.register(SandboxPathRegistry.OWNER_WORKSPACES, WS, Access.READ_WRITE);

        Path child = WS.resolve("novels").resolve("三国.md");
        String view = reg.toSandboxPath(child);
        assertEquals("/sbx" + WS + "/novels/三国.md", view, "子路径按登记根前缀推导");
        assertEquals(child.normalize().toString(), reg.toHostPath(view), "反向翻译还原宿主路径");
        assertEquals(WS.normalize().toString(), reg.toHostPath("/sbx" + WS), "视图根本身也还原");
    }

    @Test
    void backendSwitchRebuildsTableWithNewBackend() {
        SwitchableBackend backend = new SwitchableBackend(); // id=direct:mount 恒等
        SandboxPathRegistry reg = new SandboxPathRegistry(backend);
        reg.register(SandboxPathRegistry.OWNER_WORKSPACES, WS, Access.READ_WRITE);

        assertEquals(WS.toString(), reg.toSandboxPath(WS), "DIRECT 场景原样返回");
        assertEquals(1, backend.mountCalls.get());

        backend.useSandboxViews(); // 插件晚注册/后端切换:后端 id 变了
        assertEquals("/sbx" + WS, reg.toSandboxPath(WS), "后端变化必须重新物化");
        assertEquals(2, backend.mountCalls.get(), "重新物化只多一次 mount");
    }

    @Test
    void syncOverridesOwnerSetAndReMaterializes() {
        SwitchableBackend backend = new SwitchableBackend();
        backend.useSandboxViews();
        SandboxPathRegistry reg = new SandboxPathRegistry(backend);
        reg.sync(SandboxPathRegistry.OWNER_WORKSPACES,
                List.of(new MountRequest(WS, Access.READ_WRITE),
                        new MountRequest(SKILLS, Access.READ_WRITE)));
        int afterFirst = backend.mountCalls.get();
        assertTrue(reg.toSandboxPath(SKILLS).startsWith("/sbx"));

        reg.sync(SandboxPathRegistry.OWNER_WORKSPACES, List.of(new MountRequest(WS, Access.READ_WRITE)));
        assertEquals("/sbx" + WS, reg.toSandboxPath(WS));
        assertEquals(afterFirst + 1, backend.mountCalls.get(), "意图集合变化 → 重建(不含被撤销根)");
        assertEquals(1, backend.mounted.get(backend.mounted.size() - 1).size(),
                "撤销的工作区根不得再进 mount 清单");
        assertEquals(SKILLS.toString(), reg.toSandboxPath(SKILLS), "撤销后按未登记退化原样");

        // 幂等:重复同集合 sync 不产生版本变化、不重复 mount
        int before = backend.mountCalls.get();
        reg.sync(SandboxPathRegistry.OWNER_WORKSPACES, List.of(new MountRequest(WS, Access.READ_WRITE)));
        reg.toSandboxPath(WS);
        assertEquals(before, backend.mountCalls.get(), "同集合 sync 应幂等");
    }

    @Test
    void unregisterOwnerAndWorkspaceRemovedDropIntent() {
        SwitchableBackend backend = new SwitchableBackend();
        backend.useSandboxViews();
        SandboxPathRegistry reg = new SandboxPathRegistry(backend);
        reg.register(SandboxPathRegistry.OWNER_SKILLS, SKILLS, Access.READ_WRITE);
        assertTrue(reg.toSandboxPath(SKILLS).startsWith("/sbx"));

        reg.unregisterOwner(SandboxPathRegistry.OWNER_SKILLS);
        assertEquals(SKILLS.toString(), reg.toSandboxPath(SKILLS));

        reg.register(SandboxPathRegistry.OWNER_WORKSPACES, WS, Access.READ_WRITE);
        assertTrue(reg.toSandboxPath(WS).startsWith("/sbx"));
        reg.onWorkspaceRemoved(WS);
        assertEquals(WS.toString(), reg.toSandboxPath(WS), "工作区移除后意图与映射一并撤销");
    }

    @Test
    void unregisteredPathFallsBackToIdentity() {
        SwitchableBackend backend = new SwitchableBackend();
        backend.useSandboxViews();
        SandboxPathRegistry reg = new SandboxPathRegistry(backend);
        reg.register(SandboxPathRegistry.OWNER_WORKSPACES, WS, Access.READ_WRITE);

        assertEquals(SKILLS.toString(), reg.toSandboxPath(SKILLS), "未登记根原样返回");
        assertNull(reg.toHostPath("/sbx/home/dev/other/x.md"), "注册表外路径返回 null 交 NIO 自然报错");
    }

    @Test
    void mountFailureDegradesToRawPathAndRetriesOnIntentChange() {
        SwitchableBackend backend = new SwitchableBackend();
        backend.useSandboxViews();
        backend.throwOnMount = true;
        SandboxPathRegistry reg = new SandboxPathRegistry(backend);
        reg.register(SandboxPathRegistry.OWNER_WORKSPACES, WS, Access.READ_WRITE);

        assertEquals(WS.toString(), reg.toSandboxPath(WS), "mount 失败退化为原路径,不抛给工具线程");

        backend.throwOnMount = false;
        reg.register(SandboxPathRegistry.OWNER_SKILLS, SKILLS, Access.READ_WRITE);
        assertEquals("/sbx" + SKILLS, reg.toSandboxPath(SKILLS), "意图变化后重试物化");
    }

    @Test
    void readWriteIntentNotDowngradedByReadOnly() {
        SwitchableBackend backend = new SwitchableBackend();
        backend.useSandboxViews();
        SandboxPathRegistry reg = new SandboxPathRegistry(backend);
        reg.register(SandboxPathRegistry.OWNER_WORKSPACES, WS, Access.READ_WRITE);
        reg.register(SandboxPathRegistry.OWNER_SKILLS, WS, Access.READ_ONLY);
        assertTrue(reg.toSandboxPath(WS).startsWith("/sbx"), "同根跨 owner 登记去重");
        assertEquals(1, backend.mounted.get(0).size(), "读写语义不被只读登记降级/重复");
    }
}
