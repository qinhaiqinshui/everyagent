package dev.everyagent.plugin.api.event;

/**
 * 流源变化监听器(plugin-api 接口)。
 *
 * <p>ship 层({@code DataPusherManager})实现此接口,经 {@code StreamSourceRegistry}
 * 感知流源挂接/摘除,不再反向依赖 task 域。
 *
 * <p>task 层在 track/再运行时把 {@link EventLogReader} 挂进注册表,
 * 在 untrack/终态驱逐时摘除——监听器据此唤醒推送器对账。
 */
public interface StreamSourceListener {

    /** 流源挂接(新任务 track 或再运行)。 */
    void onAttach(String subjectId, EventLogReader log);

    /** 流源摘除(终态 untrack)。 */
    void onDetach(String subjectId);
}
