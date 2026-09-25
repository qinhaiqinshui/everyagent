package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.task.UserInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 成对节点(order=700)：输入队列（一事，inputQueue ↔ queue.jsonl 的载入/落盘）。
 * <p>下行段：恢复上轮终态落盘的悬空队列（readQueue → 逐条 offer）。
 * 新建任务目录无 queue.jsonl，readQueue 返回空——两条路径同一节点，无需分支。
 * 在 main.agent(390)（首条输入 consumeInput）之后、内核首次 poll 之前执行。
 * <p>上行段（临界段内）：悬空输入队列落盘 queue.jsonl / 空则删除。
 */
public final class QueueNode extends SectionNode {

    private static final Logger log = LoggerFactory.getLogger(QueueNode.class);

    private final TaskStore store;

    public QueueNode(TaskStore store) {
        this.store = store;
    }

    @Override
    public String id() { return "queue"; }

    @Override
    public float order() { return 700; }

    @Override
    protected void down(TaskLifecycleContext ctx) {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntry();
        // 恢复悬空队列:先跑本轮新输入(initialInput 已在 main.agent 消费),自然完成后逐条 poll 消费
        for (UserInput q : store.readQueue(store.dirOf(t.taskId))) {
            t.inputQueue.offer(q.text(), q.rawContent());
        }
    }

    @Override
    protected TaskOutcome up(TaskLifecycleContext ctx, TaskOutcome result) {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntry();
        var pending = t.inputQueue.snapshotItems();
        var qdir = store.dirOf(t.taskId);
        try {
            if (pending.isEmpty()) {
                store.deleteQueue(qdir);
            } else {
                store.writeQueue(qdir, pending);
            }
        } catch (IOException e) {
            log.warn("终态队列落盘失败 task={}(悬空队列丢弃)", t.taskId, e);
        }
        return result;
    }
}
