package dev.everyagent.plugin.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.everyagent.plugin.workflow.model.AgentDef;
import dev.everyagent.plugin.workflow.model.ConditionDef;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.EdgeDef;
import dev.everyagent.plugin.workflow.model.NodeDef;
import dev.everyagent.plugin.workflow.model.OnError;
import dev.everyagent.plugin.workflow.model.VariableDef;
import dev.everyagent.plugin.workflow.validate.DefinitionValidator;

/** 校验器逐规则测试（错误列表供 BAD_PARAMS 逐项回显）。 */
class DefinitionValidatorTest {

    // ── start 节点 ──

    @Test
    void missingStartIsError() {
        List<String> errors = DefinitionValidator.validate(def(
                node("n1", "command").command("echo hi"),
                edge("e1", "n1", "n1")));
        assertThat(errors).anyMatch(e -> e.contains("start"));
    }

    @Test
    void duplicateStartIsError() {
        List<String> errors = DefinitionValidator.validate(def(
                node("n1", "start"), node("n2", "start")));
        assertThat(errors).anyMatch(e -> e.contains("唯一"));
    }

    // ── 边引用 ──

    @Test
    void edgeToMissingNodeIsError() {
        List<String> errors = DefinitionValidator.validate(def(
                node("n1", "start"),
                edge("e1", "n1", "nX")));
        assertThat(errors).anyMatch(e -> e.contains("不存在(to)"));
    }

    @Test
    void edgeFromMissingNodeIsError() {
        List<String> errors = DefinitionValidator.validate(def(
                node("n1", "start"),
                edge("e1", "nX", "n1")));
        assertThat(errors).anyMatch(e -> e.contains("不存在(from)"));
    }

    // ── maxRuns ──

    @Test
    void maxRunsBoundsAreEnforced() {
        assertThat(DefinitionValidator.validate(def(node("n1", "start").maxRuns(0)))).isNotEmpty();
        assertThat(DefinitionValidator.validate(def(node("n1", "start").maxRuns(11)))).isNotEmpty();
        assertThat(DefinitionValidator.validate(def(node("n1", "start").maxRuns(1)))).isEmpty();
        assertThat(DefinitionValidator.validate(def(node("n1", "start").maxRuns(10)))).isEmpty();
        // 缺省合法（引擎按 1 处理）
        assertThat(DefinitionValidator.validate(def(node("n1", "start")))).isEmpty();
    }

    // ── 变量引用 ──

    @Test
    void varRefsMustBeDeclared() {
        Definition undeclared = def(
                node("n1", "start"),
                node("n2", "command").command("mkdir {{novelName}}"),
                node("n3", "ai").input("写《{{topic}}》"),
                edge("e1", "n1", "n2"), edge("e2", "n2", "n3"));
        List<String> errors = DefinitionValidator.validate(undeclared);
        assertThat(errors).anyMatch(e -> e.contains("{{novelName}}") && e.contains("command"));
        assertThat(errors).anyMatch(e -> e.contains("{{topic}}") && e.contains("input"));

        Definition declared = withVariables(undeclared,
                new VariableDef("novelName", null, true, null, null),
                new VariableDef("topic", null, false, null, null));
        assertThat(DefinitionValidator.validate(declared)).isEmpty();
    }

    // ── agent 引用即声明 ──

    @Test
    void normalizeMergesReferencedAgents() {
        Definition d = def(
                node("n1", "start"),
                node("n2", "ai").agent("writer"),
                node("n3", "ai").agent("reviewer"),
                node("n4", "ai").agent("writer"), // 重复引用只合并一次
                edge("e1", "n1", "n2"), edge("e2", "n2", "n3"), edge("e3", "n3", "n4"));
        // 未声明不报错（引用即声明）
        assertThat(DefinitionValidator.validate(d)).isEmpty();
        Definition normalized = DefinitionValidator.normalize(d);
        assertThat(normalized.agentsOrEmpty())
                .extracting(AgentDef::name)
                .containsExactly("writer", "reviewer");
        // 未显式声明的补默认模型 null
        assertThat(normalized.agentsOrEmpty())
                .allSatisfy(a -> assertThat(a.modelConfigId()).isNull());
    }

