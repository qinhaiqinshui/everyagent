package dev.everyagent.plugin.aireview;

import java.util.List;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.slash.SlashCommandItem;
import dev.everyagent.plugin.api.slash.SlashSelectHandler;
import dev.everyagent.plugin.api.slash.SlashSelectionResult;

/**
 * 「/AI 审议」命令来源(仿 {@code modelpool.ModelPoolSlashProvider}):
 * 用户选择后把 AI 审议胶囊(bottom 底部渲染)挂到本任务。
 *
 * <p>开关真相源是<b>任务级 slash token</b>({@code ai.review})——选中即由 slash 层
 * ({@code SlashMethods.applyTaskToken})把 token 写入任务目录 {@code slash-tokens.json},
 * 「token 已写入」本身即承载开关状态;✕ 取消即由 {@code SlashMethods.cancelItem} 移除 token,
 * 开关自然关闭。授权链节点({@link AiReviewAuthHandler})经
 * {@code slashTokens().hasKind(...)} 按 token 判定,故<b>任意时刻(含任务终态 / 进程重启后)
 * 都生效</b>——不再写 {@code metadata["ai-review"]}(副本随 meta.json 落盘,任务不在内存时写入
 * 会被静默丢弃,曾造成「胶囊已取消、开关仍生效」的双写不一致)。
 * 本 provider 的 select/cancel 回调不再触碰任务实体 / metadata,只负责返回底部胶囊展示结果。
 */
public class AiReviewSlashProvider {

    /** `/` 菜单里 AI 审议候选的分组名(直接作为展示标题)。 */
    private static final String AI_REVIEW_GROUP = "授权";

    /** 候选在 `/` 菜单里的图标(内联 SVG,currentColor 上色:盾牌 + 对勾,表「安全审议」)。 */
    private static final String AI_REVIEW_MENU_ICON =
            "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\""
                    + " stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">"
                    + "<path d=\"M8 1.5 13 3.5v3.6c0 3.3-2.2 5.9-5 7.4-2.8-1.5-5-4.1-5-7.4V3.5L8 1.5Z\" />"
                    + "<path d=\"M5.8 8.2 7.2 9.6 10.2 6.4\" /></svg>";

    /** 返回 `/AI 审议` 候选项(选中即构造自包含 opaque 串 → 底部胶囊渲染,可取消)。 */
    public static List<SlashCommandItem> items(WorkerServices services) {
        String subtitle = "授权弹窗改为 AI 安全审议（本任务有效，底部可取消）";
        // 业务 onSelect:开关状态由「token 已写入」本身承载——用户选中后 token 由 slash 层
        // (SlashMethods.applyTaskToken)落盘,本回调只返回 bottom token 供底部渲染(不写输入框)。
        // 不再触碰 task().get(...)/metadata,故任务终态/重启后(不在内存时)也能正常开关。
        SlashSelectHandler selectHandler = (item, taskId) ->
                List.of(SlashSelectionResult.bottom(AiReviewToken.buildToken(), "ai-review:on"));
        // 业务 onCancel:不挂回调(构造时传 null → 默认 NOOP 空操作)——取消时 token 由 slash 层
        // (SlashMethods.cancelItem)从 slash-tokens.json 移除,开关自然关闭。
        // defaultSelected=true:新建任务(草稿)时默认选中本条目,前端预置底部胶囊(用户仍可 ✕ 取消)。
        return List.of(new SlashCommandItem(
                "ai-review:on",
                "AI 审议",
                subtitle,
                AI_REVIEW_MENU_ICON,
                AI_REVIEW_GROUP,
                AiReviewToken.buildToken(),
                selectHandler,
                null,
                true));
    }
}
