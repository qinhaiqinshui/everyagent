package dev.everyagent.worker.plugin.loader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.registry.PluginRegistry;
import dev.everyagent.worker.plugin.registry.PluginStateStore;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.rpc.RpcDispatcher;

/**
 * plugin.uninstall 的墓碑语义(§8.5,uninstall 墓碑回归):
 *
 * <ul>
 *   <li>成功路径:目录整体删除(墓碑随目录消失,不残留),应答 ok;再卸载同 id 得 NOT_FOUND
 *       ——证明删净且无墓碑残留导致插件复活;</li>
 *   <li>目录不存在:NOT_FOUND(既有行为零回归);</li>
 *   <li>占用失败文案 lockedMessage:附插件目录路径 + 排查指引(重试/重启自愈)。</li>
 * </ul>
 */
class PluginRpcMethodsUninstallTest {

    private static PluginRpcMethods newMethods(Path pluginsRoot) {
        WorkerProperties props = mock(WorkerProperties.class);
        when(props.resolvePluginsDir()).thenReturn(pluginsRoot);
        PluginRegistry registry = new PluginRegistry(props,
                mock(PluginLoader.class), new PluginStateStore(props));
        return new PluginRpcMethods(mock(RpcDispatcher.class), mock(PluginLoader.class), registry);
    }

    private static RpcContext ctxWithPluginId(String pluginId) {
        RpcContext ctx = mock(RpcContext.class);
        when(ctx.params()).thenReturn(Json.obj().put("pluginId", pluginId));
        return ctx;
    }

    @Test
    void uninstallRemovesDirIncludingTombstone(@TempDir Path tmp) throws IOException {
        Path pluginsRoot = tmp.resolve("plugins");
        Files.createDirectories(pluginsRoot);
        Path dir = pluginsRoot.resolve("my-plugin");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("plugin.json"), "{\"id\":\"my-plugin\"}");
        PluginRpcMethods methods = newMethods(pluginsRoot);

        RpcContext first = ctxWithPluginId("my-plugin");
        methods.uninstall(first);

        assertFalse(Files.exists(dir), "插件目录应被整体删除(墓碑随目录消失,不残留)");
        verify(first).ok(any());
        verify(first, never()).err(anyString(), anyString());

        // 目录已删净 → 再卸载同 id 应 NOT_FOUND(墓碑不复活、目录不复活)
        RpcContext second = ctxWithPluginId("my-plugin");
        methods.uninstall(second);
        verify(second).err("NOT_FOUND", "插件目录不存在: my-plugin");
    }

    @Test
    void missingDirAnswersNotFound(@TempDir Path tmp) throws IOException {
        Path pluginsRoot = tmp.resolve("plugins");
        Files.createDirectories(pluginsRoot);
        PluginRpcMethods methods = newMethods(pluginsRoot);

        RpcContext ctx = ctxWithPluginId("ghost");
        methods.uninstall(ctx);

        verify(ctx).err("NOT_FOUND", "插件目录不存在: ghost");
        verify(ctx, never()).ok(any());
    }

    @Test
    void lockedMessageCarriesPathAndGuidance(@TempDir Path tmp) {
        Path dir = tmp.resolve("plugins").resolve("locked");
        String message = PluginRpcMethods.lockedMessage(dir,
                new IOException(dir + ": 另一个程序正在使用此文件，进程无法访问。"));

        assertTrue(message.contains(dir.toString()), "文案应附插件目录路径");
        assertTrue(message.contains("工作目录"), "文案应指出 CWD 占用的常见原因");
        assertTrue(message.contains("重试卸载"), "文案应指引重试");
        assertTrue(message.contains("自动清理"), "文案应说明重启自愈");
    }
}
