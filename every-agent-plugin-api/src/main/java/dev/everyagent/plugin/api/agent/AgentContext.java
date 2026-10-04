package dev.everyagent.plugin.api.agent;

import dev.everyagent.plugin.api.event.Usage;
import dev.everyagent.plugin.api.execution.ExecContext;
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

    /**
     * agent 创建者标识（{@code task} = 主 agent，{@code subagent} = subagent 插件派生，
     * {@code ai-review} = 审议 agent；由创建方经 {@link AgentBuilder#creator} 设定）。
     *
     * <p>随 {@code agent.started} 事件持久化进台账**顶级字段**（不再是 agentMetadata 里的
     * 约定键）。消费方据此决定展示范围（{@code list_agents} / {@code task.agents} 只取
     * {@code subagent}），但**状态机行为对所有 creator 一视同仁**——出生、状态翻转、终态
     * 事件全部由同一套 per-run 生命周期方法发射，没有按 creator 的分支。
     *
     * @return 创建者标识；null = 未设（旧调用方/测试桩）
     */
    default String creator() {
        return null;
    }

    /** agent 元数据（创建时注入的其他元数据；随 agent.started 事件持久化到台账）。 */
    default Map<String, Object> agentMetadata() {
        return Map.of();
    }

    /** 创建时刻(毫秒时间戳)。 */
    long createdAt();

    /**
     * 本 agent 所属的执行上下文(task 或未来 workflow;原黑盒 map
     * {@code properties().get("taskEntry")} 四件套键已随 S4 退役)。
     * <p>横切 advisor / 工具拦截器经此类型化槽位取数
     * ({@code subjectId()} / {@code workspaceRoot()} / {@code snapshot()} 等)。
     */
    default ExecContext execution() {
        return null;
    }

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

    // ── per-run 生命周期状态机(agent.* 事件的唯一发射口) ──
    //
    // 一个 agent 的宏观身份是长命的(跨多轮 run() 复用会话),而 agent.* 生命周期事件
    // 是 per-run() 的:每轮 `Agent.run()` 就是一个完整周期
    // `agent.started → agent.status{running} → [agent.status{waiting-user} ⇄ running]* →
    //  error? → agent.done → agent.status{done|failed|stopped}`。
    // 状态机与事件发射收在本契约的实现方(worker AgentEntity)一处:
    // 「谁触发」与「谁发射」分离——触发源是 advisor 链的流生命周期信号与 ask 生命周期,
    // 发射永远只有一处,不再散落到 task 层 / 插件里手搓 EmitEvent。

    /**
     * 本轮 {@code run()} 开始:重置上一轮的 per-run 状态(若上一轮已终态),CAS 进入
     * running,并发射 {@code agent.started} + {@code agent.status{running}}。
     *
     * <p>由 {@code AgentStatusAdvisor} 在 {@code adviseStream} 入口调用——即每一轮模型流
     * 订阅恰好一次;同一轮内重复调用不发重复出生事件(CAS 挡住)。
     */
    default void beginRun() {
    }

    /**
     * ask 挂起开始:CAS running → waiting-user,成功则发 {@code agent.status{waiting-user}}。
     *
     * <p>由交互层在 ask 登记后调用(覆盖 ask_user 工具 + 危险命令授权 + 图片理解授权三类
     * ask——它们都经同一 ask 入口;按工具名嗅探会漏掉后两类)。
     *
     * @return true = 本调用翻转成功(已发事件)
     */
    default boolean markWaitingUser() {
        return false;
    }

    /**
     * ask 结束(回答/超时/取消):CAS waiting-user → running,成功则发
     * {@code agent.status{running}}。
     *
     * <p>本轮已终态时不翻转——ask 被取消不该把已收口的 agent 报回 running。
     *
     * @return true = 本调用翻转成功(已发事件)
     */
    default boolean markAskResolved() {
        return false;
    }

    /**
     * 声明本轮终态(CAS 幂等):仅第一个调用者成功,成功者负责发射本轮终态事件
     * (顺序固定 {@code error? → agent.done → agent.status{done|failed|stopped}}——
     * 台账的 agent.done 分支无条件写 status=completed,故终态 status 必须后发才不被改判)。
     *
     * @param status  终态宏状态(completed / stopped / error)
     * @param message 终态附言:写入 latestActivity.error;{@code status=error} 时同时作为
     *                {@code error} 事件正文。stopped/completed 传非空只记快照不发 error 事件
     *                (用户主动停止不是「出错」,不发红块)。null = 无附言。
     * @return true = 本调用者成功声明终态;false = 已被抢先声明(本轮终态已发)
     */
    default boolean claimTerminal(String status, String message) {
        return claimTerminal(status);
    }

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

    /** 重置为可重跑状态(finished→false,清除本轮终态声明与 error 快照)。 */
    void resetForRerun();

    /**
     * 终态声明(CAS 幂等,无附言的兼容入口)。
     *
     * @param status 终态状态(completed / stopped / error)
     * @return true = 本调用者成功声明终态;false = 已被抢先声明
     * @see #claimTerminal(String, String)
     */
    boolean claimTerminal(String status);
}
