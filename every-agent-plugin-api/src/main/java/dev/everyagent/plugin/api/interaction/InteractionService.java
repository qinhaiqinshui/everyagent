package dev.everyagent.plugin.api.interaction;

import java.util.List;
import java.util.function.Consumer;

/**
 * 用户交互公共服务 —— 同步阻塞(虚拟线程)与异步回调两种调用模式。
 *
 * <p>ask_user 工具、授权链、插件自定义工具均可经此向用户发起交互。
 * 本接口只负责"显示问题→收集回答→返回文本"，不感知任何调用方语义。
 */
public interface InteractionService {

    /**
     * 同步阻塞（运行在虚拟线程上，挂起零成本）。
     *
     * @param taskId 任务 ID
     * @param agentId agent ID（主 agent 或子 agent）
     * @param questions 问题列表（id 可传空占位，由实现以真实 askId 派生）
     * @param timeoutMs 超时毫秒
     * @return 交互结果
     */
    AskResult ask(String taskId, String agentId,
            List<AskQuestion> questions, long timeoutMs) throws InterruptedException;

    /**
     * 异步回调（内部起虚拟线程跑同步逻辑，完成后回调）。
     */
    void askAsync(String taskId, String agentId,
            List<AskQuestion> questions, long timeoutMs,
            Consumer<AskResult> callback);
}
