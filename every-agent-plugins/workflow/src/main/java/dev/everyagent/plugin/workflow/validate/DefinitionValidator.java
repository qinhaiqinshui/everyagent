package dev.everyagent.plugin.workflow.validate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import dev.everyagent.plugin.workflow.engine.NodeRunners;
import dev.everyagent.plugin.workflow.model.ConditionDef;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.EdgeDef;
import dev.everyagent.plugin.workflow.model.NodeDef;
import dev.everyagent.plugin.workflow.model.OnError;
import dev.everyagent.plugin.workflow.model.VariableDef;

/**
 * 工作流定义全量校验（保存/导入前必过；错误列表供 BAD_PARAMS 逐项回显）。
 *
 * <p>规则清单：
 * <ol>
 *   <li>name 非空；node id / edge id 非空且唯一；node type ∈ {start, command, ai}；</li>
 *   <li>start 节点唯一且必须存在；</li>
 *   <li>边引用的节点（from/to）必须存在；</li>
 *   <li>maxRuns ∈ [1,10]（缺省合法，引擎按 1 处理）；timeoutMs &gt; 0；命令节点 command 非空；</li>
 *   <li>joinMode ∈ {null, "", "none", "all"}（未知取值逐项报错带节点定位；start 节点携带
 *       all 宽松忽略——无入边等价 none，入边数≤1 时 all 亦等价 none，均不报错）；</li>
 *   <li>command / input 中 {@code {{var}}} 引用的变量必须已声明（渲染范围仅此两字段）；</li>
 *   <li>AI 节点引用的 agent 名安全（文件名安全字符集）且不撞保留名
 *       meta/events/runs/agents/supervisor——supervisor 保留给督导会话线；</li>
 *   <li>AI 引用节点（上游已有同名会话线持有者）不允许携带 systemPrompt——定义节点
 *       （拓扑序最早持有者）是唯一事实源，逐项报错并给出定义节点定位；判定复用运行时
 *       同一实现 {@link NodeRunners#upstreamAgentDefinitions}（环上解析到自身时视为
 *       定义节点，携带合法）；</li>
 *   <li>循环允许（不校验环——maxRuns 是运行期兜底）；</li>
 *   <li>outputMatches 正则可编译；exitCode/outputContains 等条件带齐各自参数；</li>
 *   <li>变量名唯一且为 [A-Za-z0-9_-]+（渲染替换的键安全）；</li>
 *   <li><b>边数据传递（assigns/input）</b>：变量名安全；边 input 模板非空；assigns 的
 *       from ∈ {output}；边写入变量（assigns var ∪ 边 input 变量）不得与用户输入变量
 *       同名；<b>assigns 变量禁跨源</b>（具名输出=实例级载体，不同源竞写无静态时序；
 *       同源多边按边列表序后写覆盖合法；边 input 变量=到达即写的路径指令，允许跨源）；
 *       {@code {{var}}} 引用（command/input/边 input 模板三处扫描）必须已声明（用户
 *       变量或某条边的写入变量）且来源可达——节点引用边写入变量要求写入边源节点在
 *       其上游闭包内（环上含自身）、边 input 模板要求写入边源节点在 {@code from}
 *       节点的上游闭包或就是 {@code from} 自身（模板渲染发生在 from 完成时，值须已
 *       就绪；不可达 = 运行时序不确定的空值，创建期即拒）。</li>
 * </ol>
 *
 * <p>{@link #normalize} 在校验通过后调用：onError 缺省 supervisor、schemaVersion 归一、
 * null 列表归空表。<b>不分配 id/时间戳</b>——那是 WorkflowStore 的职责。
 */
public final class DefinitionValidator {

    /** 循环上限界（含）。 */
    public static final int MIN_MAX_RUNS = 1;
    public static final int MAX_MAX_RUNS = 10;

    /** 保留 agent 名：supervisor=督导会话线专用；其余为实例目录保留名（防误用自创格式）。 */
    public static final Set<String> RESERVED_AGENT_NAMES =
            Set.of("meta", "events", "runs", "agents", "supervisor");

