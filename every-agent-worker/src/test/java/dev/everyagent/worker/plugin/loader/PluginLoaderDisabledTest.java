package dev.everyagent.worker.plugin.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.registry.AdvisorProviderRegistry;
import dev.everyagent.worker.plugin.registry.AuthorizationHandlerRegistry;
import dev.everyagent.worker.plugin.registry.ChatModelEnhancerRegistry;
import dev.everyagent.worker.plugin.registry.FileReferenceHandlerRegistry;
import dev.everyagent.worker.plugin.registry.PluginStateStore;
import dev.everyagent.worker.plugin.registry.SandboxProviderRegistry;
import dev.everyagent.worker.plugin.registry.SearchProviderRegistry;
import dev.everyagent.worker.plugin.registry.SkillContributorRegistry;
import dev.everyagent.worker.plugin.registry.TaskAdmissionPolicyRegistry;
import dev.everyagent.worker.plugin.registry.TaskLifecycleRegistry;
import dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry;
import dev.everyagent.worker.plugin.registry.ToolProviderRegistry;
import dev.everyagent.worker.plugin.scanner.PluginScanner;
import dev.everyagent.worker.plugin.scanner.PluginScanner.ScannedPlugin;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.slash.SlashCommandRegistry;
import dev.everyagent.worker.slash.SlashTokenHandler;

/**
 * 禁用的插件核心不调 activate(回归:禁用无人值守后 {@code /} 仍有「无人值守」候选)。
 *
 * <p>插件的所有贡献(slash 候选、工具、advisor、拦截器…)都在入口类的 {@code activate(ctx)} 里
 * 注册,所以「禁用」的正确落地位置是加载器:命中禁用名单即跳过激活,插件压根没机会注册任何东西,
 * 各注册表不需要知道禁用概念。
 *
 * <p>本测试用「声明了入口类但没有 jar」的插件目录做探针:两种状态的分支互斥,
 * 断言 status 即可证明禁用检查在激活尝试之前短路;同时断言插件仍被登记(扩展管理面板
 * 要能看见它才谈得上再启用)。
 */
class PluginLoaderDisabledTest {

    /** 探针插件 id。 */
    private static final String PROBE_ID = "probe-plugin";
    /** 探针入口类(不实现,只为让加载器认定它是 Java 插件而非声明式插件)。 */
    private static final String PROBE_ENTRY = "dev.everyagent.worker.plugin.loader.ProbeEntry";

    /** 造一个 external 插件目录:只有 plugin.json(声明入口类),不建 lib/jar。 */
    private static Path writePluginDir(Path root) throws IOException {
        Path pluginDir = root.resolve(PROBE_ID);
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("plugin.json"), """
                {
                  "id": "%s",
                  "name": "探针插件",
                  "version": "0.1.0",
                  "main": "%s"
                }
                """.formatted(PROBE_ID, PROBE_ENTRY));
        return pluginDir;
    }

    /** 用给定的插件目录装配一个只跑了扫描+加载分派的 PluginLoader。 */
    private static PluginLoader newLoader(WorkerProperties props, PluginStateStore states,
            Path pluginDir) {
        PluginScanner scanner = () -> List.of(new ScannedPlugin(pluginDir, "external"));
        return new PluginLoader(props, List.of(scanner),
                mock(AdvisorProviderRegistry.class),
                mock(ToolProviderRegistry.class),
                mock(SandboxProviderRegistry.class),
                mock(SearchProviderRegistry.class),
                mock(AuthorizationHandlerRegistry.class),
                mock(ToolExecutionInterceptorRegistry.class),
                mock(TaskLifecycleRegistry.class),
                mock(ChatModelEnhancerRegistry.class),
                mock(TaskAdmissionPolicyRegistry.class),
                mock(SkillContributorRegistry.class),
                mock(FileReferenceHandlerRegistry.class),
                states,
                mock(RpcDispatcher.class),
                mock(SlashCommandRegistry.class),
                mock(SlashTokenHandler.class),
                mock(dev.everyagent.plugin.api.WorkerServices.class),
                mock(org.springframework.context.ApplicationContext.class));
    }

    private static WorkerProperties props(Path pluginsDir) {
        WorkerProperties props = mock(WorkerProperties.class);
        when(props.resolvePluginsDir()).thenReturn(pluginsDir);
        return props;
    }

    @Test
    void disabledPluginIsNotActivated(@TempDir Path tmp) throws IOException {
        Path pluginsDir = tmp.resolve("plugins");
        WorkerProperties props = props(pluginsDir);
        PluginStateStore states = new PluginStateStore(props);
        states.disable(PROBE_ID);

        PluginLoader loader = newLoader(props, states, writePluginDir(tmp));
        loader.scanAndLoad();

        List<PluginLoader.LoadedPlugin> loaded = loader.getLoadedPlugins();
        assertEquals(1, loaded.size(), "禁用插件仍要登记,面板才看得见、才能再启用");
        PluginLoader.LoadedPlugin plugin = loaded.get(0);
        assertFalse(plugin.active(), "禁用插件不得激活");
        assertTrue(plugin.status().contains("已禁用"),
                "禁用插件的 status 应说明被禁用,实际: " + plugin.status());
    }

    @Test
    void enabledPluginGoesThroughActivationPath(@TempDir Path tmp) throws IOException {
        Path pluginsDir = tmp.resolve("plugins");
        WorkerProperties props = props(pluginsDir);
        PluginStateStore states = new PluginStateStore(props);

        PluginLoader loader = newLoader(props, states, writePluginDir(tmp));
        loader.scanAndLoad();

        List<PluginLoader.LoadedPlugin> loaded = loader.getLoadedPlugins();
        assertEquals(1, loaded.size());
        // 未禁用时不会被禁用分支短路,继续走 jar/lib 解析(本探针不建 lib,故停在"lib 目录不可读")。
        assertFalse(loaded.get(0).status().contains("已禁用"),
                "未禁用插件不应被判为禁用,实际: " + loaded.get(0).status());
        assertTrue(loaded.get(0).status().contains("lib"),
                "应走到 jar 解析分支,实际: " + loaded.get(0).status());
    }

    @Test
    void disabledListSurvivesRestart(@TempDir Path tmp) {
        // 禁用名单落盘 → 新实例(模拟 worker 重启)读回,加载器据此继续跳过激活。
        WorkerProperties props = props(tmp.resolve("plugins"));
        new PluginStateStore(props).disable(PROBE_ID);

        assertTrue(new PluginStateStore(props).isDisabled(PROBE_ID), "禁用名单必须跨重启生效");

        new PluginStateStore(props).enable(PROBE_ID);
        assertFalse(new PluginStateStore(props).isDisabled(PROBE_ID), "启用后不得再被禁用名单命中");
    }
}
