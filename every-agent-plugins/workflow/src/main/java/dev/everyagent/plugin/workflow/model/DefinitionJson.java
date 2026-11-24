package dev.everyagent.plugin.workflow.model;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.everyagent.contract.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 定义 JSON 解析单点：旧结构树级迁移 + 反序列化。
 *
 * <p><b>为什么要单点</b>：定义 JSON 有三个解析口（磁盘 definition.json 扫描、RPC
 * {@code definition} 参数、workflow_create/update 工具的 JSON 文本），外加实例
 * meta.json 内嵌的定义快照——各口都必须先做同一份旧结构迁移再反序列化，收在
 * 这里防漂移。
 *
 * <p><b>迁移内容</b>（{@link #migrateLegacyAgents}）：早期版本带 agents[] 总表
 * （命名 agent 的集中声明）。该功能已删除——会话线由 AI 节点 {@code agent} 字段
 * 自引用，模型与系统提示配在节点上。迁移把每个 agents[] 条目的
 * {@code systemPrompt}/{@code modelConfigId} 复制到引用该名的全部 ai 节点
 * （节点自带值优先），再移除 {@code agents} 键。已是新结构的树原样返回（幂等）。
 * 迁移只发生在内存：磁盘文件不动，重新保存时自然写入新形态。
 */
public final class DefinitionJson {

    private DefinitionJson() {
    }

    /** 解析定义 JSON 树（先迁移旧结构，再反序列化为 {@link Definition}）。 */
    public static Definition parse(JsonNode tree) {
        return Json.convert(migrateLegacyAgents(tree), Definition.class);
    }

    /** 解析定义 JSON 文本（{@link #parse} 的文本入口）。 */
    public static Definition parseText(String text) {
        return parse(Json.parse(text));
    }

    /**
     * 旧结构 agents[] 总表 → 节点自带字段的树级迁移（原地修改传入的树并返回）。
     * 非 JSON 对象（缺失节点/标量）原样返回，供快照路径安全调用。
     */
    public static JsonNode migrateLegacyAgents(JsonNode tree) {
        if (tree == null || !tree.isObject()) {
            return tree;
        }
        JsonNode agents = tree.path("agents");
        if (!agents.isArray() || agents.isEmpty()) {
            return tree;
        }
        // name → 声明条目（后同名覆盖先；旧校验器本就禁重复，此处仅防御）
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        for (JsonNode a : agents) {
            String name = a.path("name").asString(null);
            if (name != null && !name.isBlank()) {
                byName.put(name, a);
            }
        }
        if (!byName.isEmpty()) {
            JsonNode nodes = tree.path("nodes");
            if (nodes.isArray()) {
                for (JsonNode n : nodes) {
                    if (n == null || !n.isObject() || !NodeDef.TYPE_AI.equals(n.path("type").asString(null))) {
                        continue;
                    }
                    String agent = n.path("agent").asString(null);
                    if (agent == null || agent.isBlank()) {
                        continue;
                    }
                    JsonNode declared = byName.get(agent);
                    if (declared == null) {
                        continue;
                    }
                    ObjectNode mutable = (ObjectNode) n;
                    if (n.path("systemPrompt").asString(null) == null) {
                        String sp = declared.path("systemPrompt").asString(null);
                        if (sp != null) {
                            mutable.put("systemPrompt", sp);
                        }
                    }
                    if (n.path("modelConfigId").asString(null) == null) {
                        String mc = declared.path("modelConfigId").asString(null);
                        if (mc != null) {
                            mutable.put("modelConfigId", mc);
                        }
                    }
                }
            }
        }
        ((ObjectNode) tree).remove("agents");
        return tree;
    }
}
