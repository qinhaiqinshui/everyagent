package dev.everyagent.worker.task;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 任务输入队列(运行时态,线程安全,支持按下标删除/移动)。
 * 语义保持与 {@link java.util.concurrent.BlockingQueue} 的 offer/poll/clear 一致:
 * offer 队尾追加、poll 取队首并移除(空返回 null,非阻塞)、clear 清空。
 * 内部用 {@link ReentrantLock} 保护的 {@link ArrayList}:队列规模通常很小,
 * 按下标操作 O(n) 的搬移可忽略;锁保证多线程 offer/poll/removeAt/move 的互斥。
 */
public final class InputQueue {

    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayList<UserInput> items = new ArrayList<>();

    /** 队尾追加(text 为 AI 可见文本,rawContent 为原始内容,可为空)。 */
    public void offer(String text, String rawContent) {
        lock.lock();
        try {
            items.add(UserInput.of(text, rawContent));
        } finally {
            lock.unlock();
        }
    }

    /** 队尾追加纯文本输入(无原始内容)。 */
    public void offer(String text) {
        offer(text, null);
    }

    /** 取队首并移除;空队列返回 null(非阻塞,与 LinkedBlockingQueue.poll 语义一致)。 */
    public UserInput poll() {
        lock.lock();
        try {
            return items.isEmpty() ? null : items.remove(0);
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            items.clear();
        } finally {
            lock.unlock();
        }
    }

    /** 按下标删除并返回被删元素;越界抛 {@link IndexOutOfBoundsException}。 */
    public UserInput removeAt(int index) {
        lock.lock();
        try {
            return items.remove(index);
        } finally {
            lock.unlock();
        }
    }

    /** 删除第一条与 text 等值的元素(用于「插入到当前对话」等标注了正文的消费场景,
     *  避免前端下标在并发消费下漂移点到别的项);删除成功返回被删元素,无匹配返回 null。 */
    public UserInput removeFirst(String text) {
        lock.lock();
        try {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).text().equals(text)) {
                    return items.remove(i);
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /** 把 fromIndex 的元素移动到 toIndex(重排);越界抛 {@link IndexOutOfBoundsException}。 */
    public void move(int fromIndex, int toIndex) {
        lock.lock();
        try {
            UserInput item = items.remove(fromIndex);
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

    /** 对外只读快照(仅 AI 可见 text),顺序 = 队首在前;供 pendingInputs 展示。 */
    public List<String> snapshot() {
        lock.lock();
        try {
            List<String> out = new ArrayList<>(items.size());
            for (UserInput item : items) {
                out.add(item.text());
            }
            return List.copyOf(out);
        } finally {
            lock.unlock();
        }
    }

    /** 对外只读完整快照(text + rawContent),顺序 = 队首在前;供终态落盘 queue.jsonl。 */
    public List<UserInput> snapshotItems() {
        lock.lock();
        try {
            return List.copyOf(items);
        } finally {
            lock.unlock();
        }
    }
}
