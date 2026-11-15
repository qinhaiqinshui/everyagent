package dev.everyagent.plugin.contextcompression;

import java.util.List;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.slash.SlashCommandItem;
import dev.everyagent.plugin.api.slash.SlashSelectHandler;
import dev.everyagent.plugin.api.slash.SlashSelectionResult;

/**
 * 「/压缩上下文」命令来源(仿 {@code UnattendedSlashProvider}):
 * 用户选择后把「压缩上下文」胶囊(bottom 底部渲染)挂到本任务。
 *
 * <p>选中 /压缩上下文 即<b>请求一次手动压缩</b>——token 由 slash 层
 * ({@code SlashMethods.applyTaskToken})写入任务目录 {@code slash-tokens.json},
 * {@link ContextCompressionAdvisor} 在下一次模型请求时检测到该 token 即强制压缩上下文
 * (不受自动触发阈值约束),压缩完成后消费(移除)该 token。
 * 本 provider 的 select 回调只返回 bottom 胶囊,不触碰任务实体。
 *
 * <p>与无人值守/AI 审议等<b>持久开关</b>不同:本命令是<b>一次性动作</b>,
 * token 用后自动消失,需要再次压缩时重新选择即可。
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

    /** 返回 `/压缩上下文` 候选项(选中即构造自包含 opaque 串 → 底部胶囊渲染,可取消)。 */
    public static List<SlashCommandItem> items(WorkerServices services) {
        String subtitle = "下次发送消息时强制压缩上下文（一次性，用后自动消失）";
        SlashSelectHandler selectHandler = (item, taskId) ->
                List.of(SlashSelectionResult.bottom(CompressContextToken.buildToken(), "compress-context:now"));
        return List.of(new SlashCommandItem(
                "compress-context:now",
                "压缩上下文",
                subtitle,
                COMPRESS_CONTEXT_MENU_ICON,
                COMPRESS_CONTEXT_GROUP,
                CompressContextToken.buildToken(),
                selectHandler,
                null,
                false));
    }
}
