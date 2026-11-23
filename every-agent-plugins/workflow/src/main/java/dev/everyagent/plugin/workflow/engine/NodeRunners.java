package dev.everyagent.plugin.workflow.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.agent.Agent;
import dev.everyagent.plugin.api.agent.AgentBuilder;
import dev.everyagent.plugin.api.event.Usage;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.plugin.api.spi.ExecResult;
import dev.everyagent.plugin.api.util.RootCause;
import dev.everyagent.plugin.workflow.engine.WorkflowRuntime.NodeExecView;
import dev.everyagent.plugin.workflow.model.AgentDef;
import dev.everyagent.plugin.workflow.model.ConditionDef;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.NodeDef;
import dev.everyagent.plugin.workflow.model.RunRecord;
import dev.everyagent.plugin.workflow.model.VariableDef;

import org.springframework.ai.chat.messages.Message;

/**
 * 节点执行器三件套：命令节点（NativeExec 直跑）/ AI 节点（严格按「会话时序 ①-⑥」
 * 走 agentFactoryFor 装配链）/ supervisor 节点（错误策略督导决策协议）。
 *
 * <p>执行器只负责「把一个节点跑出结果」，调度/落盘/事件/错误策略收口在 {@link WorkflowEngine}
 * ——本类无状态、不持实例内存（锁由引擎传入）。
 */
public final class NodeRunners {

    private static final Logger log = LoggerFactory.getLogger(NodeRunners.class);

    /** 命令节点缺省超时（节点未配置 timeoutMs 时）。 */
    public static final long DEFAULT_COMMAND_TIMEOUT_MS = 120_000;

    /** 每节点 supervisor 介入上限（超过即 fail）。 */
    public static final int SUPERVISOR_MAX_ATTEMPTS = 3;

    /** supervisor 决策 JSON 解析失败的重试次数（再失败降级 askUser）。 */
    public static final int SUPERVISOR_PARSE_RETRIES = 1;

    /** supervisor 缺省系统提示词（定义级 supervisor.systemPrompt 为空时使用）。 */
    public static final String DEFAULT_SUPERVISOR_PROMPT = "你是工作流督导 agent。工作流节点执行失败时会向你提供结构化错误上下文,"
            + "你负责诊断根因并给出处置决策。只输出纯 JSON,不要输出任何其他文本,格式:"
            + "{\"action\":\"retry|skip|fail|retryWithInput\",\"input\":\"...\",\"reason\":\"...\"}。"
            + "action 语义:retry=用原输入重试该节点;retryWithInput=用 input 字段替换节点输入后重试"
            + "(此时 input 必填);skip=跳过该节点继续下游;fail=终止整个工作流实例。"
            + "先在 reason 字段诊断根因再决策;保持保守:仅当问题看起来是可重试的瞬时问题时才 retry,"
            + "反复失败优先 skip 或 fail。";

    /** 督导 agent 固定名（保留名,与全部工作 agent 隔离的自己一条会话线）。 */
    public static final String SUPERVISOR_AGENT = "supervisor";

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

    /** supervisor/askUser 决策协议（四 action；input 仅 retryWithInput 携带）。 */
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
     * 命令节点执行器：{@code services().nativeExec().spawnNative}（argv 直传）；
     * Windows {@code cmd.exe /c}、Unix {@code /bin/sh -c}；cwd=工作区根；
     * stdout/stderr 全量入 runs 文件（返回值交引擎留档）。超时/中止视为节点失败抛出。
     */
    public static final class CommandNodeRunner {

        CommandNodeRunner() {
        }

