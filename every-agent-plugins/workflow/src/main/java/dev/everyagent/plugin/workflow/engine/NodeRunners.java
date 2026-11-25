package dev.everyagent.plugin.workflow.engine;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.agent.Agent;
import dev.everyagent.plugin.api.agent.AgentBuilder;
import dev.everyagent.plugin.api.agent.ConversationArchive;
import dev.everyagent.plugin.api.event.Usage;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.plugin.api.spi.ExecResult;
import dev.everyagent.plugin.api.util.RootCause;
import dev.everyagent.plugin.workflow.model.ConditionDef;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.EdgeDef;
import dev.everyagent.plugin.workflow.model.NodeDef;
import dev.everyagent.plugin.workflow.model.RunRecord;
import dev.everyagent.plugin.workflow.model.VariableDef;

import org.springframework.ai.chat.messages.Message;

/**
 * 节点执行器两件套：命令节点（NativeExec 直跑）/ AI 节点（照搬子 agent 插件模式，经
 * origin 任务 ExecContext 的 agentFactory 装配，事件单写任务域，§7.20.8）。
 *
 * <p>执行器只负责「把一个节点跑出结果」，调度/落盘/事件/错误策略收口在 {@link WorkflowEngine}
 * ——本类无状态、不持实例内存（锁由引擎传入）。
 *
 * <p><b>已删除</b>：SupervisorRunner（独立督导会话）随 fail-fast 改造整体移除
 * （supervisor 就是主 agent，§7.20）；{@link Decision}/{@link #parseDecision}/
 * {@link #buildErrorContext} 保留——决策内核服务 standalone askUser 的 decide RPC 与
 * workflow_rerun（retryWithInput 语义）。
 */
public final class NodeRunners {

    private static final Logger log = LoggerFactory.getLogger(NodeRunners.class);

    /** 命令节点缺省超时（节点未配置 timeoutMs 时）。 */
    public static final long DEFAULT_COMMAND_TIMEOUT_MS = 120_000;