    @Test
    void normalizeKeepsDeclaredAgentsFirstAndFillsDefaults() {
        Definition d = new Definition(null, null, "n", null, null, null, null,
                null, null, null,
                List.of(new AgentDef("writer", "gpt5", "你是作者")),
                List.of(node("n1", "start").def, node("n2", "ai").agent("writer").def,
                        node("n3", "ai").agent("helper").def),
                List.of(edge("e1", "n1", "n2"), edge("e2", "n2", "n3")));
        Definition normalized = DefinitionValidator.normalize(d);
        assertThat(normalized.agentsOrEmpty())
                .extracting(AgentDef::name)
                .containsExactly("writer", "helper"); // 声明在前，引用补在后
        assertThat(normalized.agentsOrEmpty().get(0).systemPrompt()).isEqualTo("你是作者");
        assertThat(normalized.onError()).isEqualTo(OnError.SUPERVISOR);
        assertThat(normalized.schemaVersion()).isEqualTo(1);
        assertThat(normalized.variablesOrEmpty()).isEmpty();
    }

    // ── 循环允许 ──

    @Test
    void cyclesAreAllowed() {
        Definition cycle = def(
                node("n1", "start"),
                node("n2", "ai").agent("a").maxRuns(3),
                node("n3", "ai").agent("a").maxRuns(3),
                edge("e1", "n1", "n2"), edge("e2", "n2", "n3"), edge("e3", "n3", "n2"));
        assertThat(DefinitionValidator.validate(cycle)).isEmpty();
    }

    // ── 正则条件 ──

    @Test
    void outputMatchesPatternMustCompile() {
        Definition bad = def(
                node("n1", "start"),
                edge("e1", "n1", "n1", new ConditionDef("outputMatches", null, null, "[unclosed")));
        assertThat(DefinitionValidator.validate(bad)).anyMatch(e -> e.contains("正则不可编译"));

        Definition good = def(
                node("n1", "start"),
                edge("e1", "n1", "n1", new ConditionDef("outputMatches", null, null, "^ok.*$")));
        assertThat(DefinitionValidator.validate(good)).isEmpty();
    }

    @Test
    void conditionTypeAndParamsAreValidated() {
        // 未知类型
        assertThat(DefinitionValidator.validate(def(node("n1", "start"),
                edge("e1", "n1", "n1", new ConditionDef("sometimes", null, null, null)))))
                .anyMatch(e -> e.contains("condition.type 未知"));
        // exitCode 缺 value
        assertThat(DefinitionValidator.validate(def(node("n1", "start"),
                edge("e1", "n1", "n1", new ConditionDef("exitCode", null, null, null)))))
                .anyMatch(e -> e.contains("缺少 value"));
        // outputContains 缺 text
        assertThat(DefinitionValidator.validate(def(node("n1", "start"),
                edge("e1", "n1", "n1", new ConditionDef("outputContains", null, null, null)))))
                .anyMatch(e -> e.contains("缺少 text"));
        // 合法参数
        assertThat(DefinitionValidator.validate(def(node("n1", "start"),
                edge("e1", "n1", "n1", new ConditionDef("outputContains", null, "成功", null)))))
                .isEmpty();
    }

    // ── 保留 agent 名 ──

    @Test
    void reservedAgentNamesAreRejected() {
        for (String reserved : new String[]{"supervisor", "meta", "events", "runs", "agents"}) {
            Definition declared = def(
                    node("n1", "start"), node("n2", "ai").agent("ok_agent"),
                    edge("e1", "n1", "n2"),
                    new AgentDef(reserved, null, null));
            assertThat(DefinitionValidator.validate(declared))
                    .as("声明保留名 %s 应报错", reserved)
                    .anyMatch(e -> e.contains("保留名"));

            Definition referenced = def(
                    node("n1", "start"), node("n2", "ai").agent(reserved),
                    edge("e1", "n1", "n2"));
            assertThat(DefinitionValidator.validate(referenced))
                    .as("引用保留名 %s 应报错", reserved)
                    .anyMatch(e -> e.contains("保留名"));
        }
    }

    @Test
    void reservedNameCheckIsCaseInsensitiveForWindowsFs() {
        Definition d = def(node("n1", "start"), node("n2", "ai").agent("x"),
                edge("e1", "n1", "n2"), new AgentDef("Supervisor", null, null));
        assertThat(DefinitionValidator.validate(d)).anyMatch(e -> e.contains("保留名"));
    }

    @Test
    void unsafeAgentNameIsRejected() {
        Definition d = def(node("n1", "start"), node("n2", "ai").agent("bad/name"),
                edge("e1", "n1", "n2"));
        assertThat(DefinitionValidator.validate(d)).anyMatch(e -> e.contains("agent 名不安全"));
    }

