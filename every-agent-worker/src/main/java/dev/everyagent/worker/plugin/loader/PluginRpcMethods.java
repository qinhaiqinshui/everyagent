package dev.everyagent.worker.plugin.loader;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.plugin.registry.PluginManifest;
import dev.everyagent.worker.plugin.registry.PluginRegistry;
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
 * <p>内置插件随主包打包（Spring @Component），不能卸载但可以禁用（经 PluginRegistry 运行时生效）。
 * 外部插件在 ~/.everyagent/plugins/ 目录，可以安装/卸载/启用/禁用。
 */
@Component
public class PluginRpcMethods {

    private static final Logger log = LoggerFactory.getLogger(PluginRpcMethods.class);

    private final RpcDispatcher dispatcher;
    private final PluginLoader pluginLoader;
    private final PluginRegistry pluginRegistry;

    public PluginRpcMethods(RpcDispatcher dispatcher, PluginLoader pluginLoader,
            PluginRegistry pluginRegistry) {
        this.dispatcher = dispatcher;
        this.pluginLoader = pluginLoader;
        this.pluginRegistry = pluginRegistry;
    }

    @PostConstruct
    void init() {
        dispatcher.register(RpcMethods.PLUGIN_LIST, this::list);
        dispatcher.register(RpcMethods.PLUGIN_INSTALL, this::install);
        dispatcher.register(RpcMethods.PLUGIN_UNINSTALL, this::uninstall);
        dispatcher.register(RpcMethods.PLUGIN_ENABLE, this::enable);
        dispatcher.register(RpcMethods.PLUGIN_DISABLE, this::disable);
        dispatcher.register(RpcMethods.PLUGIN_WEB_SOURCE, this::webSource);
    }

    /** plugin.list — 列出全部插件（内置 + 外部，含 web-only），附带 disabledIds。 */
    private void list(dev.everyagent.worker.rpc.RpcContext ctx) {
        ArrayNode arr = Json.arr();

        for (PluginManifest m : pluginRegistry.catalog()) {
            ObjectNode o = Json.obj();
            o.put("id", m.id());
            o.put("name", m.name());
            o.put("version", m.version());
            o.put("description", m.description());
            o.put("author", m.author());
            o.put("source", m.source());
            o.put("active", !pluginRegistry.isDisabled(m.id()));
            o.put("hasMain", !m.main().isEmpty());
            o.put("hasWebMain", !m.webMain().isEmpty());
            arr.add(o);
        }

        ObjectNode result = Json.obj();
        result.set("plugins", arr);
        // 附带禁用列表
        ArrayNode disabledArr = Json.arr();
        for (String id : pluginRegistry.disabledIds()) {
            disabledArr.add(id);
        }
        result.set("disabledIds", disabledArr);
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
        Path pluginsRoot = pluginRegistry.getPluginsRoot();
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
        Path pluginsRoot = pluginRegistry.getPluginsRoot();
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

    /** plugin.enable — 启用插件（运行时生效，经 PluginRegistry）。 */
    private void enable(dev.everyagent.worker.rpc.RpcContext ctx) {
        String pluginId = ctx.params().path("pluginId").asString("");
        if (pluginId.isEmpty()) {
            ctx.err("BAD_PARAMS", "缺少参数 pluginId");
            return;
        }
        pluginRegistry.enable(pluginId);
        ObjectNode result = Json.obj();
        result.put("enabled", true);
        result.put("pluginId", pluginId);
        result.put("message", "插件已启用");
        ctx.ok(result);
    }

    /** plugin.disable — 禁用插件（运行时生效，经 PluginRegistry）。 */
    private void disable(dev.everyagent.worker.rpc.RpcContext ctx) {
        String pluginId = ctx.params().path("pluginId").asString("");
        if (pluginId.isEmpty()) {
            ctx.err("BAD_PARAMS", "缺少参数 pluginId");
            return;
        }
        pluginRegistry.disable(pluginId);
        ObjectNode result = Json.obj();
        result.put("disabled", true);
        result.put("pluginId", pluginId);
        result.put("message", "插件已禁用");
        ctx.ok(result);
    }

    /** plugin.webSource — 从插件目录读取文件返回源码文本（支持内置和外部插件）。 */
    private void webSource(dev.everyagent.worker.rpc.RpcContext ctx) {
        String pluginId = ctx.params().path("pluginId").asString("");
        if (pluginId.isEmpty()) {
            ctx.err("BAD_PARAMS", "缺少参数 pluginId");
            return;
        }
        String filePath = ctx.params().path("path").asString("");
        if (filePath.isEmpty()) {
            ctx.err("BAD_PARAMS", "缺少参数 path");
            return;
        }

        // 统一从 PluginManifest 获取插件目录（内置插件源码目录或外部插件安装目录）。
        PluginManifest manifest = pluginRegistry.get(pluginId);
        if (manifest == null || manifest.pluginDir() == null) {
            ctx.err("NOT_FOUND", "插件不存在: " + pluginId);
            return;
        }
        Path pluginDir = manifest.pluginDir().normalize();
        if (!Files.isDirectory(pluginDir)) {
            ctx.err("NOT_FOUND", "插件目录不存在: " + pluginId);
            return;
        }

        // 安全：路径必须在插件目录内
        Path target = pluginDir.resolve(filePath).normalize();
        if (!target.startsWith(pluginDir) || !Files.isRegularFile(target)) {
            ctx.err("NOT_FOUND", "文件不存在或越界: " + filePath);
            return;
        }

        try {
            String content = Files.readString(target);
            ObjectNode result = Json.obj();
            result.put("pluginId", pluginId);
            result.put("path", filePath);
            result.put("content", content);
            ctx.ok(result);
        } catch (IOException e) {
            ctx.err("INTERNAL", "读取文件失败: " + e.getMessage());
        }
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
