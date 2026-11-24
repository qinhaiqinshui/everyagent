package dev.everyagent.plugin.workflow.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 工作流定义（definitions/&lt;wf_id&gt;/definition.json，RPC {@code def.save} 原子重写）。
 *
 * <p>与实例完全解耦：实例持深拷贝快照，定义后续修改/删除不影响运行中与历史实例。
 * 保存时后端规范化：onError 缺省 supervisor、schemaVersion 归一、null 列表归空表
 * （见 {@code DefinitionValidator.normalize}）。
 *
 * <p><b>历史结构</b>：早期版本带 agents[] 总表（命名 agent 的集中声明：modelConfigId/
 * systemPrompt）；已删除该功能——会话线由 AI 节点的 {@code agent} 字段自引用，模型与
 * 系统提示直接配在节点上。旧 JSON 的 agents[] 在解析口经
 * {@link DefinitionJson#migrateLegacyAgents} 就地迁移（声明值复制到引用节点），不落盘
 * ——重新保存时自然转新形态。
 *
 * <p>列表排序约定：定义列表按 updatedAt 倒序。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Definition(
        Integer schemaVersion,
        String id,
        String name,
        String description,
        String workspaceId,
        Long createdAt,
        Long updatedAt,
        List<VariableDef> variables,
        OnError onError,
        Supervisor supervisor,
        List<NodeDef> nodes,
        List<EdgeDef> edges) {

    /** 当前存储结构版本。 */
    public static final int SCHEMA_VERSION = 1;

    /** 督导 agent 配置（onError=supervisor 时生效；systemPrompt 为空用默认模板）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Supervisor(
            String modelConfigId,
            String systemPrompt) {
    }

    /** null 安全：变量声明列表（无声明=空表）。 */
    public List<VariableDef> variablesOrEmpty() {
        return variables == null ? List.of() : variables;
    }

    /** null 安全：节点列表。 */
    public List<NodeDef> nodesOrEmpty() {
        return nodes == null ? List.of() : nodes;
    }

    /** null 安全：边列表。 */
    public List<EdgeDef> edgesOrEmpty() {
        return edges == null ? List.of() : edges;
    }

    /** 同构换 id（导入重赋 id / 测试构造用；其余字段逐项保留）。 */
    public Definition withId(String newId) {
        return new Definition(schemaVersion, newId, name, description, workspaceId,
                createdAt, updatedAt, variables, onError, supervisor, nodes, edges);
    }
}