    // ── 结构完整性 ──

    @Test
    void duplicateNodeAndEdgeIdsAndUnknownTypeAreErrors() {
        List<String> errors = DefinitionValidator.validate(def(
                node("n1", "start"), node("n1", "ai"), node("n3", "magic"),
                edge("e1", "n1", "n1"), edge("e1", "n1", "n3")));
        assertThat(errors).anyMatch(e -> e.contains("节点 id 重复"));
        assertThat(errors).anyMatch(e -> e.contains("type 未知"));
        assertThat(errors).anyMatch(e -> e.contains("边 id 重复"));
    }

    @Test
    void commandNodeRequiresCommandText() {
        assertThat(DefinitionValidator.validate(def(node("n1", "command"))))
                .anyMatch(e -> e.contains("缺少 command"));
    }

    @Test
    void duplicateVariableNameIsError() {
        Definition d = withVariables(def(node("n1", "start")),
                new VariableDef("v", null, true, null, null),
                new VariableDef("v", null, false, null, null));
        assertThat(DefinitionValidator.validate(d)).anyMatch(e -> e.contains("变量名重复"));
    }

    @Test
    void blankNameIsError() {
        Definition d = new Definition(null, null, " ", null, null, null, null,
                null, null, null, null, List.of(node("n1", "start").def), null);
        assertThat(DefinitionValidator.validate(d)).anyMatch(e -> e.contains("name 不能为空"));
    }

    // ── 构造工具（可变测试桩，build 前可再补字段）──

    /** NodeDef 的测试用可变包装（record 不可变,链式补字段用）。 */
    static final class N {
        final NodeDef def;

        private N(NodeDef def) {
            this.def = def;
        }

        N command(String c) {
            return new N(new NodeDef(def.id(), def.type(), def.name(), def.x(), def.y(),
                    c, def.timeoutMs(), def.maxRuns(), def.agent(), def.modelConfigId(), def.input()));
        }

        N timeoutMs(Long t) {
            return new N(new NodeDef(def.id(), def.type(), def.name(), def.x(), def.y(),
                    def.command(), t, def.maxRuns(), def.agent(), def.modelConfigId(), def.input()));
        }

        N input(String i) {
            return new N(new NodeDef(def.id(), def.type(), def.name(), def.x(), def.y(),
                    def.command(), def.timeoutMs(), def.maxRuns(), def.agent(), def.modelConfigId(), i));
        }

        N agent(String a) {
            return new N(new NodeDef(def.id(), def.type(), def.name(), def.x(), def.y(),
                    def.command(), def.timeoutMs(), def.maxRuns(), a, def.modelConfigId(), def.input()));
        }

        N maxRuns(Integer m) {
            return new N(new NodeDef(def.id(), def.type(), def.name(), def.x(), def.y(),
                    def.command(), def.timeoutMs(), m, def.agent(), def.modelConfigId(), def.input()));
        }
    }

    static N node(String id, String type) {
        return new N(new NodeDef(id, type, id, 0.0, 0.0, null, null, null, null, null, null));
    }

    static EdgeDef edge(String id, String from, String to) {
        return new EdgeDef(id, from, to, null);
    }

    static EdgeDef edge(String id, String from, String to, ConditionDef cond) {
        return new EdgeDef(id, from, to, cond);
    }

    static Definition def(Object... parts) {
        List<NodeDef> nodes = new java.util.ArrayList<>();
        List<EdgeDef> edges = new java.util.ArrayList<>();
        List<AgentDef> agents = new java.util.ArrayList<>();
        for (Object p : parts) {
            if (p instanceof N n) {
                nodes.add(n.def);
            } else if (p instanceof EdgeDef e) {
                edges.add(e);
            } else if (p instanceof AgentDef a) {
                agents.add(a);
            }
        }
        return new Definition(null, null, "测试定义", null, "w_test", null, null,
                null, null, null, agents, nodes, edges);
    }

    static Definition withVariables(Definition d, VariableDef... vars) {
        return new Definition(d.schemaVersion(), d.id(), d.name(), d.description(), d.workspaceId(),
                d.createdAt(), d.updatedAt(), List.of(vars), d.onError(), d.supervisor(),
                d.agents(), d.nodes(), d.edges());
    }
}
