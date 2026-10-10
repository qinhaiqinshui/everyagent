package dev.everyagent.plugin.inputqueue;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 任务输入队列（从 worker 核心迁入插件）。
 * 队列项直接存 {@link TaskLifecycleContext} 引用：ctx 是链节点间透传的任务上下文，
 * 携带 input/rawContent/metadata，入队存引用、取出时全数据原样下传，
 * 后续节点（edit.resend / consume.input）自行从 metadata 消费自己的标记。
 * 线程安全，支持按下标删除/移动。语义与 BlockingQueue 的 offer/poll 一致。
 */
public final class InputQueue {

    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayList<TaskLifecycleContext> items = new ArrayList<>();

    public void offer(TaskLifecycleContext ctx) {
        lock.lock();
        try {
            items.add(ctx);
        } finally {
            lock.unlock();
        }
    }

    public TaskLifecycleContext poll() {
        lock.lock();
        try {
            return items.isEmpty() ? null : items.remove(0);
        } finally {
            lock.unlock();
        }
    }

    public TaskLifecycleContext removeAt(int index) {
        lock.lock();
        try {
            return items.remove(index);
        } finally {
            lock.unlock();
        }
    }

    /** 按输入文本匹配删除第一项（无匹配返回 null）。 */
    public TaskLifecycleContext removeFirst(String text) {
        lock.lock();
        try {
            for (int i = 0; i < items.size(); i++) {
                String itemText = items.get(i).input();
                if (itemText != null && itemText.equals(text)) {
                    return items.remove(i);
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    public void move(int fromIndex, int toIndex) {
        lock.lock();
        try {
            TaskLifecycleContext item = items.remove(fromIndex);
            items.add(toIndex, item);
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return items.size();
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return items.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    /** 各队列项 input() 文本快照（广播 pendingInputs 用）。 */
    public List<String> snapshot() {
        lock.lock();
        try {
            List<String> out = new ArrayList<>(items.size());
            for (TaskLifecycleContext item : items) {
                out.add(item.input());
            }
            return List.copyOf(out);
        } finally {
            lock.unlock();
        }
    }

    /** 队列项只读快照（终态落盘悬空队列用）。 */
    public List<TaskLifecycleContext> snapshotItems() {
        lock.lock();
        try {
            return List.copyOf(items);
        } finally {
            lock.unlock();
        }
    }
}