    /** 变量名字符集（{{varName}} 渲染键）。 */
    private static final Pattern SAFE_VAR_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** 模板变量引用（仅 command/input 两字段扫描）。 */
    private static final Pattern VAR_REF = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_-]+)\\s*\\}\\}");

    private DefinitionValidator() {
    }

    /** 全量校验；返回逐项错误列表（空=通过）。 */
    public static List<String> validate(Definition d) {
        List<String> errors = new ArrayList<>();
        if (d == null) {
            errors.add("定义为空");
            return errors;
        }
        if (d.name() == null || d.name().isBlank()) {
            errors.add("name 不能为空");
        }
        validateVariables(d, errors);
        Set<String> nodeIds = validateNodes(d, errors);
        Map<String, Set<String>> writeSources = validateEdgeData(d, errors);
        validateEdges(d, nodeIds, errors);
        validateVarRefs(d, writeSources, errors);
        return errors;
    }

    private static void validateVariables(Definition d, List<String> errors) {
        Set<String> seen = new LinkedHashSet<>();
        for (VariableDef v : d.variablesOrEmpty()) {
            if (v == null) {
                errors.add("variables 含空项");
                continue;
            }
            if (v.name() == null || v.name().isBlank()) {
                errors.add("变量 name 不能为空: " + v.label());
                continue;
            }
            if (!SAFE_VAR_NAME.matcher(v.name()).matches()) {
                errors.add("变量名只能含字母/数字/下划线/连字符(≤64): " + v.name());
                continue;
            }
            if (!seen.add(v.name())) {
                errors.add("变量名重复: " + v.name());
            }
        }
    }

    /** 校验节点，返回合法 node id 集合。 */
    private static Set<String> validateNodes(Definition d, List<String> errors) {
        Set<String> nodeIds = new LinkedHashSet<>();
        int startCount = 0;
        for (NodeDef n : d.nodesOrEmpty()) {
            if (n == null) {
                errors.add("nodes 含空项");
                continue;
            }
            String where = n.id() == null ? "(无 id 节点)" : n.id();
            if (n.id() == null || n.id().isBlank()) {
                errors.add("节点 id 不能为空: " + n.name());
                continue;
            }
            if (!nodeIds.add(n.id())) {
                errors.add("节点 id 重复: " + n.id());
                continue;
            }
            if (n.type() == null || n.type().isBlank()) {
                errors.add("节点 type 不能为空: " + where);
            } else if (!NodeDef.TYPE_START.equals(n.type()) && !NodeDef.TYPE_COMMAND.equals(n.type())
                    && !NodeDef.TYPE_AI.equals(n.type())) {
                errors.add("节点 type 未知(仅 start/command/ai): " + n.type() + " @" + where);
            }
            if (NodeDef.TYPE_START.equals(n.type())) {
                startCount++;
            }
            if (n.maxRuns() != null && (n.maxRuns() < MIN_MAX_RUNS || n.maxRuns() > MAX_MAX_RUNS)) {
                errors.add("maxRuns 必须在 [1,10]: " + n.maxRuns() + " @" + where);
            }
            // joinMode 取值白名单；start/单入边节点携带 all 等价 none（宽松不报错——见类 javadoc）
            if (n.joinMode() != null && !n.joinMode().isEmpty()
                    && !NodeDef.JOIN_NONE.equals(n.joinMode()) && !NodeDef.JOIN_ALL.equals(n.joinMode())) {
                errors.add("joinMode 取值未知(允许 none/all): " + n.joinMode() + " @" + where);
            }
            if (n.timeoutMs() != null && n.timeoutMs() <= 0) {
                errors.add("timeoutMs 必须 > 0: " + n.timeoutMs() + " @" + where);
            }
            if (NodeDef.TYPE_COMMAND.equals(n.type()) && (n.command() == null || n.command().isBlank())) {
                errors.add("command 节点缺少 command: " + where);
            }
            if (NodeDef.TYPE_AI.equals(n.type())) {
                // 会话线必选：不再有「一次性会话」——要么在本节点新建（agent 名 + systemPrompt），
                // 要么选择上游已有的会话线名（编辑器下拉候选）
                if (n.agent() == null || n.agent().isBlank()) {
                    errors.add("AI 节点必须指定 agent 名(新建或选择上游已有会话线): " + where);
                } else if (n.systemPrompt() != null && !n.systemPrompt().isBlank()) {
                    // 引用节点不得携带 systemPrompt：运行时 effectiveSystemPrompt 是节点自带值
                    // 优先，API 直改 JSON 塞进来的副本会覆盖定义节点（与「定义节点是唯一事实源」
                    // 契约相悖），故创建/更新期直接拒绝该形态。判定与运行时同一实现
                    // （upstreamAgentDefinitions），环近似下回边会把节点自身带回其上游集合，
                    // 此时解析出的定义节点即本节点——视为定义节点，携带合法，不误报。
                    NodeDef defining = NodeRunners.upstreamAgentDefinitions(d, n.id()).get(n.agent());
                    if (defining != null && !n.id().equals(defining.id())) {
                        errors.add("节点「" + nodeLabel(n) + "」是会话线「" + n.agent()
                                + "」的引用节点,不允许携带 systemPrompt(定义节点是唯一事实源;"
                                + "请改上游定义节点「" + nodeLabel(defining)
                                + "」或清空本节点 systemPrompt) @" + where);
                    }
                }
            }
            if (n.agent() != null && !n.agent().isBlank()) {
                // 会话线名即节点自带引用：不要求别处声明，但名字本身要安全且不撞保留名
                if (!safeAgentName(n.agent())) {
                    errors.add("节点引用的 agent 名不安全: " + n.agent() + " @" + where);
                } else if (isReserved(n.agent())) {
                    errors.add("节点引用的 agent 名撞保留名(supervisor 为督导专用): " + n.agent() + " @" + where);
                }
            }
        }
        if (startCount == 0) {
            errors.add("缺少 start 节点(有且仅允许一个)");
        } else if (startCount > 1) {
            errors.add("start 节点必须唯一(发现 " + startCount + " 个)");
        }
        return nodeIds;
    }

    /**
     * 边数据传递结构校验（assigns/input），返回「边写入变量 → 写入源节点集合」（含
     * assigns 与边 input 两类写入端）供引用/可达性检查消费。规则：变量名安全；边
     * input 模板非空；from ∈ {output}；边写入变量不与用户输入变量同名（解析优先级
     * 固定为边写入值优先，同名只剩歧义）。<b>assigns 变量禁跨源</b>——具名输出是
     * 实例级长生命周期载体（任意距离消费），不同源节点竞写无静态时序（同源多边按
     * 边列表序后写覆盖合法）；边 input 变量<b>允许跨源</b>——「边激活即写」与目标
     * 触发耦合（e0/e1/e2 式路径指令：多条不同源入边共写缺省 {@code input}），同名
     * 后写覆盖。
     */
    private static Map<String, Set<String>> validateEdgeData(Definition d, List<String> errors) {
        Set<String> userVars = new LinkedHashSet<>();
        for (VariableDef v : d.variablesOrEmpty()) {
            if (v != null && v.name() != null) {
                userVars.add(v.name());
            }
        }
        Map<String, Set<String>> writeSources = new LinkedHashMap<>();
        Map<String, Set<String>> assignSources = new LinkedHashMap<>();
        for (EdgeDef e : d.edgesOrEmpty()) {
            if (e == null) {
                continue;
            }
            String where = "边 " + e.id();
            // from/to 缺失的边由 validateEdges 报错；此处跳过数据面校验（源集合不收 null 键）
            if (e.from() == null || e.from().isBlank() || e.to() == null || e.to().isBlank()) {
                continue;
            }
            if (e.assigns() != null) {
                for (EdgeDef.EdgeAssign a : e.assigns()) {
                    if (a == null) {
                        errors.add("assigns 含空项: " + where);
                        continue;
                    }
                    if (a.var() == null || a.var().isBlank()) {
                        errors.add("assigns 的 var 不能为空: " + where);
                        continue;
                    }
                    if (!SAFE_VAR_NAME.matcher(a.var()).matches()) {
                        errors.add("assigns 变量名只能含字母/数字/下划线/连字符(≤64): "
                                + a.var() + " @" + where);
                        continue;
                    }
                    if (a.from() != null && !a.from().isBlank()
                            && !EdgeDef.EdgeAssign.KNOWN_FROMS.contains(a.from())) {
                        errors.add("assigns 的 from 未知(允许 output): " + a.from() + " @" + where);
                        continue;
                    }
                    writeSources.computeIfAbsent(a.var(), k -> new LinkedHashSet<>()).add(e.from());
                    assignSources.computeIfAbsent(a.var(), k -> new LinkedHashSet<>()).add(e.from());
                }
            }
            if (e.input() != null) {
                EdgeDef.EdgeInput in = e.input();
                if (in.template() == null || in.template().isBlank()) {
                    errors.add("边 input 模板不能为空: " + where);
                } else if (in.var() != null && !in.var().isBlank()
                        && !SAFE_VAR_NAME.matcher(in.var()).matches()) {
                    errors.add("边 input 变量名只能含字母/数字/下划线/连字符(≤64): "
                            + in.var() + " @" + where);
                } else {
                    writeSources.computeIfAbsent(in.effectiveVar(), k -> new LinkedHashSet<>()).add(e.from());
                }
            }
        }
        for (String var : writeSources.keySet()) {
            if (userVars.contains(var)) {
                errors.add("边写入变量与用户输入变量同名: " + var + "(请改名其一,解析优先级为边写入值优先)");
            }
        }
        for (Map.Entry<String, Set<String>> en : assignSources.entrySet()) {
            if (en.getValue().size() > 1) {
                errors.add("assigns 变量 " + en.getKey() + " 被多个不同源节点的出边写入("
                        + String.join(",", en.getValue())
                        + "),并行竞写无静态时序——请收敛到同一源节点或拆分变量名");
            }
        }
        return writeSources;
    }

    private static void validateEdges(Definition d, Set<String> nodeIds, List<String> errors) {
        Set<String> edgeIds = new LinkedHashSet<>();
        for (EdgeDef e : d.edgesOrEmpty()) {
            if (e == null) {
                errors.add("edges 含空项");
                continue;
            }
            if (e.id() == null || e.id().isBlank()) {
                errors.add("边 id 不能为空: " + e.from() + "->" + e.to());
                continue;
            }
            if (!edgeIds.add(e.id())) {
                errors.add("边 id 重复: " + e.id());
                continue;
            }
            if (e.from() == null || e.from().isBlank()) {
                errors.add("边 from 不能为空: " + e.id());
            } else if (!nodeIds.contains(e.from())) {
                errors.add("边引用的节点不存在(from): " + e.from() + " @" + e.id());
            }
            if (e.to() == null || e.to().isBlank()) {
                errors.add("边 to 不能为空: " + e.id());
            } else if (!nodeIds.contains(e.to())) {
                errors.add("边引用的节点不存在(to): " + e.to() + " @" + e.id());
            }
            validateCondition(e, errors);
        }
    }

    private static void validateCondition(EdgeDef e, List<String> errors) {
        ConditionDef c = e.condition();
        if (c == null) {
            return; // 缺省 = always
        }
        if (c.type() == null || c.type().isBlank()) {
            errors.add("condition.type 不能为空: " + e.id());
            return;
        }
        if (!ConditionDef.KNOWN_TYPES.contains(c.type())) {
            errors.add("condition.type 未知(允许 " + ConditionDef.KNOWN_TYPES + "): " + c.type() + " @" + e.id());
            return;
        }
        switch (c.type()) {
            case ConditionDef.EXIT_CODE -> {
                if (c.value() == null) {
                    errors.add("exitCode 条件缺少 value: @" + e.id());
                }
            }
            case ConditionDef.OUTPUT_CONTAINS, ConditionDef.OUTPUT_NOT_CONTAINS, ConditionDef.OUTPUT_EQUALS -> {
                if (c.text() == null || c.text().isEmpty()) {
                    errors.add(c.type() + " 条件缺少 text: @" + e.id());
                }
            }
            case ConditionDef.OUTPUT_MATCHES -> {
                if (c.pattern() == null || c.pattern().isEmpty()) {
                    errors.add("outputMatches 条件缺少 pattern: @" + e.id());
                } else {
                    try {
                        Pattern.compile(c.pattern());
                    } catch (PatternSyntaxException pse) {
                        errors.add("outputMatches 正则不可编译: " + pse.getDescription() + " @" + e.id());
                    }
                }
            }
            default -> {
                // always / onSuccess / onError：无参数要求
            }
        }
    }

    /**
     * {@code {{var}}} 引用校验（command / input / 边 input 模板三处扫描）：
     * 引用必须「已声明」——用户输入变量或某条边的写入变量（assigns var ∪ 边 input
     * 变量）；花括号配对 lint 同口径。边写入变量另加<b>可达性</b>：写入边源节点须在
     * 引用方上游闭包内（节点模板：该节点的上游；边模板：from 节点的上游 ∪ from 自身
     * ——模板在 from 完成时渲染，值须已就绪）。不可达 = 运行时序不确定的空值
     * （并行分支竞速），创建期即拒而非静默空串。
     */
    private static void validateVarRefs(Definition d, Map<String, Set<String>> writeSources,
            List<String> errors) {
        Set<String> declared = new LinkedHashSet<>();
        for (VariableDef v : d.variablesOrEmpty()) {
            if (v != null && v.name() != null) {
                declared.add(v.name());
            }
        }
        declared.addAll(writeSources.keySet());
        for (NodeDef n : d.nodesOrEmpty()) {
            if (n == null) {
                continue;
            }
            Set<String> upstream = NodeRunners.upstreamNodeIds(d, n.id());
            checkRefs(n.command(), n.id(), "command", declared, writeSources, upstream, errors);
            checkRefs(n.input(), n.id(), "input", declared, writeSources, upstream, errors);
        }
        for (EdgeDef e : d.edgesOrEmpty()) {
            if (e == null || e.input() == null || e.input().template() == null
                    || e.from() == null || e.from().isBlank()) {
                continue;
            }
            // 边模板的可达集 = from 的上游 ∪ {from}（from 自身的出边 assigns 先于模板渲染）
            Set<String> allowed = new LinkedHashSet<>(NodeRunners.upstreamNodeIds(d, e.from()));
            allowed.add(e.from());
            checkRefs(e.input().template(), e.id(), "边 input 模板", declared, writeSources,
                    allowed, errors);
        }
    }

    private static void checkRefs(String text, String nodeId, String field, Set<String> declared,
            Map<String, Set<String>> writeSources, Set<String> reachableSources, List<String> errors) {
        if (text == null || text.isEmpty()) {
            return;
        }
        java.util.regex.Matcher m = VAR_REF.matcher(text);
        while (m.find()) {
            String ref = m.group(1);
            if (!declared.contains(ref)) {
                errors.add("{{" + ref + "}} 引用了未声明的变量 @" + nodeId + " " + field);
                continue;
            }
            Set<String> sources = writeSources.get(ref);
            if (sources != null && reachableSources.stream().noneMatch(sources::contains)) {
                errors.add("{{" + ref + "}} 的写入边不在引用方上游(写入源: "
                        + String.join(",", sources) + ",引用 @" + nodeId + " " + field
                        + ")——运行时序不定会渲染为空,请调整图结构或改用用户输入变量");
            }
        }
        // 花括号配对 lint：挖掉合法 {{name}} 引用后仍残留 {{ = 引用写法不完整
        // (如 {{topic} 缺右花括号)——旧实现静默按字面量输出,拼错只能靠读产物文件发现
        if (m.replaceAll("").contains("{{")) {
            errors.add(field + " 含未配对的 {{ (变量引用须写全 {{name}};不配对会按字面量输出)"
                    + " @" + nodeId);
        }
    }

    /** 节点显示名（错误定位用）：name 缺省回退 id；name 与 id 不同时并注 id。 */
    private static String nodeLabel(NodeDef n) {
        String name = n.name() == null || n.name().isBlank() ? n.id() : n.name();
        return name.equals(n.id()) ? name : name + "(" + n.id() + ")";
    }

    /** 大小写不敏感判保留名（Windows 文件系统大小写不敏感，同名异体同样有冲突风险）。 */
    public static boolean isReserved(String agentName) {
        for (String r : RESERVED_AGENT_NAMES) {
            if (r.equalsIgnoreCase(agentName)) {
                return true;
            }
        }
        return false;
    }

    /** agent 名文件名安全（ASCII 字母/数字/_-.，不以点开头/结尾，长度 ≤64，非空）。 */
    private static boolean safeAgentName(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) {
            return false;
        }
        char first = name.charAt(0);
        char last = name.charAt(name.length() - 1);
        if (!isNameChar(first) || first == '.' || !isNameChar(last) || last == '.') {
            return false;
        }
        for (int i = 1; i < name.length() - 1; i++) {
            if (!isNameChar(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.';
    }

    /**
     * 保存前规范化（校验通过后调用）：onError 缺省 supervisor；schemaVersion 归一为
     * 当前版本；null 列表归空表；agentConfigId 空白归 null。不改动 id/时间戳/工作区。
     */
    public static Definition normalize(Definition d) {
        OnError onError = d.onError() == null ? OnError.SUPERVISOR : d.onError();
        return new Definition(
                Definition.SCHEMA_VERSION,
                d.id(),
                d.name(),
                d.description(),
                d.workspaceId(),
                d.createdAt(),
                d.updatedAt(),
                d.variables() == null ? List.of() : List.copyOf(d.variables()),
                d.agentConfigId() == null || d.agentConfigId().isBlank() ? null : d.agentConfigId().trim(),
                onError,
                d.supervisor(),
                d.nodes() == null ? List.of() : List.copyOf(d.nodes()),
                d.edges() == null ? List.of() : List.copyOf(d.edges()));
    }
}
