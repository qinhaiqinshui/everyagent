package dev.everyagent.worker.os;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.SandboxBackend.Access;
import dev.everyagent.plugin.api.spi.SandboxBackend.PathGrant;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link SandboxPathRegistry} 薄适配契约测试（架构 §7.8 / §7.10）。
 *
 * <p>锁死三条语义:
 * <ol>
 *   <li>记账幂等 + <b>差量下发</b>:register 只发新路径,sync/unregister/工作区移除以
 *       {@code revoke} 收口;同集合重复登记零下发;</li>
 *   <li>翻译不再由本类实现——{@link SandboxPathRegistry#toSandboxPath} 等直接转发
 *       沙箱纯查询,故后端形态变化(如插件晚注册 / 后端切换)必须整批重放;</li>
 *   <li>下发失败退化不阻断:grant 抛异常时旧快照保留,下次记账自然重试。</li>
 * </ol>
 */
class SandboxPathRegistryTest {

    /**
     * 可切换形态的测试后端:{@code direct} = 恒等翻译 + no-op 授权;
     * {@code wsl-ubuntu} 形态 = 授权根映射为 {@code /sbx<宿主路径>} 前缀视图。
     */
    private static final class FakeBackend implements SandboxBackend {
        private volatile String id = "direct";
        private volatile boolean prefixView = false;
        private volatile boolean throwOnGrant = false;
        final List<List<PathGrant>> grantCalls = new CopyOnWriteArrayList<>();
        final List<List<Path>> revokeCalls = new CopyOnWriteArrayList<>();
        /** 已授权根（宿主侧归一字符串）。 */
        final Set<String> roots = new LinkedHashSet<>();

        void useSandboxViews() {
            this.id = "wsl-ubuntu";
            this.prefixView = true;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void grant(List<PathGrant> grants) {
            if (throwOnGrant) {
                throw new IllegalStateException("grant 失败(测试注入)");
            }
            grantCalls.add(List.copyOf(grants));
            grants.forEach(g -> roots.add(n(g.hostPath())));
        }

        @Override
        public void revoke(List<Path> hostPaths) {
            revokeCalls.add(List.copyOf(hostPaths));
            hostPaths.forEach(p -> roots.remove(n(p)));
        }

        @Override
        public String toSandbox(Path hostPath) {
            if (!prefixView) {
                return hostPath.toString();
            }
            String s = n(hostPath);
            String best = longest(s);
            return best == null ? hostPath.toString() : "/sbx" + best + s.substring(best.length());
        }

        @Override
        public Path toHost(String sandboxPath) {
            if (!prefixView) {
                return Path.of(sandboxPath);
            }
            String bestRoot = null;
            for (String r : roots) {
                String view = "/sbx" + r;
                if (sandboxPath.equals(view) || sandboxPath.startsWith(view + "/")) {
                    if (bestRoot == null || r.length() > bestRoot.length()) {
                        bestRoot = r;
                    }
                }
            }
            if (bestRoot == null) {
                return null;
            }
            String view = "/sbx" + bestRoot;
            return Path.of(bestRoot + sandboxPath.substring(view.length()));
        }

        private String longest(String s) {
            return roots.stream()
                    .filter(r -> s.equals(r) || s.startsWith(r + "/"))
                    .max(Comparator.comparingInt(String::length))
                    .orElse(null);
        }

        private static String n(Path p) {
            return p.toString().replace('\\', '/');
        }
    }

    private static final Path WS = Path.of("/home/dev/workspace");
    private static final Path SKILLS = Path.of("/home/dev/.everyagent/skills");

    @Test
    void registerPushesDeltaOnlyOncePerPath() {
        FakeBackend backend = new FakeBackend();
        backend.useSandboxViews();
        SandboxPathRegistry reg = new SandboxPathRegistry(backend);

        reg.register(SandboxPathRegistry.OWNER_WORKSPACES, WS, Access.READ_WRITE);
        assertEquals(1, backend.grantCalls.size(), "首次登记 → 一次下发");
        assertEquals(List.of(WS), backend.grantCalls.get(0).stream().map(PathGrant::hostPath).toList());

        reg.register(SandboxPathRegistry.OWNER_SKILLS, SKILLS, Access.READ_WRITE);
        assertEquals(2, backend.grantCalls.size(), "新路径 → 再下发一次");
        assertEquals(List.of(SKILLS), backend.grantCalls.get(1).stream().map(PathGrant::hostPath).toList());

        reg.register(SandboxPathRegistry.OWNER_SKILLS, SKILLS, Access.READ_WRITE);
        assertEquals(2, backend.grantCalls.size(), "同路径同语义重复登记必须幂等(零下发)");
        assertTrue(backend.revokeCalls.isEmpty(), "登记不产生回收");
    }

    @Test
    void translationDelegatesToBackendAndHandlesChildPaths() {
        FakeBackend backend = new FakeBackend();
        backend.useSandboxViews();
        SandboxPathRegistry reg = new SandboxPathRegistry(backend);
        reg.register(SandboxPathRegistry.OWNER_WORKSPACES, WS, Access.READ_WRITE);

        Path child = WS.resolve("novels").resolve("三国.md");
        String view = reg.toSandboxPath(child);
        assertEquals("/sbx" + FakeBackend.n(WS) + "/novels/三国.md", view, "子路径按授权根前缀推导");
        assertEquals(child.normalize().toString(), reg.toHostPath(view), "反向翻译还原宿主路径");
        assertEquals(WS.normalize().toString(), reg.toHostPath("/sbx" + FakeBackend.n(WS)),
                "授权根本身也还原");
    }

    @Test
    void backendSwitchReplaysWholeLedger() {
        FakeBackend backend = new FakeBackend(); // id=direct:恒等视图
        SandboxPathRegistry reg = new SandboxPathRegistry(backend);
        reg.register(SandboxPathRegistry.OWNER_WORKSPACES, WS, Access.READ_WRITE);

        assertEquals(WS.toString(), reg.toSandboxPath(WS), "DIRECT 场景原样返回");
        int before = backend.grantCalls.size();

        backend.useSandboxViews(); // 插件晚注册 / 后端切换:后端 id 变了
        assertEquals("/sbx" + FakeBackend.n(WS), reg.toSandboxPath(WS), "后端变化必须整批重放");
        assertEquals(before + 1, backend.grantCalls.size(), "重放只多一次下发");
    }

    @Test
    void syncOverridesOwnerSetAndRevokesDroppedRoots() {
        FakeBackend backend = new FakeBackend();
        backend.useSandboxViews();
        SandboxPathRegistry reg = new SandboxPathRegistry(backend);
        reg.sync(SandboxPathRegistry.OWNER_WORKSPACES,
                List.of(new PathGrant(WS, Access.READ_WRITE),
                        new PathGrant(SKILLS, Access.READ_WRITE)));
        assertTrue(reg.toSandboxPath(SKILLS).startsWith("/sbx"));

        reg.sync(SandboxPathRegistry.OWNER_WORKSPACES, List.of(new PathGrant(WS, Access.READ_WRITE)));
        assertEquals(1, backend.revokeCalls.size(), "被移出集合的根必须回收");
        assertEquals(List.of(SKILLS), backend.revokeCalls.get(0));
        assertEquals(SKILLS.toString(), reg.toSandboxPath(SKILLS), "回收后翻译退化为宿主路径");

        int before = backend.grantCalls.size();
        reg.sync(SandboxPathRegistry.OWNER_WORKSPACES, List.of(new PathGrant(WS, Access.READ_WRITE)));
        assertEquals(before, backend.grantCalls.size(), "同集合 sync 应幂等");
    }

    @Test
    void rootKeptWhileAnyOwnerStillWantsIt() {
        FakeBackend backend = new FakeBackend();
        backend.useSandboxViews();
        SandboxPathRegistry reg = new SandboxPathRegistry(backend);
        reg.register(SandboxPathRegistry.OWNER_WORKSPACES, WS, Access.READ_WRITE);
        reg.register(SandboxPathRegistry.OWNER_SKILLS, WS, Access.READ_WRITE);
        assertEquals(1, backend.grantCalls.size(), "同路径跨 owner 去重,只下发一次");

        reg.unregisterOwner(SandboxPathRegistry.OWNER_SKILLS);
        assertTrue(backend.revokeCalls.isEmpty(), "仍有一个 owner 期望 → 不得回收");
        assertTrue(reg.toSandboxPath(WS).startsWith("/sbx"));

        reg.unregisterOwner(SandboxPathRegistry.OWNER_WORKSPACES);
        assertEquals(1, backend.revokeCalls.size(), "最后一个 owner 撤销后才回收");
        assertEquals(WS.toString(), reg.toSandboxPath(WS));
    }

    @Test
    void workspaceRemovedDropsRootAndRevokes() {
        FakeBackend backend = new FakeBackend();
        backend.useSandboxViews();
        SandboxPathRegistry reg = new SandboxPathRegistry(backend);
        reg.register(SandboxPathRegistry.OWNER_WORKSPACES, WS, Access.READ_WRITE);
        assertTrue(reg.toSandboxPath(WS).startsWith("/sbx"));

        reg.onWorkspaceRemoved(WS);
        assertEquals(1, backend.revokeCalls.size(), "工作区移除 → 路径级回收");
        assertEquals(WS.toString(), reg.toSandboxPath(WS));

        reg.onWorkspaceRemoved(WS);
        assertEquals(1, backend.revokeCalls.size(), "重复移除幂等(未登记则 no-op)");
    }

    @Test
    void unregisteredPathFallsBackToIdentity() {
        FakeBackend backend = new FakeBackend();
        backend.useSandboxViews();
        SandboxPathRegistry reg = new SandboxPathRegistry(backend);
        reg.register(SandboxPathRegistry.OWNER_WORKSPACES, WS, Access.READ_WRITE);

        assertEquals(SKILLS.toString(), reg.toSandboxPath(SKILLS), "未授权根原样返回");
        assertNull(reg.toHostPath("/sbx/home/dev/other/x.md"), "账本外路径返回 null 交 NIO 自然报错");
    }

    @Test
    void grantFailureDegradesWithoutBreakingCallerAndRetriesLater() {
        FakeBackend backend = new FakeBackend();
        backend.useSandboxViews();
        backend.throwOnGrant = true;
        SandboxPathRegistry reg = new SandboxPathRegistry(backend);
        reg.register(SandboxPathRegistry.OWNER_WORKSPACES, WS, Access.READ_WRITE);

        assertEquals(WS.toString(), reg.toSandboxPath(WS), "下发失败退化为原路径,不抛给工具线程");
        assertTrue(backend.revokeCalls.isEmpty());

        backend.throwOnGrant = false;
        reg.register(SandboxPathRegistry.OWNER_SKILLS, SKILLS, Access.READ_WRITE);
        assertTrue(reg.toSandboxPath(WS).startsWith("/sbx"), "下次记账重试并补上先前失败的根");
        assertTrue(reg.toSandboxPath(SKILLS).startsWith("/sbx"));
    }

    @Test
    void readWriteGrantNotDowngradedByReadOnly() {
        FakeBackend backend = new FakeBackend();
        backend.useSandboxViews();
        SandboxPathRegistry reg = new SandboxPathRegistry(backend);
        reg.register(SandboxPathRegistry.OWNER_WORKSPACES, WS, Access.READ_WRITE);
        reg.register(SandboxPathRegistry.OWNER_SKILLS, WS, Access.READ_ONLY);
        assertEquals(1, backend.grantCalls.size(), "读写语义不被只读登记降级/重复");
        assertEquals(Access.READ_WRITE, backend.grantCalls.get(0).get(0).access());

        // 反向:先只读后读写 → 权限提升必须重新下发
        SandboxPathRegistry reg2 = new SandboxPathRegistry(backend);
        reg2.register(SandboxPathRegistry.OWNER_DEFAULT, SKILLS, Access.READ_ONLY);
        reg2.register(SandboxPathRegistry.OWNER_DEFAULT, SKILLS, Access.READ_WRITE);
        List<PathGrant> last = backend.grantCalls.get(backend.grantCalls.size() - 1);
        assertEquals(List.of(SKILLS), last.stream().map(PathGrant::hostPath).toList());
        assertEquals(Access.READ_WRITE, last.get(0).access(), "权限提升必须重发");
    }

    @Test
    void registerAllowsBatchAndNullSafety() {
        FakeBackend backend = new FakeBackend();
        SandboxPathRegistry reg = new SandboxPathRegistry(backend);
        reg.register(List.of());
        reg.register(SandboxPathRegistry.OWNER_DEFAULT,
                new ArrayList<>(java.util.Arrays.asList(
                        new PathGrant(WS, Access.READ_WRITE), null)));
        assertEquals(1, backend.grantCalls.size());
        assertEquals(List.of(WS), backend.grantCalls.get(0).stream().map(PathGrant::hostPath).toList());
    }
}