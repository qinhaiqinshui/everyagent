package dev.everyagent.worker.slash;

/**
 * 斜杠命令选中后的「展示位置」:决定回调结果插入输入框的形态。
 *
 * <p>wire 上用小写字符串,统一由 {@link #wireName()} 提供
 * (= {@code name().toLowerCase()}):{@code INLINE→inline}、{@code BOTTOM→bottom}。
 */
public enum SlashDisplayPosition {

    /** 原地:像现在一样把串原样插入输入框(opaque token 渲染为胶囊)。 */
    INLINE,

    /** 底部:不插入输入框,改为在输入区底部渲染触发区(供业务回调消费)。 */
    BOTTOM;

    /** wire 传输用的小写名称(与前端约定一致)。 */
    public String wireName() {
        return name().toLowerCase();
    }
}