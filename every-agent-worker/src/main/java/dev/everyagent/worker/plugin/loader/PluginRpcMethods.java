package dev.everyagent.worker.plugin.loader;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.plugin.registry.AdvisorProviderRegistry;
import dev.everyagent.worker.plugin.registry.PluginStateStore;
import dev.everyagent.worker.plugin.registry.SandboxProviderRegistry;
import dev.everyagent.worker.plugin.registry.ToolProviderRegistry;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.RpcDispatcher;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * plugin.* RPC 方法：插件管理。
 *
 * <p>内置插件随主包打包（Spring @Component），不能卸载但可以禁用（经 PluginStateStore 运行时生效）。
 * 外部插件在 ~/.everyagent/plugins/ 目录，可以安装/卸载/启用/禁用。
 */
@Component
public class PluginRpcMethods {

    private static final Logger log = LoggerFactory.getLogger(PluginRpcMethods.class);

    private final RpcDispatcher dispatcher;
    private final PluginLoader pluginLoader;
    private final BuiltInPlugins builtInPlugins;
    private final PluginStateStore stateStore;
    private final ToolProviderRegistry toolRegistry;
    private final AdvisorProviderRegistry advisorRegistry;
    private final SandboxProviderRegistry sandboxRegistry;

    public PluginRpcMethods(RpcDispatcher dispatcher, PluginLoader pluginLoader,
            BuiltInPlugins builtInPlugins, PluginStateStore stateStore,
            ToolProviderRegistry toolRegistry, AdvisorProviderRegistry advisorRegistry,
            SandboxProviderRegistry sandboxRegistry) {
        this.dispatcher = dispatcher;
        this.pluginLoader = pluginLoader;
        this.builtInPlugins = builtInPlugins;
        this.stateStore = stateStore;
        this.toolRegistry = toolRegistry;
        this.advisorRegistry = advisorRegistry;
        this.sandboxRegistry = sandboxRegistry;
    }

    @PostConstruct
    void init() {
        dispatcher.register(RpcMethods.PLUGIN_LIST, this::list);
        dispatcher.register(RpcMethods.PLUGIN_INSTALL, this::install);
        dispatcher.register(RpcMethods.PLUGIN_UNINSTALL, this::uninstall);
        dispatcher.register(RpcMethods.PLUGIN_ENABLE, this::enable);
        dispatcher.register(RpcMethods.PLUGIN_DISABLE, this::disable);
    }

    /** plugin.list — 列出全部插件（内置 + 外部，含禁用状态）。 */
    private void list(dev.everyagent.worker.rpc.RpcContext ctx) {
        ArrayNode arr = Json.arr();

        // 内置 ToolProvider
        for (var p : toolRegistry.getProviders()) {
            ObjectNode o = Json.obj();
            o.put("id", p.pluginId());
            o.put("name", p.pluginId());
            o.put("type", "tool");
            o.put("scope", p.scope().name());
            o.put("source", "builtin");
            o.put("active", !stateStore.isDisabled(p.pluginId()));
            arr.add(o);
        }
        // 内置 AdvisorProvider
        for (var p : advisorRegistry.getProviders()) {
            ObjectNode o = Json.obj();
            o.put("id", p.pluginId());
            o.put("name", p.pluginId());
            o.put("type", "advisor");
            o.put("scope", p.scope().name());
            o.put("order", p.order());
            o.put("source", "builtin");
            o.put("active", !stateStore.isDisabled(p.pluginId()));
            arr.add(o);
        }
        // 内置 SandboxProvider
        for (var p : sandboxRegistry.getProviders()) {
            ObjectNode o = Json.obj();
            o.put("id", p.id());
            o.put("name", p.id());
            o.put("type", "sandbox");
            o.put("available", p.isAvailable());
            o.put("source", "builtin");
            o.put("active", !stateStore.isDisabled(p.id()));
            arr.add(o);
        }
        // 外部插件
        for (PluginLoader.LoadedPlugin p : pluginLoader.getLoadedPlugins()) {
            ObjectNode o = Json.obj();
            o.put("id", p.id());
            o.put("name", p.name());
            o.put("version", p.version());
            o.put("description", p.description());
            o.put("author", p.author());
            o.put("source", "external");
            o.put("active", p.active());
            o.put("status", p.status());
            o.put("path", p.pluginDir().toString());
            arr.add(o);
        }

        ObjectNode result = Json.obj();
        result.set("plugins", arr);
        ctx.ok(result);
    }

