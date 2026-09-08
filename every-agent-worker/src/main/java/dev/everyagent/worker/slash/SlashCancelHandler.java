package dev.everyagent.worker.slash;

/**
 * 斜杠命令「取消」业务回调:胶囊/⌧(内联✕)被移除时触发,用于回滚选中时产生的副作用。
 *
 * <p>契约:{@code token} 为被取消调用的 opaque token 文本(供回查注入来源);
 * {@code taskId} 可空,为空表示草稿取消/内联✕,调用方约定<b>此回调内不应写任务 meta</b>。
 *
 * @see #NOOP 默认空操作(未挂回调的条目取消时无事发生)
 */
@FunctionalInterface
public interface SlashCancelHandler {

    /** 空操作的默认取消回调。 */
    SlashCancelHandler NOOP = (item, token, taskId) -> {
        // 默认无副作用
    };

    /**
     * 条目被取消时触发。
     *
     * @param item   被取消的斜杠命令条目(非 null)。
     * @param token  被取消调用的 opaque token 文本(可为空/纯文本)。
     * @param taskId 当前任务 ID;可空(空=草稿取消/内联✕,回调内不应写任务 meta)。
     */
    void onCancel(SlashCommandItem item, String token, String taskId);
}