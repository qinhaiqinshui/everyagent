package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.WorkspaceManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WslUbuntuBashToolProvider}：appliesTo 只认 wsl-ubuntu 后端；createTools 产出
 * bash 工具；<b>rg 可用性按判据条件化生成描述</b>（架构 §7.10「WSL 侧 rg 另有一套判据」）。
 *
 * <p>判据口径：发行版内 rg 无法经宿主三档解析判定，本插件只认「托管镜像出处」——
 * 目标发行版解析为 {@link WslCommon#MANAGED_DISTRO} 且 rootfs 镜像在位（该镜像由
 * {@code scripts/wsl-rootfs-build.*} 预装 ripgrep）→ 如实宣称「已在 PATH」；
 * 其余情形（用户自配 distro / 无镜像落回 WSL 默认发行版）→ 中性降级表述，
 * 既不宣称可用（命令不存在会被误读成无匹配），也不宣称「rg 不可用」（反向的无法验证断言）。
 *
 * <p>约束(§14.9)：插件对 worker 零依赖——ToolContext/后端桩在本类内自建。
 */
class WslUbuntuBashToolProviderTest {

    @TempDir
    Path pluginDir;

    @TempDir
    Path runtimeDir;

    @TempDir
    Path homeDir;

    @TempDir
    Path workspaceRoot;

    // ---- 夹具 ----

    /**
     * 托管镜像在位与否由 {@code <pluginDir>/wsl/eagent-rootfs.tar.gz} 决定（第一级链）。
     *
     * @param distroConfig 配置 {@code worker.sandbox.wsl.distro}（空 = 未配置）
     * @param withImage    是否放置托管镜像
     */
    private WslUbuntuBashToolProvider provider(String distroConfig, boolean withImage) {
        WorkerConfig props = mock(WorkerConfig.class, RETURNS_DEEP_STUBS);
        when(props.resolveRuntimeDir()).thenReturn(runtimeDir);
        when(props.resolveHomeDir()).thenReturn(homeDir);
        when(props.sandbox().wsl().distro()).thenReturn(distroConfig);
        when(props.sandbox().wsl().tarball()).thenReturn("");
        if (withImage) {
            touch(pluginDir.resolve("wsl"), "eagent-rootfs.tar.gz");
        }
        return new WslUbuntuBashToolProvider(props, null, pluginDir, mock(WorkerServices.class));
    }

    private static void touch(Path dir, String name) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name), "stub");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private String description(WslUbuntuBashToolProvider provider) {
        return provider.createTools(ctx("wsl-ubuntu")).get(0).getToolDefinition().description();
    }

    // ---- appliesTo / pluginId / 工具形态 ----

    @Test
    void appliesOnlyWhenActiveBackendIsWslUbuntu() {
        WslUbuntuBashToolProvider p = provider("", true);
        assertTrue(p.appliesTo(ctx("wsl-ubuntu")), "当前后端 id==wsl-ubuntu → 提供");
        assertFalse(p.appliesTo(ctx("codex")));
        assertFalse(p.appliesTo(ctx("windows-mic")));
        assertFalse(p.appliesTo(ctx(null)), "无后端(DIRECT/沙箱关闭)不提供");
    }

    @Test
    void pluginIdMatchesPluginJson() {
        assertEquals("sandbox-wsl-ubuntu", provider("", true).pluginId());
    }

    @Test
    void createsSingleBashTool() {
        List<ToolCallback> tools = provider("", true).createTools(ctx("wsl-ubuntu"));
        assertEquals(1, tools.size());
        assertEquals("bash", tools.get(0).getToolDefinition().name());
    }

    // ---- 可用分支：托管镜像出处成立 ----

    /** 未配置 distro + 托管镜像在位 → 运行期发行版 = EveryAgent，其 rootfs 预装了 rg。 */
    @Test
    void managedImageReportsRgAvailable() {
        String desc = description(provider("", true));
        assertTrue(desc.contains("已在 PATH"), "托管镜像预装 rg → 描述声明 rg 可用");
        assertFalse(desc.contains("未探测"), "可用分支不得混入中性降级措辞");
        assertFalse(desc.contains("rg 不可用"), "可用分支不得混入「不可用」提示");
    }

    /** 显式配置成托管名（镜像同在位）同样算托管发行版。 */
    @Test
    void explicitManagedDistroNameReportsRgAvailable() {
        assertTrue(description(provider(WslCommon.MANAGED_DISTRO, true)).contains("已在 PATH"));
    }

    // ---- 不可判定分支：不得谎报（§7.10 硬约束）----

    /** 用户把 distro 指向自装发行版：宿主无从判定其内是否装了 rg。 */
    @Test
    void customDistroNeverClaimsRgInPath() {
        String desc = description(provider("Ubuntu-24.04", true));
        assertFalse(desc.contains("已在 PATH"), "非托管发行版不得谎报 rg 已在 PATH");
        assertFalse(desc.contains("rg 不可用"),
                "也不得反向断言不可用(自装发行版里 rg 常在位,那是无法验证的降级)");
        assertTrue(desc.contains("未探测"), "如实说明未探测");
        assertTrue(desc.contains("改用 grep"), "给出可执行的回退命令");
    }

    /** 未配置 distro 且无托管镜像 → 落回 WSL 默认发行版，同样不可判定。 */
    @Test
    void defaultDistroWithoutImageNeverClaimsRgInPath() {
        String desc = description(provider("", false));
        assertFalse(desc.contains("已在 PATH"), "无托管镜像出处不得宣称 rg 可用");
        assertTrue(desc.contains("改用 grep"), "中性分支保留 grep 回退");
    }

    /** 托管名但镜像不在位：出处链断了（可能来自别处导入的同名发行版），不据名字宣称可用。 */
    @Test
    void managedNameWithoutImageIsNotEnough() {
        assertFalse(description(provider(WslCommon.MANAGED_DISTRO, false)).contains("已在 PATH"),
                "无镜像锚点时托管名不足以宣称 rg 在 PATH");
    }

    // ---- 桩 ----

    /** 最小 ToolContext（只提供 sandbox/workspaceRoot/subjectId；§14.9 不引用 worker 类型）。 */
    record FakeToolContext(String taskId, String agentId, Path workspaceRootPath,
            SandboxBackend sandbox) implements ToolContext {

        @Override
        public WorkspaceManager workspaces() {
            return null;
        }

        @Override
        public String subjectId() {
            return taskId;
        }

        @Override
        public String workspaceRoot() {
            return workspaceRootPath != null ? workspaceRootPath.toString() : null;
        }

        @Override
        public String workspaceId() {
            return "defaultworkspace";
        }

        @Override
        public ModelConfig snapshot() {
            return null;
        }

        @Override
        public EventEmitter emitter() {
            return null;
        }

        @Override
        public AgentFactory agentFactory() {
            return null;
        }

        @Override
        public Map<String, Object> metadata() {
            return Map.of();
        }

        @Override
        public Path dataDir() {
            return workspaceRootPath;
        }

        @Override
        public boolean terminal() {
            return false;
        }

        @Override
        public InteractionService interaction() {
            return null;
        }

        @Override
        public Map<String, AgentContext> agents() {
            return Map.of();
        }
    }

    /** 只有 id 的假后端（appliesTo 判定用）。 */
    record FakeBackend(String id) implements SandboxBackend {
    }

    private ToolContext ctx(String sandboxId) {
        return new FakeToolContext("t1", "main", workspaceRoot,
                sandboxId == null ? null : new FakeBackend(sandboxId));
    }
}
