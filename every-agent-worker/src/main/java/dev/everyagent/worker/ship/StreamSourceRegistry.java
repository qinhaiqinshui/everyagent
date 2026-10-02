package dev.everyagent.worker.ship;

import dev.everyagent.plugin.api.event.EventLogReader;
import dev.everyagent.plugin.api.event.StreamSourceListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 流源注册表（基础设施层·流式推送子域）。
 * <p>编排层（task 层）在 track/再运行时把「taskId → EventLogReader」挂进此注册表，
 * 在 untrack/终态驱逐时 detach。推送器（DataPusher）经此注册表取日志，
 * 不再反向感知 TaskManager。
 *
 * <p>反转前：DataPusherManager → TaskManager.get(taskId).log（反向依赖）
 * 反转后：Task 层 → StreamSourceRegistry.attach(taskId, reader, mainAgentId)
 *         DataPusher → StreamSourceRegistry.getReader(taskId)（正向依赖基础设施层）
 */
@Component
public class StreamSourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(StreamSourceRegistry.class);

    /** 流源表：streamKey（通常=taskId）→ EventLogReader */
    private final Map<String, EventLogReader> sources = new ConcurrentHashMap<>();
    /** mainAgentId 表：streamKey → mainAgentId（payload 组装用,与日志同生命周期）。 */
    private final Map<String, String> mainAgentIds = new ConcurrentHashMap<>();

    /** 监听器：attach/detach 时通知（DataPusherManager 注册，替代 TaskResumeListener） */
    private final CopyOnWriteArrayList<StreamSourceListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 挂接流源：编排层在 track/再运行时调用。
     * @param streamKey 通常 = taskId
     * @param log 任务的内存事件日志（EventLogReader 接口）
     * @param mainAgentId 任务主 agent 稳定 id（推送器 payload 组装用）
     */
    public void attach(String streamKey, EventLogReader log, String mainAgentId) {
        sources.put(streamKey, log);
        if (mainAgentId != null) {
            mainAgentIds.put(streamKey, mainAgentId);
        }
        for (StreamSourceListener l : listeners) {
            try {
                l.onAttach(streamKey, log);
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
        EventLogReader removed = sources.remove(streamKey);
        mainAgentIds.remove(streamKey);
        if (removed == null) return;
        for (StreamSourceListener l : listeners) {
            try {
                l.onDetach(streamKey);
            } catch (RuntimeException e) {
                log.debug("onDetach 监听器异常 streamKey={}", streamKey, e);
            }
        }
        log.debug("流源摘除 streamKey={}", streamKey);
    }

    /**
     * 获取流源的 EventLogReader。推送器用此方法替代直接访问 TaskManager。
     * @return EventLogReader 或 null（任务不在内存/已驱逐）
     */
    public EventLogReader getReader(String streamKey) {
        return sources.get(streamKey);
    }

    /**
     * 获取流源对应的 mainAgentId（推送器 payload 组装用）。
     * @return mainAgentId 或 null（流源未挂接/未携带）
     */
    public String getMainAgentId(String streamKey) {
        return mainAgentIds.get(streamKey);
    }

    /**
     * 注册流源变化监听器。
     */
    public void addListener(StreamSourceListener listener) {
        listeners.add(listener);
    }

    /**
     * 注销监听器。
     */
    public void removeListener(StreamSourceListener listener) {
        listeners.remove(listener);
    }
}
