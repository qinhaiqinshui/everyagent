package dev.everyagent.plugin.api.slash;

import java.util.List;

/**
 * 选中回调的返回结果:告诉前端「插什么、插到哪」。
 *
 * @param token    要交给前端的内容串(可为 opaque token 或纯文本;BOTTOM 场景可为空)。
 * @param position 展示位置({@link SlashDisplayPosition#INLINE}=原地插入,
 *                 {@link SlashDisplayPosition#BOTTOM}=渲染在输入区底部)。
 * @param id       胶囊归属条目 id(可选;多结果联动时用,如「无人值守」一次返回「无人值守」+
 *                 「AI 审议」两个 bottom 胶囊,各自 id 指向各自注册条目,前端据此各自 apply /
 *                 cancel;为 null 时由调用方回退父条目 id)。
 * @param chainIds 本次选中额外联动应用的已注册条目 id 列表;worker 斜杠层按 id 取该条目自身
 *                 {@code insertText} 作为额外 bottom 胶囊,token 由归属插件构造,跨插件零耦合;
 *                 空/null = 无联动。
 */
public record SlashSelectionResult(String token, SlashDisplayPosition position, String id, List<String> chainIds) {

    /** 3 参便捷构造(既有调用方零改动):无联动,{@code chainIds = List.of()}。 */
    public SlashSelectionResult(String token, SlashDisplayPosition position, String id) {
        this(token, position, id, List.of());
    }

    /** 便捷工厂:底部渲染(业务回调自定义展示,不写输入框);id 为 null,回退父条目 id。 */
    public static SlashSelectionResult bottom(String token) {
        return new SlashSelectionResult(token, SlashDisplayPosition.BOTTOM, null, List.of());
    }

    /** 便捷工厂:底部渲染并显式指定归属条目 id(多结果联动用)。 */
    public static SlashSelectionResult bottom(String token, String id) {
        return new SlashSelectionResult(token, SlashDisplayPosition.BOTTOM, id, List.of());
    }

    /**
     * 便捷工厂:底部渲染 + 指定归属条目 id + 声明联动条目(chainIds → 额外 bottom 胶囊)。
     * 联动 token 由 worker 斜杠层从注册表按 id 反查各条目自身 {@code insertText} 生成,
     * 归属插件无需依赖被联动插件。
     */
    public static SlashSelectionResult bottom(String token, String id, List<String> chainIds) {
        return new SlashSelectionResult(token, SlashDisplayPosition.BOTTOM, id, chainIds);
    }

    /** 便捷工厂:原地插入(与当前默认 select 行为一致);id 为 null,回退父条目 id。 */
    public static SlashSelectionResult inline(String token) {
        return new SlashSelectionResult(token, SlashDisplayPosition.INLINE, null, List.of());
    }
}
