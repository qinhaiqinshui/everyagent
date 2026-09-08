package dev.everyagent.worker.slash;

import java.util.List;

/**
 * `/` 斜杠命令候选条目(展示字段 + 可选业务回调,纯数据,不耦合 Spring AI)。
 *
 * <p>默认(未挂回调时)行为与老项目一致:选中后把 {@code insertText} 原样插入输入框,
 * 若其是 opaque token(由 {@link SlashTokenEncoder} 构造)则渲染为胶囊,否则原样插入纯文本——
 * 与老项目 slash 层「只判断形态、不解析内容」的契约一致(等价于
 * {@link #defaultSelect(SlashCommandItem, String)} 返回 inline)。
 *
 * <p>可选业务回调(组件可空,访问器恒非 null,空值在构造时替换为默认实现):
 * <ul>
 *   <li>{@code selectHandler}:选中回调,默认={@link #defaultSelect(SlashCommandItem, String)};
 *       挂自定义回调后返回 {@link List}<{@link SlashSelectionResult}> 决定 inline 原地插入或
 *       bottom 底部渲染(可一次返回多个,如「无人值守」联动返回双胶囊);</li>
 *   <li>{@code cancelHandler}:取消回调(胶囊/⌧ 移除),默认={@link SlashCancelHandler#NOOP} 空操作。</li>
 * </ul>
 * 回调经后续 slash.select / slash.cancel RPC 与前端任务 token 注入配合消费;
 * 本类仅承载回调签名与默认行为,不实现注入。
 *
 * @param id            全局唯一 ID(用于 React key 与跨 provider 去重)。
 * @param title         展示标题(必填);胶囊显示文字来自 opaque token 顶层 label,与之一致。
 * @param subtitle      副内容(可选)。
 * @param icon          内联 SVG 字符串(可选),currentColor 上色。
 * @param group         分组名(可选,直接作为展示标题)。
 * @param insertText    选中后要插入输入框的内容:opaque token 串或纯文本(默认 select 行为使用)。
 * @param selectHandler 选中回调(可空;null 视为默认原地行 {@link #defaultSelect(SlashCommandItem, String)})。
 * @param cancelHandler 取消回调(可空;null 视为 {@link SlashCancelHandler#NOOP} 空操作)。
 * @param defaultSelected 新建任务时是否默认选中(false=否;true=前端草稿预置该条目,用户仍可 ✕ 取消)。默认 false。
 */
public record SlashCommandItem(
        String id,
        String title,
        String subtitle,
        String icon,
        String group,
        String insertText,
        SlashSelectHandler selectHandler,
        SlashCancelHandler cancelHandler,
        boolean defaultSelected) {

    /** 6 参便捷构造(既有调用方零改动):回调缺省由规范构造替换为默认实现,默认 {@code defaultSelected=false}。 */
    public SlashCommandItem(
            String id, String title, String subtitle, String icon, String group, String insertText) {
        this(id, title, subtitle, icon, group, insertText, null, null, false);
    }

    /** 8 参便捷构造(既有调用方零改动):默认 {@code defaultSelected=false}。 */
    public SlashCommandItem(
            String id,
            String title,
            String subtitle,
            String icon,
            String group,
            String insertText,
            SlashSelectHandler selectHandler,
            SlashCancelHandler cancelHandler) {
        this(id, title, subtitle, icon, group, insertText, selectHandler, cancelHandler, false);
    }

    /** 规范构造:校验必填展示字段;回调为 null 时替换为默认实现,保证访问器恒非 null。 */
    public SlashCommandItem(
            String id,
            String title,
            String subtitle,
            String icon,
            String group,
            String insertText,
            SlashSelectHandler selectHandler,
            SlashCancelHandler cancelHandler,
            boolean defaultSelected) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("slash item id 不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("slash item title 不能为空");
        }
        if (insertText == null) {
            throw new IllegalArgumentException("slash item insertText 不能为空");
        }
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.icon = icon;
        this.group = group;
        this.insertText = insertText;
        this.selectHandler = selectHandler == null ? SlashCommandItem::defaultSelect : selectHandler;
        this.cancelHandler = cancelHandler == null ? SlashCancelHandler.NOOP : cancelHandler;
        this.defaultSelected = defaultSelected;
    }

    /**
     * 默认 select 行为:原地返回条目的 {@code insertText} 并置于 {@link SlashDisplayPosition#INLINE}
     * (「像现在一样」——前端把串原样插入输入框,opaque token 渲染为胶囊)。
     *
     * @param item   被选中的条目(非 null)。
     * @param taskId 当前任务 ID(可空;默认实现不消费)。
     * @return 单元素列表(原地 inline 一个结果)。
     */
    public static List<SlashSelectionResult> defaultSelect(SlashCommandItem item, String taskId) {
        return List.of(SlashSelectionResult.inline(item.insertText()));
    }
}
