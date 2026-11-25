package dev.everyagent.plugin.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

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

    // ── joinMode 汇合屏障 ──

    @Test
    void joinModeValuesAreValidated() {
        // 未知取值报错（带节点定位）；null/""/none/all 全部合法
        assertThat(DefinitionValidator.validate(def(
                node("n1", "start"),
                node("n2", "command").command("echo hi").joinMode("sometimes"),
                edge("e1", "n1", "n2"))))
                .anyMatch(e -> e.contains("joinMode") && e.contains("n2"));
        for (String ok : new String[]{null, "", "none", "all"}) {
            Definition d = def(
                    node("n1", "start"),
                    node("n2", "command").command("echo hi").joinMode(ok),
                    edge("e1", "n1", "n2"));
            assertThat(DefinitionValidator.validate(d))
                    .as("joinMode=%s 应合法", ok).isEmpty();
        }
    }

    @Test
    void joinModeAllOnStartAndSingleInEdgeIsLenient() {
        // start 节点携带 all 宽松忽略（无入边，引擎等价 none）；入边数≤1 时 all 等价 none，不报错
        assertThat(DefinitionValidator.validate(def(node("n1", "start").joinMode("all"))))
                .isEmpty();
        assertThat(DefinitionValidator.validate(def(
                node("n1", "start"),
                node("n2", "command").command("echo hi").joinMode("all"),
                edge("e1", "n1", "n2"))))
                .isEmpty();
    }

    // ── 变量引用 ──

    @Test
    void varRefsMustBeDeclared() {
        Definition undeclared = def(
                node("n1", "start"),
                node("n2", "command").command("mkdir {{novelName}}"),
                node("n3", "ai").agent("gen").input("写《{{topic}}》"),
                edge("e1", "n1", "n2"), edge("e2", "n2", "n3"));
        List<String> errors = DefinitionValidator.validate(undeclared);
        assertThat(errors).anyMatch(e -> e.contains("{{novelName}}") && e.contains("command"));
        assertThat(errors).anyMatch(e -> e.contains("{{topic}}") && e.contains("input"));

        Definition declared = withVariables(undeclared,
                new VariableDef("novelName", null, true, null, null),
                new VariableDef("topic", null, false, null, null));
        assertThat(DefinitionValidator.validate(declared)).isEmpty();
    }

    @Test
    void unpairedDoubleBracesAreError() {
        // BUG-04a：{{ 缺配对 }} 不得静默按字面量输出——创建期即拒绝
        Definition unpaired = withVariables(def(
                node("n1", "start"),
                node("n2", "command").command("echo published={{topic}> done.txt"),
                node("n3", "ai").agent("gen").input("写 {{topic} 主题"),
                edge("e1", "n1", "n2"), edge("e2", "n2", "n3")),
                new VariableDef("topic", null, true, null, null));
        List<String> errors = DefinitionValidator.validate(unpaired);
        assertThat(errors).anyMatch(e -> e.contains("未配对") && e.contains("command"));
        assertThat(errors).anyMatch(e -> e.contains("未配对") && e.contains("input"));

        // 配对完整、多余单花括号（shell 花括号展开）不报
        Definition ok = withVariables(def(
                node("n1", "start"),
                node("n2", "command").command("echo {{topic}}} && echo {"),
                edge("e1", "n1", "n2")),
                new VariableDef("topic", null, true, null, null));
        assertThat(DefinitionValidator.validate(ok)).isEmpty();
    }

    // ── 命名会话线（agents 总表已删除：agent 名是 AI 节点自带引用） ──

    @Test
    void nodeAgentRefsNeedNoDeclaration() {
        // 会话线名只由 AI 节点引用，无集中声明——引用即可用
        Definition d = def(
                node("n1", "start"),
                node("n2", "ai").agent("writer"),
                node("n3", "ai").agent("reviewer"),
                node("n4", "ai").agent("writer"), // 多节点可复用同一条会话线
                edge("e1", "n1", "n2"), edge("e2", "n2", "n3"), edge("e3", "n3", "n4"));
        assertThat(DefinitionValidator.validate(d)).isEmpty();
    }

    @Test
    void normalizeKeepsNodeCarriedConfigAndFillsDefaults() {
        Definition d = def(
                node("n1", "start"),
                node("n2", "ai").agent("writer").modelConfigId("gpt5").systemPrompt("你是作者"));
        Definition normalized = DefinitionValidator.normalize(d);
        assertThat(normalized.nodesOrEmpty().get(1).systemPrompt()).isEqualTo("你是作者");
        assertThat(normalized.nodesOrEmpty().get(1).modelConfigId()).isEqualTo("gpt5");
        assertThat(normalized.onError()).isEqualTo(OnError.SUPERVISOR);
        assertThat(normalized.schemaVersion()).isEqualTo(1);
        assertThat(normalized.variablesOrEmpty()).isEmpty();
    }

    // ── 引用节点不得携带 systemPrompt（B3：定义节点是唯一事实源） ──

    @Test
    void referenceNodeWithSystemPromptIsError() {
        // 上游已有同名会话线（引用节点）携带 systemPrompt → 逐项报错，文案含定义节点定位
        Definition d = def(
                node("s1", "start"),
                node("d1", "ai").agent("writer").systemPrompt("你是作者"),
                node("r1", "ai").agent("writer").systemPrompt("API 直改塞进来的副本"),
                edge("e1", "s1", "d1"), edge("e2", "d1", "r1"));
        List<String> errors = DefinitionValidator.validate(d);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("引用节点");
        assertThat(errors.get(0)).contains("systemPrompt");
        assertThat(errors.get(0)).contains("writer");
        assertThat(errors.get(0)).contains("d1"); // 定义节点定位
        assertThat(errors.get(0)).contains("r1"); // 引用节点定位
    }

    @Test
    void definingNodeWithSystemPromptIsValid() {
        // 定义节点（上游无同名持有者=首次使用）携带 systemPrompt 合法；下游另一条线的定义节点同理
        Definition d = def(
                node("s1", "start"),
                node("d1", "ai").agent("writer").systemPrompt("你是作者"),
                node("r1", "ai").agent("reviewer").systemPrompt("你是评审"),
                edge("e1", "s1", "d1"), edge("e2", "d1", "r1"));
        assertThat(DefinitionValidator.validate(d)).isEmpty();
    }

    @Test
    void blankDefiningPromptWithCleanReferencesIsValid() {
        // 反馈实测形态：定义节点 systemPrompt 为空（=该会话线无系统提示词）+ 全部引用节点不带
        Definition d = def(
                node("s1", "start"),
                node("d1", "ai").agent("reviewer"),
                node("r1", "ai").agent("reviewer"),
                node("r2", "ai").agent("reviewer"),
                edge("e1", "s1", "d1"), edge("e2", "d1", "r1"), edge("e3", "r1", "r2"));
        assertThat(DefinitionValidator.validate(d)).isEmpty();
    }

    @Test
    void referenceNodeBlankPromptIsTreatedAsAbsent() {
        // 空白 systemPrompt 视同未携带（非空非空白才触发）
        Definition d = def(
                node("s1", "start"),
                node("d1", "ai").agent("writer").systemPrompt("你是作者"),
                node("r1", "ai").agent("writer").systemPrompt("   "),
                edge("e1", "s1", "d1"), edge("e2", "d1", "r1"));
        assertThat(DefinitionValidator.validate(d)).isEmpty();
    }

    @Test
    void cycleReferenceNodeWithSystemPromptIsError() {
        // 环：近似拓扑序（visited 截断 + 列表序平局）下 d1 仍是「a」的定义节点，r1 是引用节点
        Definition d = def(
                node("s1", "start"),
                node("d1", "ai").agent("a").maxRuns(3),
                node("r1", "ai").agent("a").systemPrompt("环上引用自带").maxRuns(3),
                edge("e1", "s1", "d1"), edge("e2", "d1", "r1"), edge("e3", "r1", "d1"));
        List<String> errors = DefinitionValidator.validate(d);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("引用节点");
        assertThat(errors.get(0)).contains("d1");
        assertThat(errors.get(0)).contains("r1");
    }

    @Test
    void cycleNodeThatIsItsOwnDefinitionMayCarryPrompt() {
        // 环近似：回边把定义节点自身带回它的上游集合，解析出的定义节点=本节点 → 仍是定义节点，
        // 携带 systemPrompt 合法（不误报、不要求清空）
        Definition d = def(
                node("s1", "start"),
                node("d1", "ai").agent("a").systemPrompt("唯一事实源").maxRuns(3),
                node("r1", "ai").agent("a").maxRuns(3),
                edge("e1", "s1", "d1"), edge("e2", "d1", "r1"), edge("e3", "r1", "d1"));
        assertThat(DefinitionValidator.validate(d)).isEmpty();
    }

    @Test
    void commandNodeSystemPromptIsIgnored() {
        // 现状保持：非 AI 节点携带 systemPrompt 无会话线语义，校验忽略（不改现状）
        Definition d = def(
                node("s1", "start"),
                node("c1", "command").command("echo hi").systemPrompt("被忽略"),
                edge("e1", "s1", "c1"));
        assertThat(DefinitionValidator.validate(d)).isEmpty();
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
        Definition d = def(node("n1", "start"), node("n2", "ai").agent("Supervisor"),
                edge("e1", "n1", "n2"));
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
                null, null, null, List.of(node("n1", "start").def), null);
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
                    c, def.timeoutMs(), def.maxRuns(), def.agent(), def.modelConfigId(), def.input(),
                    def.systemPrompt(), def.joinMode()));
        }

        N timeoutMs(Long t) {
            return new N(new NodeDef(def.id(), def.type(), def.name(), def.x(), def.y(),
                    def.command(), t, def.maxRuns(), def.agent(), def.modelConfigId(), def.input(),
                    def.systemPrompt(), def.joinMode()));
        }

        N input(String i) {
            return new N(new NodeDef(def.id(), def.type(), def.name(), def.x(), def.y(),
                    def.command(), def.timeoutMs(), def.maxRuns(), def.agent(), def.modelConfigId(), i,
                    def.systemPrompt(), def.joinMode()));
        }

        N agent(String a) {
            return new N(new NodeDef(def.id(), def.type(), def.name(), def.x(), def.y(),
                    def.command(), def.timeoutMs(), def.maxRuns(), a, def.modelConfigId(), def.input(),
                    def.systemPrompt(), def.joinMode()));
        }

        N modelConfigId(String m) {
            return new N(new NodeDef(def.id(), def.type(), def.name(), def.x(), def.y(),
                    def.command(), def.timeoutMs(), def.maxRuns(), def.agent(), m, def.input(),
                    def.systemPrompt(), def.joinMode()));
        }

        N systemPrompt(String s) {
            return new N(new NodeDef(def.id(), def.type(), def.name(), def.x(), def.y(),
                    def.command(), def.timeoutMs(), def.maxRuns(), def.agent(), def.modelConfigId(),
                    def.input(), s, def.joinMode()));
        }

        N maxRuns(Integer m) {
            return new N(new NodeDef(def.id(), def.type(), def.name(), def.x(), def.y(),
                    def.command(), def.timeoutMs(), m, def.agent(), def.modelConfigId(), def.input(),
                    def.systemPrompt(), def.joinMode()));
        }

        N joinMode(String j) {
            return new N(new NodeDef(def.id(), def.type(), def.name(), def.x(), def.y(),
                    def.command(), def.timeoutMs(), def.maxRuns(), def.agent(), def.modelConfigId(),
                    def.input(), def.systemPrompt(), j));
        }
    }

    static N node(String id, String type) {
        return new N(new NodeDef(id, type, id, 0.0, 0.0, null, null, null, null, null, null, null, null));
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
        for (Object p : parts) {
            if (p instanceof N n) {
                nodes.add(n.def);
            } else if (p instanceof EdgeDef e) {
                edges.add(e);
            }
        }
        return new Definition(null, null, "测试定义", null, "w_test", null, null,
                null, null, null, nodes, edges);
    }

    static Definition withVariables(Definition d, VariableDef... vars) {
        return new Definition(d.schemaVersion(), d.id(), d.name(), d.description(), d.workspaceId(),
                d.createdAt(), d.updatedAt(), List.of(vars), d.onError(), d.supervisor(),
                d.nodes(), d.edges());
    }
}
