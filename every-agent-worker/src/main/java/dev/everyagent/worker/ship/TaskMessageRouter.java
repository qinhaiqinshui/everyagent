package dev.everyagent.worker.ship;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.proto.Channels;
import dev.everyagent.worker.proto.Events;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 任务消息路由器（基础设施·通信）：注册为 HubPool.Listener，
 * 订阅 worker 输入频道，把 TASK_INPUT / TASK_DIALOG_INSERT / ASK_REPLY
 * 三类消息路由到 {@link TaskInputHandler}。
 * 从 TaskManager 迁出——消息解析与路由属通信基础设施，非任务编排。
 * <p><b>过渡期说明</b>：当前 TaskManager 仍 implements HubPool.Listener 且
 * pool.addListener(this) 未删——本路由与 TaskManager 侧双注册，两条路径都会
 * 收到同一消息。此为迁移过渡态，步骤 7 删除 TaskManager 侧旧路径后收敛。
 * <p>handler 经 ObjectProvider 延迟解析：TaskManager（未来 implements
 * TaskInputHandler）若被直接注入可能构造循环依赖——TaskManager 依赖的组件
 * 反过来依赖本路由。onHubConnected / onHubDisconnected 走 Listener 接口
 * default 空实现，无需覆写。
 */
@Component
public class TaskMessageRouter implements HubPool.Listener {

    private static final Logger log = LoggerFactory.getLogger(TaskMessageRouter.class);

    private final HubPool pool;
    private final WorkerProperties props;
    /** 延迟解析：TaskManager 实现 TaskInputHandler，Spring 注入避免循环依赖。 */
    private final ObjectProvider<TaskInputHandler> handlerProvider;

    public TaskMessageRouter(HubPool pool, WorkerProperties props,
            ObjectProvider<TaskInputHandler> handlerProvider) {
        this.pool = pool;
        this.props = props;
        this.handlerProvider = handlerProvider;
    }

    @PostConstruct
    void init() {
        pool.addListener(this);
        // worker 级输入频道常订阅（连接建立/重连时由 HubLink 重放）——从 TaskManager.init 迁出
        for (HubLink conn : pool.conns()) {
            conn.sub(Channels.workerInput(conn.k(), props.getWorkerId()));
        }
    }

    @Override
    public void onHubMessage(HubLink conn, JsonNode frame) {
        String channel = frame.path("channel").asString("");
        if (!channel.equals(Channels.workerInput(conn.k(), props.getWorkerId()))) {
            return;
        }
        TaskInputHandler handler = handlerProvider.getIfAvailable();
        if (handler == null) {
            return;
        }
        String event = frame.path("event").asString("");
        JsonNode payload = frame.path("payload");
        switch (event) {
            case Events.TASK_INPUT -> handler.onTaskInput(conn, payload);
            case Events.TASK_DIALOG_INSERT -> handler.onDialogInsert(payload);
            case Events.ASK_REPLY -> handler.onAskReply(payload);
            default -> {
            }
        }
    }
}