    private static final Pattern VAR_REF = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_-]+)\\s*\\}\\}");

    private NodeRunners() {
    }

    // ══════════════════════════ 结果与决策载体 ══════════════════════════

    /**
     * 节点执行结果：exitCode（AI 节点正常完成视为 0）+ 条件求值用输出（命令=stdout+stderr、
     * AI=最终回复文本）+ runs 文件留档形态 + 命名 agent 观测（匿名为 null）。
     */
    public record NodeResult(
            int exitCode,
            String conditionOutput,
            RunRecord.RunOutput output,
            RunRecord.AgentTrace agentTrace) {
    }

    /** 决策协议（四 action；input 仅 retryWithInput 携带）。askUser 挂起的 decide RPC 与 rerun 共用。 */
    public record Decision(String action, String input, String reason) {

        public static final String RETRY = "retry";
        public static final String SKIP = "skip";
        public static final String FAIL = "fail";
        public static final String RETRY_WITH_INPUT = "retryWithInput";

        public boolean validAction() {
            return RETRY.equals(action) || SKIP.equals(action) || FAIL.equals(action)
                    || RETRY_WITH_INPUT.equals(action);
        }
    }

    // ══════════════════════════ 变量渲染 ══════════════════════════

    /**
     * {@code {{var}}} 渲染（仅 command/input 两字段调用本方法）：值原样替换、不递归
     * （值里再含 {{}} 不二次展开）；非 required 未提供且无默认 → 空串。
     */
    public static String render(String template, Map<String, String> variables,
            List<VariableDef> declarations) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher m = VAR_REF.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = resolveVariable(name, variables, declarations);
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String resolveVariable(String name, Map<String, String> variables,
            List<VariableDef> declarations) {
        if (variables != null && variables.containsKey(name)) {
            return variables.get(name);
        }
        if (declarations != null) {
            for (VariableDef v : declarations) {
                if (v != null && name.equals(v.name()) && v.defaultValue() != null) {
                    return v.defaultValue();
                }
            }
        }
        return "";
    }

    // ══════════════════════════ 出边条件求值 ══════════════════════════

    /**
     * 七种出边条件求值（作用于节点完成态 {exitCode, output}；condition null = always）。
     * 节点完成后所有满足条件的出边全部激活（全激活语义），由引擎逐边调用。
     */
    public static boolean evaluate(ConditionDef c, int exitCode, String output) {
        if (c == null || c.type() == null || ConditionDef.ALWAYS.equals(c.type())) {
            return true;
        }
        String out = output == null ? "" : output;
        switch (c.type()) {
            case ConditionDef.ON_SUCCESS:
                return exitCode == 0;
            case ConditionDef.ON_ERROR:
                return exitCode != 0;
            case ConditionDef.EXIT_CODE:
                return c.value() != null && c.value() == exitCode;
            case ConditionDef.OUTPUT_CONTAINS:
                return c.text() != null && out.contains(c.text());
            case ConditionDef.OUTPUT_NOT_CONTAINS:
                return c.text() != null && !out.contains(c.text());
            case ConditionDef.OUTPUT_MATCHES:
                return c.pattern() != null && out.matches(c.pattern());
            default:
                log.warn("[workflow] 未知条件类型按不满足处理: {}", c.type());
                return false;
        }
    }

    // ══════════════════════════ 命令节点 ══════════════════════════

    /**
     * 命令节点执行器：{@code services().nativeExec()} 直跑宿主（不经 PermissionGate/沙箱
     * 后端，与既有纪律一致）；Windows 默认经
     * {@code services().nativeExec().spawnPowerShell}（worker 核心共享管线，与会话
     * powershell 工具同一实现、行为完全一致），宿主无法解析 PowerShell 可执行文件（抛
     * {@link IllegalStateException}）时回退 {@code cmd.exe /c} 旧路径；Unix
     * {@code /bin/sh -c}；cwd=工作区根；stdout/stderr 全量入 runs 文件（返回值交引擎
     * 留档）。超时/中止视为节点失败抛出。
     */
    public static final class CommandNodeRunner {

        CommandNodeRunner() {
        }

        public NodeResult run(WorkerServices services, String workspaceRoot, NodeDef node,
                String renderedCommand) {
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            long timeoutMs = node.timeoutMs() != null && node.timeoutMs() > 0
                    ? node.timeoutMs() : DEFAULT_COMMAND_TIMEOUT_MS;
            ExecResult r;
            if (windows) {
                // Windows 默认经 PowerShell：复用 worker 核心共享管线（临时 .ps1(UTF-8 BOM)
                // + 宿主解析 pwsh 优先/回退 powershell.exe + stdout/stderr 文件承载 +
                // CLIXML/ANSI 还原 + 退出码传导），与会话 powershell 工具行为完全一致；
                // 宿主无 PowerShell（共享实现抛 IllegalStateException）→ 回退 cmd.exe /c
                try {
                    r = services.nativeExec().spawnPowerShell(renderedCommand,
                            Path.of(workspaceRoot), Map.of(), timeoutMs);
                } catch (IllegalStateException e) {
                    log.warn("[workflow] 宿主无法解析 PowerShell,命令节点回退 cmd.exe: {}",
                            e.getMessage());
                    r = services.nativeExec().spawnNative(
                            new String[]{"cmd.exe", "/c", renderedCommand},
                            Path.of(workspaceRoot), Map.of(), timeoutMs);
                }
            } else {
                r = services.nativeExec().spawnNative(
                        new String[]{"/bin/sh", "-c", renderedCommand},
                        Path.of(workspaceRoot), Map.of(), timeoutMs);
            }
            if (r == null) {
                throw new IllegalStateException("命令执行返回空结果: " + node.name());
            }
            if (r.aborted()) {
                throw new IllegalStateException("命令超时/被中止(" + timeoutMs + "ms): " + node.name());
            }
            String stdout = r.stdout() == null ? "" : r.stdout();
            String stderr = r.stderr() == null ? "" : r.stderr();
            return new NodeResult(r.exitCode(), stdout + stderr,
                    RunRecord.RunOutput.command(r.exitCode(), stdout, stderr), null);
        }
    }

    // ══════════════════════════ 任务绑定守卫 ══════════════════════════

    /** 无/失效任务绑定时 AI 会话的拒绝文案（引擎 start 与节点运行共用一条口径）。 */
    static final String NO_TASK_BINDING_MESSAGE =
            "工作流 AI 会话必须运行在任务内(实例未绑定 origin 任务,或绑定任务的事件口已失效): "
            + "请经任务运行工作流(在任务中触发运行,使 AI 节点作为任务子 agent 落任务事件流); "
            + "workflow.run RPC 直启不支持含 AI 节点的工作流";

    /** 绑定是否可用：非 null、事件口在场且主体未收口（emitterOf 语义，终态=null 等价）。 */
    static boolean usableTaskBinding(ExecContext taskExec) {
        return taskExec != null && taskExec.emitter() != null && !taskExec.terminal();
    }

    /** 节点级守卫：绑定不可用即抛（走节点失败路径，错误信息即 {@link #NO_TASK_BINDING_MESSAGE}）。 */
    static void requireTaskBinding(ExecContext taskExec) {
        if (!usableTaskBinding(taskExec)) {
            throw new IllegalStateException(NO_TASK_BINDING_MESSAGE);
        }
    }

    // ══════════════════════════ 会话线上游解析（与前端编辑器扫描同语义） ══════════════════════════

    /**
     * 沿边反向收集 {@code nodeId} 的可达祖先（不含自身；防环用 visited）。
     * 工作流允许环（maxRuns 兜底），反向遍历按 visited 截断即可。
     */
    public static Set<String> upstreamNodeIds(Definition def, String nodeId) {
        Map<String, List<String>> incoming = new HashMap<>();
        for (EdgeDef e : def.edgesOrEmpty()) {
            if (e != null && e.from() != null && e.to() != null) {
                incoming.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e.from());
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(incoming.getOrDefault(nodeId, List.of()));
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            if (!seen.add(cur)) {
                continue;
            }
            for (String prev : incoming.getOrDefault(cur, List.of())) {
                stack.push(prev);
            }
        }
        return seen;
    }

    /**
     * 节点在定义节点表中的顺序（上游子集的近似拓扑序）：Kahn 归约，同层按
     * {@code def.nodes} 列表序平局决胜；环内残留节点按列表序附加在尾部
     * （工作流允许环——环上「定义节点」取列表序首个，语义近似即可）。
     */
    private static List<NodeDef> topoOrderWithin(Definition def, Set<String> subset) {
        Map<String, Integer> index = new HashMap<>();
        List<NodeDef> subsetNodes = new ArrayList<>();
        for (NodeDef n : def.nodesOrEmpty()) {
            if (n != null && n.id() != null && subset.contains(n.id())) {
                index.put(n.id(), subsetNodes.size());
                subsetNodes.add(n);
            }
        }
        Map<String, Integer> indegree = new HashMap<>();
        for (NodeDef n : subsetNodes) {
            indegree.put(n.id(), 0);
        }
        List<EdgeDef> innerEdges = new ArrayList<>();
        for (EdgeDef e : def.edgesOrEmpty()) {
            if (e != null && e.from() != null && e.to() != null
                    && indegree.containsKey(e.from()) && indegree.containsKey(e.to())) {
                indegree.merge(e.to(), 1, Integer::sum);
                innerEdges.add(e);
            }
        }
        Comparator<String> byIndex = Comparator.comparingInt(index::get);
        PriorityQueue<String> ready = new PriorityQueue<>(byIndex);
        indegree.forEach((id, deg) -> {
            if (deg == 0) {
                ready.add(id);
            }
        });
        Map<String, List<String>> outgoing = new HashMap<>();
        for (EdgeDef e : innerEdges) {
            outgoing.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
        }
        Set<String> emitted = new LinkedHashSet<>();
        List<NodeDef> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            if (!emitted.add(id)) {
                continue;
            }
            order.add(subsetNodes.get(index.get(id)));
            for (String next : outgoing.getOrDefault(id, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        for (NodeDef n : subsetNodes) {
            if (!emitted.contains(n.id())) {
                order.add(n);
            }
        }
        return order;
    }

    /**
     * 上游 AI 节点已用的会话线名 → 定义节点（拓扑序最早的持有者 = 「新建那条会话线」
     * 的节点）。编辑器下拉候选与运行时 systemPrompt 解析共用本语义；空 agent 名跳过。
     */
    public static Map<String, NodeDef> upstreamAgentDefinitions(Definition def, String nodeId) {
        Set<String> upstream = upstreamNodeIds(def, nodeId);
        Map<String, NodeDef> byName = new LinkedHashMap<>();
        if (upstream.isEmpty()) {
            return byName;
        }
        for (NodeDef n : topoOrderWithin(def, upstream)) {
            if (NodeDef.TYPE_AI.equals(n.type())
                    && n.agent() != null && !n.agent().isBlank()) {
                byName.putIfAbsent(n.agent(), n);
            }
        }
        return byName;
    }

    /**
     * AI 节点的生效 systemPrompt：节点自带值优先（= 定义节点）；引用节点（自带为空）
     * 沿上游解析定义节点的值——单一事实源是定义节点，引用节点不存副本、改定义即全线下游生效。
     */
    public static String effectiveSystemPrompt(Definition def, NodeDef node) {
        if (node.systemPrompt() != null && !node.systemPrompt().isBlank()) {
            return node.systemPrompt();
        }
        if (node.agent() == null || node.agent().isBlank()) {
            return null;
        }
        NodeDef defining = upstreamAgentDefinitions(def, node.id()).get(node.agent());
        return defining == null ? null : defining.systemPrompt();
    }

    // ══════════════════════════ AI 节点 ══════════════════════════

    /**
     * AI 节点执行器——照搬 SubAgentManager 的子 agent 模式（AI 节点 = origin 任务里的
     * 子 agent，§7.20.8）：
     * ①持 agent fair 锁 → ②经任务 {@code conversationArchive} 载种子（命名 agent；
     * 含交替不变量补齐）→ ③经任务 emitter 发 user.message 指令轮（agentId=
     * {@code <name>#<wi>}，落 {@code tasks/<taskId>/<agentId>.jsonl}，文件自包含）→
     * ④{@code taskExec.agentFactory().create(agentKey, configId)}
     * .systemPrompt(节点自带或上游定义节点解析).conversation(种子).userInput(渲染后输入)
     * .tools(剔除表, REMOVE).creator("workflow").build()
     * → ⑤run()（事件实时单写任务 EventLog——瞬态 thinking/delta 与持久 user.message/
     * message/tool.result/usage 全走任务事件口，实例目录不落 agent 会话）→
     * ⑥lastText()/conversation()/usage() 收口交引擎；锁在 finally 释放（锁粒度=整个 run）。
     *
     * <p>匿名 AI 节点（agent 未指定，仅旧定义兼容通道——新校验已强制必填）= 一次性会话：
     * 无种子、agentId={@code <nodeId>.<runNo>}（不复用）；指令轮照发（任务页可见、档案自包含）。
     * 无任务绑定的 AI 节点运行拒绝（{@link #requireTaskBinding}——fail-fast，引导经任务运行）。
     */
    public static final class AiNodeRunner {

        AiNodeRunner() {
        }

        /**
         * @param snapshot   实例定义快照（引用节点 systemPrompt 沿上游解析）
         * @param agentLock  命名 agent 的 fair 锁（匿名节点传 null）
         * @param visibleToAi token 拍平后的输入（AI 实际所见）
         * @param rendered    变量渲染后的原文（opaque token 原串留档）
         */
        public NodeResult run(ExecContext taskExec, String instanceId,
                Definition snapshot, NodeDef node, int runNo, String rendered, String visibleToAi,
                ReentrantLock agentLock) throws InterruptedException {
            requireTaskBinding(taskExec);
            if (agentLock != null) {
                agentLock.lockInterruptibly();
            }
            try {
                String agentName = node.agent() == null || node.agent().isBlank()
                        ? null : node.agent();
                // 模型是节点自带配置；系统提示节点自带优先、引用节点沿上游解析定义节点值
                //（agents 总表已删除，旧定义声明值在解析口迁移到节点，见 DefinitionJson）
                String configId = node.modelConfigId();
                String systemPrompt = effectiveSystemPrompt(snapshot, node);
                String agentKey = agentName != null
                        ? agentName + "#" + instanceId
                        : node.id() + "." + runNo;

                // ① 载种子（任务档案 → 全保真会话；匿名一次性无种子；档案口缺失/读失败空表不抛）
                List<Message> seed = List.of();
                if (agentName != null) {
                    seed = AgentSessions.ensureAlternation(loadArchive(taskExec, agentKey));
                }

                // ② 指令轮经任务事件口落盘（user.message 带归户 agentId——任务档案自包含，续跑重建依据）
                emitInstruction(taskExec, agentKey, visibleToAi, rendered);

                // ③ 装配：任务 ExecContext 的 agentFactory（每 run 新建实体，连续性由任务档案保证）
                AgentBuilder builder = taskExec.agentFactory()
                        .create(agentKey, configId)
                        .title(node.name() == null ? node.id() : node.name())
                        .creator("workflow")
                        .tools(ExcludedTools.toRemove(), AgentBuilder.ModifyMode.REMOVE);
                if (systemPrompt != null && !systemPrompt.isBlank()) {
                    builder.systemPrompt(systemPrompt);
                }
                if (!seed.isEmpty()) {
                    builder.conversation(seed);
                }
                builder.userInput(visibleToAi);
                Agent agent = builder.build();

                // ④ run（同步阻塞本虚拟线程；事件经 agent 级 emitter 单写任务 EventLog）
                try {
                    agent.run();
                    agent.claimTerminal("completed");
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    agent.claimTerminal("stopped", "已取消");
                    throw ie;
                } catch (Throwable t) {
                    agent.claimTerminal("error", RootCause.summary(t));
                    throw t;
                } finally {
                    agent.finished(true);
                }

                // ⑤⑥ 收口：lastText/conversation/usage（普通字段，不依赖事件通道）
                String text = agent.lastText() == null ? "" : agent.lastText();
                Usage u = agent.usage() == null ? Usage.zero() : agent.usage();
                RunRecord.AgentTrace trace = agentName == null ? null : new RunRecord.AgentTrace(
                        agentName, configId, seed.size(), agent.conversation().size(),
                        new RunRecord.Usage(u.inputTokens(), u.outputTokens()));
                return new NodeResult(0, text, RunRecord.RunOutput.ai(text), trace);
            } finally {
                if (agentLock != null) {
                    agentLock.unlock();
                }
            }
        }
    }

    // ══════════════════════════ 共用 ══════════════════════════

    /** 经任务档案口读 agent 会话档案（未绑定/端口缺失/IO 失败返回空表，不抛）。 */
    private static List<Message> loadArchive(ExecContext taskExec, String agentId) {
        ConversationArchive archive = taskExec.conversationArchive();
        if (archive == null) {
            return List.of();
        }
        try {
            List<Message> prior = archive.load(agentId);
            return prior != null ? prior : List.of();
        } catch (RuntimeException e) {
            log.warn("[workflow] 会话档案读取失败(按无历史处理) agent={} subject={}",
                    agentId, taskExec.subjectId(), e);
            return List.of();
        }
    }

    /** 发 user.message 指令轮（经任务事件口，带归户 agentId；rawContent 留档 opaque token 原串）。 */
    static void emitInstruction(ExecContext taskExec, String agentKey, String visibleToAi,
            String rawRendered) {
        try {
            taskExec.emitter().emit(EmitEvent.of(SnowflakeId.next(), Events.USER_MESSAGE,
                    agentKey, null, null, visibleToAi, null,
                    Map.of("rawContent", rawRendered == null ? "" : rawRendered),
                    EmitEvent.Mode.REPLACE));
        } catch (RuntimeException e) {
            log.warn("[workflow] 指令轮落盘失败 agent={} subject={}", agentKey,
                    taskExec.subjectId(), e);
        }
    }

    /**
     * 决策文本解析（rerun/主 agent 决策输入用）：剥 ```json 围栏与前后杂文本，取首个 '{' 到末个 '}'
     * 的片段解析 {action, input?, reason?}；action 非四值之一/无 JSON 片段 → null。
     */
    public static Decision parseDecision(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            var node = Json.parse(output.substring(start, end + 1));
            String action = node.path("action").asString(null);
            if (action == null || action.isBlank()) {
                return null;
            }
            Decision d = new Decision(action.trim(),
                    node.path("input").asString(null),
                    node.path("reason").asString(null));
            return d.validAction() ? d : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 结构化错误上下文（workflow_run 工具失败结果/askUser 挂起 context 同源）：
     * 定义名/节点名/nodeId/runNo/错误消息/历史输出摘要/此前介入决策链/剩余次数。
     */
    public static String buildErrorContext(String definitionName, NodeDef node, int runNo,
            String errorMessage, List<String> historyOutputPreviews, List<String> priorDecisions,
            int remainingAttempts) {
        StringBuilder sb = new StringBuilder("工作流节点执行失败,请诊断并决策。\n");
        sb.append("- 工作流: ").append(definitionName == null ? "(未命名)" : definitionName).append('\n');
        sb.append("- 节点: ").append(node.name() == null ? node.id() : node.name())
                .append(" (nodeId=").append(node.id()).append(", type=").append(node.type())
                .append(", 第 ").append(runNo).append(" 次运行)\n");
        sb.append("- 错误: ").append(errorMessage == null ? "(无)" : errorMessage).append('\n');
        sb.append("- 该节点此前运行输出摘要:\n");
        if (historyOutputPreviews == null || historyOutputPreviews.isEmpty()) {
            sb.append("  (无)\n");
        } else {
            for (int i = 0; i < historyOutputPreviews.size(); i++) {
                sb.append("  #").append(i + 1).append(": ")
                        .append(historyOutputPreviews.get(i)).append('\n');
            }
        }
        sb.append("- 此前介入决策链:\n");
        if (priorDecisions == null || priorDecisions.isEmpty()) {
            sb.append("  (无)\n");
        } else {
            priorDecisions.forEach(d -> sb.append("  - ").append(d).append('\n'));
        }
        sb.append("- 剩余介入次数: ").append(remainingAttempts).append('\n');
        sb.append("请只输出 JSON: {\"action\":\"retry|skip|fail|retryWithInput\",")
                .append("\"input\":\"(retryWithInput 时必填)\",\"reason\":\"诊断根因\"}");
        return sb.toString();
    }
}