    /** plugin.install — 从 .eap（zip）文件解压安装到 plugins 目录。 */
    private void install(dev.everyagent.worker.rpc.RpcContext ctx) {
        String zipPath = ctx.params().path("path").asString("");
        if (zipPath.isEmpty()) {
            ctx.err("BAD_PARAMS", "缺少参数 path");
            return;
        }
        Path zip = Path.of(zipPath);
        if (!Files.isRegularFile(zip)) {
            ctx.err("NOT_FOUND", "插件文件不存在: " + zipPath);
            return;
        }
        Path pluginsRoot = builtInPlugins.getPluginsRoot();
        String pluginId;
        try {
            pluginId = extractEap(zip, pluginsRoot);
        } catch (IOException e) {
            ctx.err("INTERNAL", "安装失败: " + e.getMessage());
            return;
        }
        ObjectNode result = Json.obj();
        result.put("installed", true);
        result.put("pluginId", pluginId);
        result.put("message", "插件已安装，重启 worker 后生效");
        ctx.ok(result);
    }

    /** plugin.uninstall — 从 plugins 目录删除外部插件。 */
    private void uninstall(dev.everyagent.worker.rpc.RpcContext ctx) {
        String pluginId = ctx.params().path("pluginId").asString("");
        if (pluginId.isEmpty()) {
            ctx.err("BAD_PARAMS", "缺少参数 pluginId");
            return;
        }
        Path pluginsRoot = builtInPlugins.getPluginsRoot();
        Path pluginDir = pluginsRoot.resolve(pluginId).normalize();
        if (!pluginDir.startsWith(pluginsRoot) || !Files.isDirectory(pluginDir)) {
            ctx.err("NOT_FOUND", "插件目录不存在: " + pluginId);
            return;
        }
        try {
            deleteRecursive(pluginDir);
        } catch (IOException e) {
            ctx.err("INTERNAL", "卸载失败: " + e.getMessage());
            return;
        }
        ObjectNode result = Json.obj();
        result.put("uninstalled", true);
        result.put("pluginId", pluginId);
        result.put("message", "插件已卸载，重启 worker 后生效");
        ctx.ok(result);
    }

    /** plugin.enable — 启用插件（运行时生效，经 PluginStateStore）。 */
    private void enable(dev.everyagent.worker.rpc.RpcContext ctx) {
        String pluginId = ctx.params().path("pluginId").asString("");
        if (pluginId.isEmpty()) {
            ctx.err("BAD_PARAMS", "缺少参数 pluginId");
            return;
        }
        stateStore.enable(pluginId);
        // 外部插件也移除 .disabled 标记
        Path pluginsRoot = builtInPlugins.getPluginsRoot();
        Path disabledMarker = pluginsRoot.resolve(pluginId).resolve(".disabled").normalize();
        if (disabledMarker.startsWith(pluginsRoot)) {
            try { Files.deleteIfExists(disabledMarker); } catch (IOException e) { /* 忽略 */ }
        }
        ObjectNode result = Json.obj();
        result.put("enabled", true);
        result.put("pluginId", pluginId);
        result.put("message", "插件已启用");
        ctx.ok(result);
    }

    /** plugin.disable — 禁用插件（运行时生效，经 PluginStateStore）。 */
    private void disable(dev.everyagent.worker.rpc.RpcContext ctx) {
        String pluginId = ctx.params().path("pluginId").asString("");
        if (pluginId.isEmpty()) {
            ctx.err("BAD_PARAMS", "缺少参数 pluginId");
            return;
        }
        stateStore.disable(pluginId);
        ObjectNode result = Json.obj();
        result.put("disabled", true);
        result.put("pluginId", pluginId);
        result.put("message", "插件已禁用");
        ctx.ok(result);
    }

    private String extractEap(Path zip, Path pluginsRoot) throws IOException {
        String pluginId = null;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("__MACOSX") || name.contains("/.DS_Store")) {
                    continue;
                }
                if (pluginId == null && name.contains("/")) {
                    pluginId = name.substring(0, name.indexOf('/'));
                } else if (pluginId == null) {
                    pluginId = zip.getFileName().toString().replaceAll("\\.eap$", "");
                }
                Path target = pluginsRoot.resolve(name).normalize();
                if (!target.startsWith(pluginsRoot)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return pluginId != null ? pluginId : "unknown";
    }

    private void deleteRecursive(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                for (Path child : stream.toList()) {
                    deleteRecursive(child);
                }
            }
        }
        Files.deleteIfExists(dir);
    }
}
