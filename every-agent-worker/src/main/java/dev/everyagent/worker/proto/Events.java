package dev.everyagent.worker.proto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 事件清单(架构 §3.3 任务域)与各事件 payload。归 worker 所有;hub 不感知。
 *
 * <p>事件名不做主/子 agent 区分——同一名事件(delta/message/error/...),
 * 是否子 agent 由 agentId 字段决定(主 agent = mainAgentId,wire 形剥离开;子 agent 必带)。
 * 仅 spawn 生命周期用 agent.* 专用名(agent.started/agent.done,只对子 agent 发)。
 *
 * <p>事件分两类(持久化约定):
 * <ul>
 * <li><b>持久事件</b>(落盘 jsonl,回放可见):user.message / message / tool.result / usage /
 *     ask.* / agent.started / agent.done / error / cancelled。
 *     其中 message 承载完整一轮:thinking + 正文 + 工具调用下发(真实 toolCall id)。</li>
 * <li><b>瞬态事件</b>(只推前端,不落盘):delta / thinking。
 *     瞬态事件也占 seq,故磁盘 seq 有洞——回放走 sync 直折叠 + 水位,不依赖连续性。</li>
 * </ul>
 * <p>纯显示事件(重试生命周期 / 任务耗时等无副作用展示)统一收敛为 {@link #TASK_TRACE},
 * 事件内以 {@code ext.persist=false} 标记瞬态实例(如每秒倒计时的 retry.progress),
 * 其余正常落盘;前端按 payload.traceId 原地更新 trace 内容。
 */
public final class Events {

    // stream 频道
    public static final String USER_MESSAGE = "user.message";
    /** 一轮新开(瞬态,不落盘):主 agent 新 user.message 且当前无未闭合轮时推送。 */
    public static final String ROUND_OPENED = "round.opened";
    /** 一轮闭合(瞬态,不落盘):主 agent 无 toolCalls 且有正文的 message 定稿后推送。 */
    public static final String ROUND_CLOSED = "round.closed";
    public static final String DELTA = "delta";
    public static final String THINKING = "thinking";
    public static final String MESSAGE = "message";
    public static final String USAGE = "usage";
    public static final String TOOL_RESULT = "tool.result";
    public static final String ASK_CREATE = "ask.create";
    public static final String ASK_STATE = "ask.state";
    public static final String ASK_RESOLVED = "ask.resolved";
    /** 子 agent spawn 生命周期(只对子 agent 发,必带 agentId)。 */
    public static final String AGENT_STARTED = "agent.started";
    public static final String AGENT_DONE = "agent.done";
    /** 主/子统一状态事件(必带 agentId,含主 agent):running / waiting-user / done / failed / stopped。 */
    public static final String AGENT_STATUS = "agent.status";
    public static final String ERROR = "error";
    public static final String CANCELLED = "cancelled";
    /**
     * 统一纯显示 trace 事件:无副作用的展示事件(重试生命周期、任务耗时等)全部收敛于此,
     * 替代旧 retry.attempt/progress/resolved/exhausted 与 task.duration 四个事件名。
     * payload 与前端 TaskTraceRecord 同形,前端按 {@code traceId} 原地 upsert:
     * 同一 traceId 的后续事件覆盖内容,不新增线程项(仿 node 侧 appendTaskTrace / updateTaskTrace)。
     * 瞬态实例(如 retry.progress 每秒倒计时)经 {@code ext.persist=false} 标记不落盘。
     */
    public static final String TASK_TRACE = "task.trace";

    // tasks 频道
    public static final String TASK_CREATED = "task.created";
    public static final String TASK_UPDATED = "task.updated";
    public static final String TASK_DELETED = "task.deleted";

    // worker 级 input 频道(taskId 入 payload)
    public static final String TASK_INPUT = "task.input";
    /** 任务队列「插入到当前对话」事件:把队列里某条用户输入立即注入正在进行的 AI 对话循环。 */
    public static final String TASK_DIALOG_INSERT = "task.dialogInsert";
    public static final String ASK_REPLY = "ask.reply";
    /** 前端流消费进度回报(worker 级输入频道):携带 creditIndex 释放 DataPusher 背压窗口。 */
    public static final String STREAM_ACK = "stream.ack";

    // evt 频道通知
    public static final String CONFIG_CHANGED = "config.changed";
    public static final String FS_CHANGED = "fs.changed";

    private Events() {
    }

    // ---- stream 事件 payload ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserMessage(String text, String from) {
    }

    /** text + agentId(子 agent 事件必带;主 agent wire 形剥离)。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(String text, String agentId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Thinking(String text, String agentId) {
    }

    /** message.toolCalls 单项:模型真实 toolCall id + name + 原始参数 JSON 串。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolCallPart(String id, String name, String arguments) {
    }

    /**
     * 完成一轮模型输出的权威记录:thinking(整轮思考)+ text(正文)+ toolCalls(下发,真实 id)。
     * 落盘与回放的最小完整单元;工具返回以 tool.result 单独事件配对(id 相同)。
     * 主/子 agent 同形,区分在 record.agentId。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessagePayload(String thinking, String text, List<ToolCallPart> toolCalls) {
    }

    /**
     * 单轮模型调用的实测 usage:round = 本轮,agentId 缺省为主 agent。
     * contextWindowTokens 来自任务快照 params(未配置回退默认窗口),前端据此渲染上下文电池。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UsageEvent(String agentId, String model, Long contextWindowTokens,
            TaskDtos.Usage round, TaskDtos.Usage total) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolResult(String callId, String summary, Boolean truncated, String agentId) {
    }

    /** 子 agent 的 ask 必带 agentId,主 agent 省略。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AskCreate(String askId, String kind, String question, List<String> options,
            Long timeoutMs, String agentId) {
    }

    /** 挂起期间每 30s 重发;重连/新上线前端由此恢复挂起卡片。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AskState(String askId, String kind, String status, String question,
            List<String> options, String agentId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AskResolved(String askId, String by, String status) {
    }

    /** agent.started / agent.done payload(子 agent spawn 生命周期,必带 agentId)。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentStarted(String agentId, String title, String input) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentDone(String agentId, JsonNode result, TaskDtos.Usage usage) {
    }

    /** agent.status payload(主/子统一;agentId 必带,status ∈ running/waiting-user/done/failed/stopped)。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentStatus(String agentId, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorEvent(String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cancelled(String by) {
    }

    /**
     * task.trace 事件 payload:与前端 TaskTraceRecord 同形。
     * traceId 必填——前端按此原地 upsert(有则覆盖、无则新增);kind 取值如
     * request_retry(重试生命周期)/ task_duration(任务耗时);status 表达该 trace 的阶段
     * (retrying/resolved/exhausted/done 等),供前端收起态文案与图标切换;
     * metadata 携带结构化的附加信息(attempt/maxAttempts/remainingMs/durationMs 等)。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TracePayload(String traceId, String kind, String title, String summary,
            String content, String status, Long createdAt, JsonNode metadata) {
    }

    // ---- input 频道 payload(taskId 入 payload)----

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskInput(String taskId, String text) {
    }

    /** 队列插入对话事件 payload:index 供 worker 移除该条队列项;text 为用户输入正文。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskDialogInsert(String taskId, Integer index, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AskReply(String askId, String answer) {
    }

    /** tasks 频道通知:任务被用户删除(多端列表同步)。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskDeleted(String taskId) {
    }

    // ---- evt 频道通知 payload ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConfigChanged(List<String> keys) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FsChanged(String path, String kind) {
    }
}
