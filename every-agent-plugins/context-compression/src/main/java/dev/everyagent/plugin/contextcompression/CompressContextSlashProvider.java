package dev.everyagent.plugin.contextcompression;

import java.util.List;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.slash.SlashCommandItem;

/**
 * 「/压缩上下文」命令来源(仿 task-wait 的 {@code WaitTaskSlashProvider})。
 *
 * <p>选中后走<b>默认 select 行为</b>:把 {@code insertText}(opaque token)原地插入
 * 输入框,渲染为普通<b>内联胶囊</b>(与 @文件引用、等待任务同形态),不挂 selectHandler、
 * 不写任务级存储。用户随消息一起发送,{@code CompressContextRequestNode} 扫到胶囊即登记
 * 一次性压缩请求,{@link ContextCompressionAdvisor} 在本 run 首个模型请求强制压缩
 * (不受自动触发阈值约束)并消费请求——用后即弃,胶囊随消息走,不常驻、不重复触发。
 *
 * <p>与无人值守/AI 审议等<b>持久开关</b>(bottom 底部胶囊 + 任务级 slash-tokens.json)
 * 划清边界:本命令是<b>一次性动作</b>,交互形态与生命周期都是「普通输入框胶囊」。
 */
public class CompressContextSlashProvider {

    /** `/` 菜单里压缩上下文候选的分组名(直接作为展示标题)。 */
    private static final String COMPRESS_CONTEXT_GROUP = "上下文";

    /** 候选在 `/` 菜单里的图标(内联 SVG,currentColor 上色:压缩/收拢箭头)。 */
    private static final String COMPRESS_CONTEXT_MENU_ICON =
            "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\""
                    + " stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">"
                    + "<path d=\"M4 6.5L8 10.5L12 6.5\" />"
                    + "<path d=\"M4 3h8\" /></svg>";

    /**
     * 返回 `/压缩上下文` 候选项:无自定义回调,默认 select 把 {@code insertText}
     * (自包含 opaque token)原地插入输入框 → 内联胶囊,可 ✕ 取消(纯本地,无服务端状态)。
     */
    public static List<SlashCommandItem> items(WorkerServices services) {
        return List.of(new SlashCommandItem(
                "compress-context:now",
                "压缩上下文",
                "随本条消息发送时强制压缩上下文（一次性，用后即逝）",
                COMPRESS_CONTEXT_MENU_ICON,
                COMPRESS_CONTEXT_GROUP,
                CompressContextToken.buildToken(),
                null,
                null,
                false));
    }
}

