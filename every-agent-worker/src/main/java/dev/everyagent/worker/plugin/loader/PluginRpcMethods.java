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
 * <p>内置插件随主包打包，不能卸载但可以禁用。禁用名单持久化在 worker 侧
 * （{@code PluginStateStore}），由 {@code PluginLoader} 在加载时生效:被禁用的插件
 * 核心不调它的 {@code activate}，因此不会注册任何贡献（重启 worker 后完全生效）。
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
        dispatcher.register(RpcMethods.PLUGIN_ASSET, this::asset);
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
            // active 保持旧语义(= 不在禁用名单),兼容既有前端;真实加载结果看 status。
            o.put("active", !pluginRegistry.isDisabled(m.id()));
            // status = 加载期实际状态(LoadedPlugin.status 透传):已激活/激活失败/已禁用(未激活)等,
            // 前端据此区分「在跑」与「加载失败」。
            o.put("status", m.status());
            o.put("hasMain", !m.main().isEmpty());
            o.put("hasWebMain", !m.webMain().isEmpty());
            // webMain 原始值随清单下发:前端据此推导 web 产物路径(后缀换 .js)。
            o.put("webMain", m.webMain());
            // 展示元数据(扩展管理面板 VSCode 风格列表/详情页用):图标路径经 plugin.asset 读取字节,
            // 资源链接与分类纯展示;均不参与任何加载判定。
            o.put("icon", m.icon());
            o.put("repository", m.repository());
            o.put("license", m.license());
            o.put("homepage", m.homepage());
            ArrayNode categories = Json.arr();
            for (String c : m.categories()) {
                categories.add(c);
            }
            o.set("categories", categories);
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
        result.put("message", "插件已启用，重启 worker 后生效");
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
        result.put("message", "插件已禁用，重启 worker 后生效");
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

        Path target = resolvePluginFile(ctx, pluginId, filePath);
        if (target == null) {
            return; // resolvePluginFile 已回错误
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

    /** plugin.asset — 从插件目录读取二进制资源（扩展图标等）返回 mime + base64。 */
    private void asset(dev.everyagent.worker.rpc.RpcContext ctx) {
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

        Path target = resolvePluginFile(ctx, pluginId, filePath);
        if (target == null) {
            return; // resolvePluginFile 已回错误
        }

        String mime = imageMime(target.getFileName().toString());
        if (mime == null) {
            ctx.err("BAD_PARAMS", "不支持的资源类型: " + target.getFileName());
            return;
        }
        try {
            long size = Files.size(target);
            if (size > MAX_ASSET_BYTES) {
                ctx.err("FRAME_TOO_LARGE", "资源过大: " + size + " 字节,超过 "
                        + MAX_ASSET_BYTES + " 字节上限");
                return;
            }
            String base64 = java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(target));
            ObjectNode result = Json.obj();
            result.put("pluginId", pluginId);
            result.put("path", filePath);
            result.put("mime", mime);
            result.put("contentBase64", base64);
            ctx.ok(result);
        } catch (IOException e) {
            ctx.err("INTERNAL", "读取资源失败: " + e.getMessage());
        }
    }

    /** 插件目录内单文件资源的大小上限（图标等展示资源,2 MB）。 */
    private static final long MAX_ASSET_BYTES = 2 * 1024 * 1024;

    /** 按扩展名猜图片 mime；非图片类型返回 null（调用方报 BAD_PARAMS）。 */
    private static String imageMime(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return null;
    }

    /**
     * 解析插件目录内的文件路径（webSource / asset 共用）：
     * 统一从 PluginManifest 获取插件目录，normalize + startsWith 做 jail 校验
     * （路径不许逃逸插件目录）；精确路径未命中时按同目录大小写不敏感回退匹配一次
     * （Linux 上 {@code readme.md} 也能读到 {@code README.md}，供扩展详情页取 README）。
     *
     * @return 解析后的目标文件；失败时已向 ctx 回错误并返回 null
     */
    private Path resolvePluginFile(dev.everyagent.worker.rpc.RpcContext ctx, String pluginId, String filePath) {
        // 统一从 PluginManifest 获取插件目录（内置插件源码目录或外部插件安装目录）。
        PluginManifest manifest = pluginRegistry.get(pluginId);
        if (manifest == null || manifest.pluginDir() == null) {
            ctx.err("NOT_FOUND", "插件不存在: " + pluginId);
            return null;
        }
        Path pluginDir = manifest.pluginDir().normalize();
        if (!Files.isDirectory(pluginDir)) {
            ctx.err("NOT_FOUND", "插件目录不存在: " + pluginId);
            return null;
        }

        // 安全：路径必须在插件目录内
        Path target = pluginDir.resolve(filePath).normalize();
        if (!target.startsWith(pluginDir) || !Files.isRegularFile(target)) {
            // 大小写不敏感回退：同目录下找同名（忽略大小写）的常规文件
            Path fallback = findCaseInsensitiveSibling(target);
            if (fallback == null) {
                ctx.err("NOT_FOUND", "文件不存在或越界: " + filePath);
                return null;
            }
            target = fallback;
        }
        return target;
    }

    /** 在 target 的父目录里按文件名忽略大小写找一个常规文件；找不到返回 null。 */
    private static Path findCaseInsensitiveSibling(Path target) {
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return null;
        }
        String wanted = target.getFileName().toString();
        try (var stream = Files.list(parent)) {
            return stream
                    .filter(p -> Files.isRegularFile(p)
                            && p.getFileName().toString().equalsIgnoreCase(wanted))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
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
