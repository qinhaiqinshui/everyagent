package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.task.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 上行节点(order=700)：悬空输入队列落盘 queue.jsonl。临界段内。
 */
public final class QueuePersistNode extends UpstreamNode {

    private static final Logger log = LoggerFactory.getLogger(QueuePersistNode.class);

    private final TaskStore store;

    public QueuePersistNode(TaskStore store) {
        this.store = store;
    }

    @Override
    public String id() { return "queue.persist"; }

    @Override
    public float order() { return 700; }

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
