package dev.everyagent.worker.task;

import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.plugin.api.event.StreamEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 任务生命周期事件的 wire 出口(task 层,架构 §5.2/§5.3)。
 *
 * <p>集中做两件事,避免每个发布点重复:
 * <ol>
 * <li><b>频道定向</b>:发到**本 worker 的** tasks 频道 {@code u.<K>.worker.<workerId>.tasks}。
 * 同一 apiKey 下多台 worker 因此各归各的频道,一台的任务不会混进另一台的前端列表
 * (前端也不得按 ownerKey 前缀猜归属,§14.12);</li>
 * <li><b>归属入 payload</b>:保证 {@code workerId} 字段存在——契约
 * {@code events.schema.json} 的 tasksEvent 早已把它列为必填,此前实现漏发。</li>
 * </ol>
 *
 * <p>不进 plugin-api:{@code StreamEmitter} 是域中立通信口(§14.0 分层,"task"字样不属于它),
 * 本类是 task 层对它的包装。插件侧同语义的写法见各任务域插件发布点。
 *
 * <p><b>不改磁盘 meta</b>:workerId 只在 wire 上盖,不落 {@code meta.json}。理由:落盘后若用户
 * 改 {@code worker.worker-id},旧任务会带着陈旧身份;而 D20 已定「任务数据不做 owner 隔离」。
 */
public final class TaskEventWire {

    private TaskEventWire() {
    }

    /**
     * 扇出一条任务生命周期事件。
     *
     * @param eventSink 扇出入口(worker 侧为 {@code HubPool},自带本机 workerId)
     * @param event     事件名({@code Events.TASK_CREATED / TASK_UPDATED / TASK_DELETED / TASK_QUEUED})
     * @param payload   事件 payload;调用方须传**可写副本**(如 {@code TaskEntry#summaryJson()}
     *                  或磁盘 summary 的 deepCopy),不得传共享只读引用
     */
    public static void fanoutTasks(StreamEmitter eventSink, String event, JsonNode payload) {
        eventSink.fanout(k -> Channels.tasks(k, eventSink.workerId()), event, null,
                withWorkerId(payload, eventSink.workerId()), null);
    }

    /**
     * 给任务摘要补 {@code workerId}(契约 events.schema.json 的 tasksEvent 必填字段)。
     *
     * <p>已有该字段则原样返回;缺失时返回**副本**——磁盘 summary 是内存索引的共享引用,
     * 就地写会污染索引(也影响并发读)。
     */
    public static JsonNode withWorkerId(JsonNode payload, String workerId) {
        if (payload instanceof ObjectNode o && !o.has("workerId")) {
            return o.deepCopy().put("workerId", workerId);
        }
        return payload;
    }
}
