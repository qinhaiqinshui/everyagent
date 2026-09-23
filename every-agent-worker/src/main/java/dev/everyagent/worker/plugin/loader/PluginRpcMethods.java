package dev.everyagent.worker.plugin.loader;

import dev.everyagent.contract.json.Json;
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
 * <p>方法列表：
 * <ul>
 *   <li>{@code plugin.list} — 列出已加载的插件清单</li>
 *   <li>{@code plugin.install} — 从 .eap 文件解压安装到 plugins 目录</li>
 *   <li>{@code plugin.uninstall} — 从 plugins 目录删除插件（内置插件不可卸载）</li>
 *   <li>{@code plugin.enable} — 启用插件（移除 .disabled 标记）</li>
 *   <li>{@code plugin.disable} — 禁用插件（添加 .disabled 标记）</li>
 * </ul>
 *
 * <p>注意：install/uninstall/enable/disable 只修改磁盘文件，不热重载——
 * 需要重启 worker 或调用 skill.reload 后才生效（与 skill 热加载策略一致）。
 */
@Component
public class PluginRpcMethods {

    private static final Logger log = LoggerFactory.getLogger(PluginRpcMethods.class);

    private final RpcDispatcher dispatcher;
    private final PluginLoader pluginLoader;
    private final BuiltInPlugins builtInPlugins;

    public PluginRpcMethods(RpcDispatcher dispatcher, PluginLoader pluginLoader,
            BuiltInPlugins builtInPlugins) {
        this.dispatcher = dispatcher;
        this.pluginLoader = pluginLoader;
        this.builtInPlugins = builtInPlugins;
    }

    @PostConstruct
    void init() {
        dispatcher.register(RpcMethods.PLUGIN_LIST, this::list);
        dispatcher.register(RpcMethods.PLUGIN_INSTALL, this::install);
        dispatcher.register(RpcMethods.PLUGIN_UNINSTALL, this::uninstall);
        dispatcher.register(RpcMethods.PLUGIN_ENABLE, this::enable);
        dispatcher.register(RpcMethods.PLUGIN_DISABLE, this::disable);
    }

    /** plugin.list — 列出已加载的插件清单。 */
    private void list(dev.everyagent.worker.rpc.RpcContext ctx) {
        ArrayNode arr = Json.arr();
        for (PluginLoader.LoadedPlugin p : pluginLoader.getLoadedPlugins()) {
            ObjectNode o = Json.obj();
            o.put("id", p.id());
            o.put("name", p.name());
            o.put("version", p.version());
            o.put("description", p.description());
            o.put("author", p.author());
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

    /** plugin.uninstall — 从 plugins 目录删除插件。 */
    private void uninstall(dev.everyagent.worker.rpc.RpcContext ctx) {
        String pluginId = ctx.params().path("pluginId").asString("");
        if (pluginId.isEmpty()) {
            ctx.err("BAD_PARAMS", "缺少参数 pluginId");
            return;
        }

        if (builtInPlugins.builtInIds().contains(pluginId)) {
            ctx.err("BAD_PARAMS", "内置插件不可卸载: " + pluginId);
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

    /** plugin.enable — 移除 .disabled 标记。 */
    private void enable(dev.everyagent.worker.rpc.RpcContext ctx) {
        String pluginId = ctx.params().path("pluginId").asString("");
        if (pluginId.isEmpty()) {
            ctx.err("BAD_PARAMS", "缺少参数 pluginId");
            return;
        }
        Path pluginsRoot = builtInPlugins.getPluginsRoot();
        Path disabledMarker = pluginsRoot.resolve(pluginId).resolve(".disabled").normalize();
        if (!disabledMarker.startsWith(pluginsRoot)) {
            ctx.err("BAD_PARAMS", "非法插件 id");
            return;
        }
        try {
            Files.deleteIfExists(disabledMarker);
        } catch (IOException e) {
            ctx.err("INTERNAL", "启用失败: " + e.getMessage());
            return;
        }
        ObjectNode result = Json.obj();
        result.put("enabled", true);
        result.put("pluginId", pluginId);
        result.put("message", "插件已启用，重启 worker 后生效");
        ctx.ok(result);
    }

    /** plugin.disable — 添加 .disabled 标记。 */
    private void disable(dev.everyagent.worker.rpc.RpcContext ctx) {
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
        Path disabledMarker = pluginDir.resolve(".disabled");
        try {
            if (!Files.exists(disabledMarker)) {
                Files.createFile(disabledMarker);
            }
        } catch (IOException e) {
            ctx.err("INTERNAL", "禁用失败: " + e.getMessage());
            return;
        }
        ObjectNode result = Json.obj();
        result.put("disabled", true);
        result.put("pluginId", pluginId);
        result.put("message", "插件已禁用，重启 worker 后生效");
        ctx.ok(result);
    }

    // ── 工具方法 ──

    /**
     * 从 .eap（zip）解压到 plugins 目录。
     * zip 根目录下的文件解压到 plugins/<id>/ 下。
     */
    private String extractEap(Path zip, Path pluginsRoot) throws IOException {
        String pluginId = null;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                // 跳过目录条目和 macOS 元数据
                if (entry.isDirectory() || name.startsWith("__MACOSX") || name.contains("/.DS_Store")) {
                    continue;
                }
                // 从路径中提取插件 id（第一级目录名）
                if (pluginId == null && name.contains("/")) {
                    pluginId = name.substring(0, name.indexOf('/'));
                } else if (pluginId == null) {
                    // 无子目录结构的 zip（直接是 plugin.json 等）
                    pluginId = zip.getFileName().toString().replaceAll("\\.eap$", "");
                }

                Path target = pluginsRoot.resolve(name).normalize();
                if (!target.startsWith(pluginsRoot)) {
                    log.warn("[plugins] zip 条目路径越界,跳过: {}", name);
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
