package dev.everyagent.worker.ship;

import dev.everyagent.worker.task.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 流源注册表（基础设施层·流式推送子域）。
 * <p>编排层（task 层）在 track/再运行时把「taskId → EventLog」挂进此注册表，
 * 在 untrack/终态驱逐时 detach。推送器（DataPusher）经此注册表取日志，
 * 不再反向感知 TaskManager。
 *
 * <p>反转前：DataPusherManager → TaskManager.get(taskId).log（反向依赖）
 * 反转后：Task 层 → StreamSourceRegistry.attach(taskId, log)
 *         DataPusher → StreamSourceRegistry.getEventLog(taskId)（正向依赖基础设施层）
 */
@Component
public class StreamSourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(StreamSourceRegistry.class);

    /** 流源表：streamKey（通常=taskId）→ EventLog */
    private final Map<String, EventLog> sources = new ConcurrentHashMap<>();

    /** 监听器：attach/detach 时通知（DataPusherManager 注册，替代 TaskResumeListener） */
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /** 流源变化监听器 */
    public interface Listener {
        /** 流源挂接（新任务 track 或再运行） */
        void onAttach(String streamKey, EventLog log);
        /** 流源摘除（终态 untrack） */
        void onDetach(String streamKey);
    }

    /**
     * 挂接流源：编排层在 track/再运行时调用。
     * @param streamKey 通常 = taskId
     * @param log 任务的内存事件日志
     */
    public void attach(String streamKey, EventLog eventLog) {
        sources.put(streamKey, eventLog);
        for (Listener l : listeners) {
            try {
                l.onAttach(streamKey, eventLog);
            } catch (RuntimeException e) {
                log.debug("onAttach 监听器异常 streamKey={}", streamKey, e);
            }
        }
        log.debug("流源挂接 streamKey={}", streamKey);
    }

    /**
     * 摘除流源：编排层在 untrack/终态驱逐时调用。
     */
    public void detach(String streamKey) {
        EventLog removed = sources.remove(streamKey);
        if (removed == null) return;
        for (Listener l : listeners) {
            try {
                l.onDetach(streamKey);
            } catch (RuntimeException e) {
                log.debug("onDetach 监听器异常 streamKey={}", streamKey, e);
            }
        }
        log.debug("流源摘除 streamKey={}", streamKey);
    }

    /**
     * 获取流源的 EventLog。推送器用此方法替代直接访问 TaskManager。
     * @return EventLog 或 null（任务不在内存/已驱逐）
     */
    public EventLog getEventLog(String streamKey) {
        return sources.get(streamKey);
    }

    /**
     * 注册流源变化监听器。
     */
    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /**
     * 注销监听器。
     */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }
}