        public NodeResult run(WorkerServices services, String workspaceRoot, NodeDef node,
                String renderedCommand) {
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            String[] argv = windows
                    ? new String[]{"cmd.exe", "/c", renderedCommand}
                    : new String[]{"/bin/sh", "-c", renderedCommand};
            long timeoutMs = node.timeoutMs() != null && node.timeoutMs() > 0
                    ? node.timeoutMs() : DEFAULT_COMMAND_TIMEOUT_MS;
            ExecResult r = services.nativeExec()
                    .spawnNative(argv, Path.of(workspaceRoot), Map.of(), timeoutMs);
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

    // ══════════════════════════ AI 节点 ══════════════════════════

    /**
     * AI 节点执行器——严格按「Agent 上下文模型 §2」会话时序 ①-⑥：
     * ①持 agent fair 锁 → ②conversationArchive 载种子（含交替不变量补齐）→
     * ③发 user.message 指令轮（agentId={@code <name>#<wi>}，落盘，文件自包含）→
     * ④agentFactoryFor(view).create(agentKey, configId).systemPrompt(声明).conversation(种子)
     * .userInput(渲染后输入).tools(剔除表, REMOVE).build() → ⑤run()（事件实时落盘；
     * delta/thinking 增量经 node 绑定视图以 node.delta 瞬态扇出实例流频道，架构 §7.13）→
     * ⑥lastText()/conversation()/usage() 收口交引擎；锁在 finally 释放（锁粒度=整个 run）。
     *
     * <p>匿名 AI 节点（agent 未指定）= 一次性会话：无种子、不发指令轮、不落 agents/、
     * agentId={@code <nodeId>.<runNo>}（不带 {@code #<wi>} 归户后缀 → 事件不持久化）。
     */
    public static final class AiNodeRunner {

        AiNodeRunner() {
        }

        /**
         * @param agentLock 命名 agent 的 fair 锁（匿名节点传 null）
         * @param visibleToAi token 拍平后的输入（AI 实际所见）
         * @param rendered    变量渲染后的原文（opaque token 原串留档）
         */
        public NodeResult run(WorkerServices services, WorkflowRuntime runtime, String instanceId,
                Definition snapshot, NodeDef node, int runNo, String rendered, String visibleToAi,
                ReentrantLock agentLock) throws InterruptedException {
            if (agentLock != null) {
                agentLock.lockInterruptibly();
            }
            try {
                String agentName = node.agent() == null || node.agent().isBlank()
                        ? null : node.agent();
                AgentDef agentDef = agentName == null ? null : findAgent(snapshot, agentName);
                String configId = node.modelConfigId() != null ? node.modelConfigId()
                        : (agentDef != null ? agentDef.modelConfigId() : null);
                // node 绑定视图：emitter() 为包装口（delta/thinking → node.delta 瞬态扇出
                // 实例流频道，agent.run 期间由此关联当前节点，架构 §7.13）
                NodeExecView view = runtime.view(configId, node.id());
                String agentKey = agentName != null
                        ? agentName + "#" + instanceId
                        : node.id() + "." + runNo;

                // ① 载种子（磁盘事件流 → 全保真会话；失败空表不抛）
                List<Message> seed = List.of();
                if (agentName != null) {
                    seed = AgentSessions.ensureAlternation(
                            runtime.conversationArchive().load(agentKey));
                }

                // ② 指令轮先行落盘（user.message 带归户 agentId——jsonl 自包含，续跑重建依据）
                if (agentName != null) {
                    emitInstruction(runtime, agentKey, visibleToAi, rendered);
                }

                // ③ 装配：每 run 新建 AgentEntity，运行即销毁（连续性由磁盘事件流保证）
                AgentBuilder builder = services.agentFactoryFor(view)
                        .create(agentKey, configId)
                        .title(node.name() == null ? node.id() : node.name())
                        .creator("workflow")
                        .tools(ExcludedTools.toRemove(), AgentBuilder.ModifyMode.REMOVE);
                if (agentDef != null && agentDef.systemPrompt() != null
                        && !agentDef.systemPrompt().isBlank()) {
                    builder.systemPrompt(agentDef.systemPrompt());
                }
                if (!seed.isEmpty()) {
                    builder.conversation(seed);
                }
                builder.userInput(visibleToAi);
                Agent agent = builder.build();

                // ④ run（同步阻塞本虚拟线程；事件经 emitter 双路由实时追加 per-agent jsonl）
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

    // ══════════════════════════ supervisor ══════════════════════════

    /**
     * supervisor 执行器（错误策略 onError=supervisor）：以结构化错误上下文跑督导会话线
     * （固定名 supervisor，agents/supervisor.jsonl 续写——连续介入记得此前决策），
     * 输出纯 JSON 决策（剥 ```json 围栏容忍）；解析失败重试一次，再失败返回 null
     * （调用方降级 askUser）。督导自身异常（模型故障等）同样返回 null 走降级。
     */
    public static final class SupervisorRunner {

        SupervisorRunner() {
        }

        /**
         * @param errorContext 结构化错误上下文（{@link #buildErrorContext} 产出）
         * @return 解析成功的决策；两轮均不可解析/督导自身失败 → null（降级 askUser）
         */
        public Decision decide(WorkerServices services, WorkflowRuntime runtime, String instanceId,
                Definition snapshot, String errorContext, ReentrantLock supervisorLock)
                throws InterruptedException {
            String input = errorContext;
            for (int attempt = 0; attempt <= SUPERVISOR_PARSE_RETRIES; attempt++) {
                String output;
                try {
                    output = runSupervisorAgent(services, runtime, instanceId, snapshot, input,
                            supervisorLock);
                } catch (InterruptedException ie) {
                    throw ie;
                } catch (Throwable t) {
                    log.warn("[workflow] supervisor 运行失败(降级 askUser) instance={}", instanceId, t);
                    return null;
                }
                Decision d = parseDecision(output);
                if (d != null) {
                    return d;
                }
                input = errorContext + "\n\n[系统提示] 你上一次的输出不是合法的决策 JSON。"
                        + "请严格只输出 {\"action\":\"retry|skip|fail|retryWithInput\","
                        + "\"input\":\"...\",\"reason\":\"...\"} 这一个 JSON 对象,不要有任何其他文本。";
            }
            return null;
        }

        /** 督导会话线执行（同 AI 节点命名 agent 机制；失败抛出由 decide 处理）。 */
        private String runSupervisorAgent(WorkerServices services, WorkflowRuntime runtime,
                String instanceId, Definition snapshot, String input,
                ReentrantLock supervisorLock) throws InterruptedException {
            supervisorLock.lockInterruptibly();
            try {
                String agentKey = SUPERVISOR_AGENT + "#" + instanceId;
                String configId = snapshot.supervisor() == null ? null
                        : snapshot.supervisor().modelConfigId();
                String prompt = snapshot.supervisor() != null
                        && snapshot.supervisor().systemPrompt() != null
                        && !snapshot.supervisor().systemPrompt().isBlank()
                                ? snapshot.supervisor().systemPrompt() : DEFAULT_SUPERVISOR_PROMPT;

                List<Message> seed = AgentSessions.ensureAlternation(
                        runtime.conversationArchive().load(agentKey));
                emitInstruction(runtime, agentKey, input, input);
                NodeExecView view = runtime.view(configId);
                Agent agent = services.agentFactoryFor(view)
                        .create(agentKey, configId)
                        .title("工作流督导")
                        .creator("workflow-supervisor")
                        .tools(ExcludedTools.toRemove(), AgentBuilder.ModifyMode.REMOVE)
                        .systemPrompt(prompt)
                        .conversation(seed)
                        .userInput(input)
                        .build();
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
                return agent.lastText() == null ? "" : agent.lastText();
            } finally {
                supervisorLock.unlock();
            }
        }
    }

    // ══════════════════════════ 共用 ══════════════════════════

    /** 发 user.message 指令轮（带归户 agentId；rawContent 留档 opaque token 原串）。 */
    static void emitInstruction(WorkflowRuntime runtime, String agentKey, String visibleToAi,
            String rawRendered) {
        try {
            runtime.emitter().emit(EmitEvent.of(SnowflakeId.next(), Events.USER_MESSAGE,
                    agentKey, null, null, visibleToAi, null,
                    Map.of("rawContent", rawRendered == null ? "" : rawRendered),
                    EmitEvent.Mode.REPLACE));
        } catch (RuntimeException e) {
            log.warn("[workflow] 指令轮落盘失败 agent={}", agentKey, e);
        }
    }

    private static AgentDef findAgent(Definition snapshot, String name) {
        for (AgentDef a : snapshot.agentsOrEmpty()) {
            if (a != null && name.equals(a.name())) {
                return a;
            }
        }
        return null;
    }

    /**
     * supervisor 决策解析：剥 ```json 围栏与前后杂文本，取首个 '{' 到末个 '}' 的片段解析
     * {action, input?, reason?}；action 非四值之一/无 JSON 片段 → null。
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
     * 结构化错误上下文（supervisor 的输入；askUser 的 context 同源裁剪）：
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
