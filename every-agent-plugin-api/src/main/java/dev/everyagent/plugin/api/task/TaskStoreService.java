package dev.everyagent.plugin.api.task;

import org.springframework.ai.chat.messages.Message;

import java.nio.file.Path;
import java.util.List;

import tools.jackson.databind.node.ObjectNode;

/**
 * 任务落盘服务 —— 插件经 {@link dev.everyagent.plugin.api.WorkerServices#store()} 访问。
 *
 * <p>暴露插件实际需要的 TaskStore 方法（队列读写、截断、meta 读写、会话重建等）。
 * worker 的 {@code TaskStore} 实现此接口；插件不直接依赖 worker 的 TaskStore。
 */
public interface TaskStoreService {

    /**
     * 定位任务数据目录。
     * 已 track 的任务用登记目录；否则按 taskWorkspace 映射或懒发现定位。
     */
    Path dirOf(String taskId);

    // ---- 悬空队列落盘（queue.jsonl）----

    /** 整读 queue.jsonl；文件不存在/读取失败返回空列表。 */
    List<UserInput> readQueue(Path dir);

    /** 整写 queue.jsonl（临时文件 + ATOMIC_MOVE）；空列表也覆盖写空文件。 */
    void writeQueue(Path dir, List<UserInput> items) throws java.io.IOException;

    /** 删除 queue.jsonl（不存在则忽略）。 */
    void deleteQueue(Path dir);

    // ---- 截断（编辑重发）----

    /**
     * 截断磁盘后续事件（所有 *.jsonl 保留 seq ≤ targetSeq 的事件，
     * 截断 rounds.jsonl、清理 file-changes/ 与 agents.json）。
     *
     * @return true 如果找到 targetSeq 处的 user.message 事件
     */
    boolean truncateAfterSeq(Path dir, long targetSeq) throws java.io.IOException;

    /**
     * 编辑重发热路径专用：截断磁盘 + 重置落盘游标 + 重开 writer。
     * 先截断 jsonl 文件，然后关闭旧 writer、按截断后磁盘内容重建游标。
     *
     * @return true 如果找到 targetSeq 处的 user.message 事件
     */
    boolean truncateAndReset(String taskId, long targetSeq) throws java.io.IOException;

    // ---- meta 读写 ----

    /** 读 meta.json（文件不存在/损坏返回 null）。 */
    ObjectNode readMeta(Path dir);

    /** 原子写 meta.json（临时文件 + ATOMIC_MOVE）。 */
    void writeMeta(Path dir, ObjectNode summary) throws java.io.IOException;

    // ---- 会话重建 ----

    /**
     * 从磁盘重建主 agent 会话前缀（读 &lt;mainAgentId&gt;.jsonl → Spring AI 消息序列）。
     * 文件缺失/旧格式返回空列表。
     */
    List<Message> loadConversation(Path dir, String mainAgentId);

    // ---- agents.json 读写（subagent 台账）----

    /** 读 agents.json（不存在返回 null）。 */
    java.util.List<tools.jackson.databind.node.ObjectNode> readAgents(Path dir);

    /** 原子写 agents.json（覆盖写）。 */
    void writeAgents(String taskId, java.util.Collection<tools.jackson.databind.node.ObjectNode> agents);

    /** 判断任务数据目录是否存在（磁盘终态或运行中）。 */
    boolean taskDirExists(String taskId);
}
