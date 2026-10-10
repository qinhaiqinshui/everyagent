package dev.everyagent.worker.plugin.loader;

import dev.everyagent.contract.json.Json;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 JSON 字符串解析 {@link PluginDescriptor}。
 * <p>
 * 使用项目共享的 {@link Json} 工具类(Jackson 3)解析为 {@link JsonNode} 后逐字段提取,
 * 缺失字段以合理默认值(空字符串 / 空 list / 空 map)填充,绝不返回 null。
 */
public final class PluginDescriptorParser {

    private PluginDescriptorParser() {
    }

    /**
     * 从 JSON 字符串解析插件清单。
     *
     * @param json plugin.json 文本内容(不允许 JSONC 注释,Jackson 默认不解析注释)
     * @return 解析后的 {@link PluginDescriptor}(各字段非 null)
     * @throws IOException JSON 解析失败时抛出(Jackson 异常包装为 IOException)
     */
    public static PluginDescriptor parse(String json) throws IOException {
        JsonNode root;
        try {
            root = Json.parse(json);
        } catch (RuntimeException e) {
            throw new IOException("Failed to parse plugin.json: " + e.getMessage(), e);
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            return emptyDescriptor();
        }
        if (!root.isObject()) {
            throw new IOException("plugin.json root must be a JSON object, got: " + nodeType(root));
        }

        String id = text(root, "id");
        String name = text(root, "name");
        String version = text(root, "version");
        String description = text(root, "description");
        String author = text(root, "author");
        String minAppVersion = text(root, "minAppVersion");

        PluginDescriptor.Requires requires = parseRequires(root.get("requires"));
        PluginDescriptor.Provides provides = parseProvides(root.get("provides"));
        List<String> activationEvents = stringList(root.get("activationEvents"));
        PluginDescriptor.Contributes contributes = parseContributes(root.get("contributes"));

        return new PluginDescriptor(
                id, name, version, description, author, minAppVersion,
                requires, provides, activationEvents, contributes
        );
    }

    /**
     * 从文件读取并解析插件清单。
     *
     * @param file plugin.json 路径
     * @return 解析后的 {@link PluginDescriptor}
     * @throws IOException 读取或解析失败
     */
    public static PluginDescriptor parseFile(Path file) throws IOException {
        String content = Files.readString(file);
        return parse(content);
    }

    // ---- 内部辅助 ----

    private static PluginDescriptor emptyDescriptor() {
        return new PluginDescriptor(
                "", "", "", "", "", "",
                new PluginDescriptor.Requires(List.of(), ""),
                new PluginDescriptor.Provides(Map.of(), List.of(), List.of(), ""),
                List.of(),
                new PluginDescriptor.Contributes(Map.of())
        );
    }

    private static PluginDescriptor.Requires parseRequires(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return new PluginDescriptor.Requires(List.of(), "");
        }
        List<String> spi = stringList(node.get("spi"));
        String everyAgent = text(node, "every-agent");
        return new PluginDescriptor.Requires(spi, everyAgent);
    }

    private static PluginDescriptor.Provides parseProvides(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return new PluginDescriptor.Provides(Map.of(), List.of(), List.of(), "");
        }
        Map<String, String> spi = stringMap(node.get("spi"));
        List<String> rpc = stringList(node.get("rpc"));
        List<String> slash = stringList(node.get("slash"));
        String web = text(node, "web");
        return new PluginDescriptor.Provides(spi, rpc, slash, web);
    }

    private static PluginDescriptor.Contributes parseContributes(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return new PluginDescriptor.Contributes(Map.of());
        }
        Map<String, PluginDescriptor.ConfigItem> config = parseConfigMap(node.get("config"));
        return new PluginDescriptor.Contributes(config);
    }

    private static Map<String, PluginDescriptor.ConfigItem> parseConfigMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        Map<String, PluginDescriptor.ConfigItem> out = new LinkedHashMap<>();
        for (var entry : node.properties()) {
            JsonNode item = entry.getValue();
            if (item == null || item.isMissingNode() || item.isNull() || !item.isObject()) {
                continue;
            }
            String type = text(item, "type");
            Object defaultValue = unwrap(item.get("default"));
            String description = text(item, "description");
            out.put(entry.getKey(), new PluginDescriptor.ConfigItem(type, defaultValue, description));
        }
        return out;
    }

    private static String text(JsonNode parent, String field) {
        if (parent == null || !parent.isObject()) {
            return "";
        }
        JsonNode node = parent.get(field);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.asText("");
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(node.size());
        for (JsonNode el : node) {
            if (el != null && !el.isMissingNode() && !el.isNull()) {
                out.add(el.asText(""));
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (var entry : node.properties()) {
            JsonNode val = entry.getValue();
            String s = (val == null || val.isMissingNode() || val.isNull()) ? "" : val.asText("");
            out.put(entry.getKey(), s);
        }
        return Map.copyOf(out);
    }

    /**
     * 将 JsonNode 解包为原生 Java 对象(String / Integer / Double / Boolean / null),
     * 用于 ConfigItem.defaultValue。
     */
    private static Object unwrap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asString();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        // 复杂类型(对象/数组)保留为 JsonNode,调用方可自行处理
        return node;
    }

    private static String nodeType(JsonNode node) {
        if (node == null) {
            return "null(JsonNode)";
        }
        return node.getNodeType().toString();
    }
}
