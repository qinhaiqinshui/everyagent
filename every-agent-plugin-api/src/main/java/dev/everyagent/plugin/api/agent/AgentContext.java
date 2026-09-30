package dev.everyagent.plugin.api.agent;

import dev.everyagent.plugin.api.event.Usage;
import dev.everyagent.plugin.api.model.EventEmitter;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

/**
 * per-run agent 的数据面接口(plugin-api 契约)。
 *
 * <p>主 agent 与子 agent 共用同一接口,仅 {@code agentId} 不同。读写混合:
 * 插件(如 subagent)通过此接口读写运行时状态——conversation 追加、终态声明、
 * 活动快照更新、重跑重置等。
 *
 * <p>实现方(worker 的 {@code AgentEntity})持有 {@link EventEmitter}、会话内存、
 * usage 统计等;本接口只暴露插件实际调用的方法,不做过度设计。
 */
public interface AgentContext {

    // ── 只读字段(构造时确定) ──

    /** agent 唯一标识(主 agent 或子 agent)。 */
    String agentId();

    /** agent 标题(展示用)。 */
    String title();

    /** 创建时刻(毫秒时间戳)。 */
    long createdAt();

    /** 上层黑盒数据(agent 核心不读;task 层 advisor 从中取 TaskEntry 等)。 */
    Map<String, Object> properties();

    /** agent 层包装的 emitter(发射时自动填 agentId)。 */
    EventEmitter emitter();

    // ── 可读可写字段 ──

    /** 当前状态(running / completed / stopped / error)。 */
    String status();

    /** 是否已收口(运行体 finally 置 true)。 */
    boolean finished();

    /** 置 finished 标志(运行体收口时调用)。 */
    void finished(boolean finished);

    /** 最近一轮助手输出文本(终态事件载荷)。 */
    String lastText();

    /** 会话内存(可被压缩重写的载体;插件直接 {@code conversation().add(...)} 追加)。 */
    List<Message> conversation();

    // ── 模型与上下文校准 ──

    /** 当前 agent 的 ChatModel(摘要器等需要同步调模型时使用)。 */
    ChatModel chatModel();

    /** 当前请求的模型名(从 options.getModel() 取;null = 未知)。 */
    String currentModel();

    /** 最近一轮实测 usage(offset 校准等用;空 = 未知)。 */
    Usage lastRound();

    /** 最近一轮所用模型名(空 = 未知)。 */
    String lastModel();

    // ── 运行时方法 ──

    /** 累计 token 用量快照。 */
    Usage usage();

    /** 最近活动快照(list_agents/wait_agents 契约 latestActivity 字段)。 */
    AgentActivity activity();

    /** 合并更新活动快照(null 参数 = 保留原值)。 */
    void updateActivity(String reasoning, String content, String error);

    /** 重置为可重跑状态(status→running, finished→false, 清除 terminalClaimed 和 error)。 */
    void resetForRerun();

    /**
     * 终态声明(CAS 幂等):仅第一个调用者成功。
     *
     * @param status 终态状态(completed / stopped / error)
     * @return true = 本调用者成功声明终态;false = 已被抢先声明
     */
    boolean claimTerminal(String status);
}
