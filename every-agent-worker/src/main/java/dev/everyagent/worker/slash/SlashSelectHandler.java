package dev.everyagent.worker.slash;

import java.util.List;

/**
 * 斜杠命令「选中」业务回调(与展示字段解耦,供显式挂业务逻辑的条目使用)。
 *
 * <p>契约:{@code taskId} 可空;为空表示草稿态(尚未建任务),调用方约定
 * <b>此回调内不应写任务 meta</b>。返回 {@link List}<{@link SlashSelectionResult}>
 * 决定前端如何展示——单元素即「一次返回一个胶囊/插入」,多元素用于联动场景
 * (如「无人值守」一次返回「无人值守」+「AI 审议」两个 bottom 胶囊,前端循环 apply)。
 *
 * @see SlashCommandItem#defaultSelect(SlashCommandItem, String) 默认实现(原地返回 insertText)
 */
@FunctionalInterface
public interface SlashSelectHandler {

    /**
     * 条目被选中时触发。
     *
     * @param item   被选中的斜杠命令条目(非 null)。
     * @param taskId 当前任务 ID;可空(空=草稿态,回调内不应写任务 meta)。
     * @return 展示结果列表(非 null;空列表 = 前端无动作)。
     */
    List<SlashSelectionResult> onSelect(SlashCommandItem item, String taskId);
}