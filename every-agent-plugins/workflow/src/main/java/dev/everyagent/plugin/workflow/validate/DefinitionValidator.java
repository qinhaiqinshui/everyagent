package dev.everyagent.plugin.workflow.validate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import dev.everyagent.plugin.workflow.model.AgentDef;
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
 *   <li>command / input 中 {@code {{var}}} 引用的变量必须已声明（渲染范围仅此两字段）；</li>
 *   <li>agent 名安全（文件名安全字符集）且不撞保留名 meta/events/runs/agents/supervisor
 *       ——agents/ 子目录已避免文件冲突，但 supervisor 保留给督导会话线；</li>
 *   <li>循环允许（不校验环——maxRuns 是运行期兜底）；</li>
 *   <li>outputMatches 正则可编译；exitCode/outputContains 等条件带齐各自参数；</li>
 *   <li>变量名唯一且为 [A-Za-z0-9_-]+（渲染替换的键安全）。</li>
 * </ol>
 *
 * <p>{@link #normalize} 在校验通过后调用：节点引用的 agent 名自动合并进 agents[]
 * （引用即声明，未显式声明的补默认模型 null）、onError 缺省 supervisor、
 * schemaVersion 归一、null 列表归空表。<b>不分配 id/时间戳</b>——那是 WorkflowStore 的职责。
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
        Map<String, AgentDef> declaredAgents = validateAgents(d, errors);
        Set<String> nodeIds = validateNodes(d, errors);
        validateEdges(d, nodeIds, errors);
        validateVarRefs(d, errors);
        // agent 引用即声明：节点引用未声明的名字不是错误（normalize 合并），
        // 但引用名本身要过安全/保留名检查——已在 validateNodes 内对每个引用名校验。
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

    /** 校验已声明的 agents，返回 name → AgentDef（声明顺序保持）。 */
    private static Map<String, AgentDef> validateAgents(Definition d, List<String> errors) {
        Map<String, AgentDef> byName = new LinkedHashMap<>();
        for (AgentDef a : d.agentsOrEmpty()) {
            if (a == null || a.name() == null || a.name().isBlank()) {
                errors.add("agents 含空项或 name 为空");
                continue;
            }
            if (!safeAgentName(a.name())) {
                errors.add("agent 名不安全(仅字母/数字/_-.且不以点开头结尾,≤64): " + a.name());
                continue;
            }
            if (isReserved(a.name())) {
                errors.add("agent 名撞保留名(不可用 meta/events/runs/agents/supervisor): " + a.name());
                continue;
            }
            if (byName.put(a.name(), a) != null) {
                errors.add("agent 名重复: " + a.name());
            }
        }
        return byName;
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
            if (n.timeoutMs() != null && n.timeoutMs() <= 0) {
                errors.add("timeoutMs 必须 > 0: " + n.timeoutMs() + " @" + where);
            }
            if (NodeDef.TYPE_COMMAND.equals(n.type()) && (n.command() == null || n.command().isBlank())) {
                errors.add("command 节点缺少 command: " + where);
            }
            if (n.agent() != null && !n.agent().isBlank()) {
                // 引用即声明：不要求已声明，但名字本身要安全且不撞保留名
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
            case ConditionDef.OUTPUT_CONTAINS, ConditionDef.OUTPUT_NOT_CONTAINS -> {
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

    /** command / input 中 {{var}} 引用必须已声明。 */
    private static void validateVarRefs(Definition d, List<String> errors) {
        Set<String> declared = new LinkedHashSet<>();
        for (VariableDef v : d.variablesOrEmpty()) {
            if (v != null && v.name() != null) {
                declared.add(v.name());
            }
        }
        for (NodeDef n : d.nodesOrEmpty()) {
            if (n == null) {
                continue;
            }
            checkRefs(n.command(), n.id(), "command", declared, errors);
            checkRefs(n.input(), n.id(), "input", declared, errors);
        }
    }

    private static void checkRefs(String text, String nodeId, String field, Set<String> declared,
            List<String> errors) {
        if (text == null || text.isEmpty()) {
            return;
        }
        java.util.regex.Matcher m = VAR_REF.matcher(text);
        while (m.find()) {
            String ref = m.group(1);
            if (!declared.contains(ref)) {
                errors.add("{{" + ref + "}} 引用了未声明的变量 @" + nodeId + " " + field);
            }
        }
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
     * 保存前规范化（校验通过后调用）：
     * 节点引用的 agent 名自动合并进 agents[]（引用即声明，未显式声明的补默认模型 null，
     * 追加在声明列表之后、按首次引用顺序）；onError 缺省 supervisor；
     * schemaVersion 归一为当前版本；null 列表归空表。不改动 id/时间戳/工作区。
     */
    public static Definition normalize(Definition d) {
        List<AgentDef> declared = new ArrayList<>(d.agentsOrEmpty());
        Set<String> known = new LinkedHashSet<>();
        for (AgentDef a : declared) {
            if (a != null && a.name() != null) {
                known.add(a.name());
            }
        }
        for (NodeDef n : d.nodesOrEmpty()) {
            if (n != null && n.agent() != null && !n.agent().isBlank() && known.add(n.agent())) {
                declared.add(new AgentDef(n.agent(), null, null));
            }
        }
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
                onError,
                d.supervisor(),
                List.copyOf(declared),
                d.nodes() == null ? List.of() : List.copyOf(d.nodes()),
                d.edges() == null ? List.of() : List.copyOf(d.edges()));
    }
}
