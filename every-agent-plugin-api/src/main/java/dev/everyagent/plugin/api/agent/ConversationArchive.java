package dev.everyagent.plugin.api.agent;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 会话档案端口(plugin-api 契约):按 agentId 从磁盘事件档案重建该 agent 的会话内存。
 *
 * <p><b>角色中性</b>:不区分主 agent / 子 agent / AI 审议 agent / 未来新增的派生 agent——
 * 落盘与重建一律按 agentId 走同一逻辑({@code <agentId>.jsonl} 自包含,谓词见 worker
 * {@code ConversationLoader}:agent.input/user.message → UserMessage、message →
 * AssistantMessage、tool.result → ToolResponseMessage、中断轮合成占位结果)。
 * 消费方(如 subagent 插件跨轮续跑)只管传 agentId,不知晓也不关心对方是什么角色。
 *
 * <p>经 {@code ExecContext.conversationArchive()} 槽位获取(与 emitter()/agentFactory()/
 * interaction() 同为预绑定端口):任务域实现委托 TaskStore + ConversationLoader;
 * 未来工作流层实现自己的档案口即可同构复用。
 */
@FunctionalInterface
public interface ConversationArchive {

    /**
     * 重建指定 agent 的会话内存(不含 system 轮——system 由创建方装配时另行注入)。
     *
     * @param agentId agent 标识(任意角色)
     * @return 会话消息序列(磁盘无该 agent 档案/读取失败返回空列表,永不抛异常)
     */
    List<Message> load(String agentId);
}
