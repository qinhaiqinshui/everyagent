package dev.everyagent.plugin.inputqueue;

import dev.everyagent.worker.task.UserInput;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 任务输入队列（从 worker 核心迁入插件）。
 * 线程安全，支持按下标删除/移动。语义与 BlockingQueue 的 offer/poll 一致。
 */
public final class InputQueue {

    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayList<UserInput> items = new ArrayList<>();

    public void offer(String text, String rawContent) {
        lock.lock();
        try {
            items.add(UserInput.of(text, rawContent));
        } finally {
            lock.unlock();
        }
    }

    public void offer(String text) {
        offer(text, null);
    }

    public UserInput poll() {
        lock.lock();
        try {
            return items.isEmpty() ? null : items.remove(0);
        } finally {
            lock.unlock();
        }
    }

    public UserInput removeAt(int index) {
        lock.lock();
        try {
            return items.remove(index);
        } finally {
            lock.unlock();
        }
    }

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

    public List<UserInput> snapshotItems() {
        lock.lock();
        try {
            return List.copyOf(items);
        } finally {
            lock.unlock();
        }
    }
}
