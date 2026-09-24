package dev.everyagent.worker.ship;

import dev.everyagent.worker.hub.HubLink;
import tools.jackson.databind.JsonNode;

/**
 * 任务输入处理接口（基础设施·通信）：TaskMessageRouter 把 worker 输入频道
 * 收到的消息按类型路由到此接口。task 层（TaskManager）实现它。
 * <p>接口在基础设施层定义、上层实现——依赖方向与 AgentContext 相同
 * （下层定义接口，上层实现，下层经接口回调上层，不 import 上层具体类）。
 * <p>入参统一为消息 payload（taskId 等字段在其中读取）。
 */
public interface TaskInputHandler {

    /** task.input：用户输入（运行中入队 / 终态再运行认领）。 */
    void onTaskInput(HubLink conn, JsonNode payload);

    /** task.dialogInsert：队列项插入当前对话。 */
    void onDialogInsert(JsonNode payload);

    /** ask.reply：ask 回复。 */
    void onAskReply(JsonNode payload);
}
