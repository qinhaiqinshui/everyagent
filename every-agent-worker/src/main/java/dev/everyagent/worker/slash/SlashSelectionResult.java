package dev.everyagent.worker.slash;

/**
 * 选中回调的返回结果:告诉前端「插什么、插到哪」。
 *
 * @param token    要交给前端的内容串(可为 opaque token 或纯文本;BOTTOM 场景可为空)。
 * @param position 展示位置({@link SlashDisplayPosition#INLINE}=原地插入,
 *                 {@link SlashDisplayPosition#BOTTOM}=渲染在输入区底部)。
 * @param id       胶囊归属条目 id(可选;多结果联动时用,如「无人值守」一次返回「无人值守」+
 *                 「AI 审议」两个 bottom 胶囊,各自 id 指向各自注册条目,前端据此各自 apply /
 *                 cancel;为 null 时由调用方回退父条目 id)。
 */
public record SlashSelectionResult(String token, SlashDisplayPosition position, String id) {

    /** 便捷工厂:底部渲染(业务回调自定义展示,不写输入框);id 为 null,回退父条目 id。 */
    public static SlashSelectionResult bottom(String token) {
        return new SlashSelectionResult(token, SlashDisplayPosition.BOTTOM, null);
    }

    /** 便捷工厂:底部渲染并显式指定归属条目 id(多结果联动用)。 */
    public static SlashSelectionResult bottom(String token, String id) {
        return new SlashSelectionResult(token, SlashDisplayPosition.BOTTOM, id);
    }

    /** 便捷工厂:原地插入(与当前默认 select 行为一致);id 为 null,回退父条目 id。 */
    public static SlashSelectionResult inline(String token) {
        return new SlashSelectionResult(token, SlashDisplayPosition.INLINE, null);
    }
}