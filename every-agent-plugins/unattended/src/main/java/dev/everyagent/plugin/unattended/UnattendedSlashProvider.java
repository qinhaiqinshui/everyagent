package dev.everyagent.plugin.unattended;

import java.util.List;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.slash.SlashCommandItem;
import dev.everyagent.plugin.api.slash.SlashSelectHandler;
import dev.everyagent.plugin.api.slash.SlashSelectionResult;

/**
 * 「/无人值守」命令来源(仿 {@code modelpool.ModelPoolSlashProvider}):
 * 用户选择后把「无人值守」胶囊(bottom 底部渲染)挂到本任务。
 *
 * <p>选中 /无人值守 即开启任务级「无人值守」开关——{@code UnattendedToolInterceptor}
 * 在 ask_user 工具执行瞬间拦截调用、代替人工逐题选择第一个选项并以「题干：首选项」
 * 格式回传作答文本;{@code UnattendedAuthHandler} 在授权链上直接拒绝授权。
 * 本次任务后续所有轮次(含再运行)持续生效。
 *
 * <p>联动:选中 /无人值守 时同时联动挂上「AI 审议」胶囊(授权一律拒绝后需后者接管审议)。
 * 本插件只声明联动条目 id({@code ai-review:on}),第二个胶囊的 token 由 worker 斜杠层按 id
 * 从注册表取该条目自身 insertText 生成,故本插件<b>不依赖</b> ai-review 插件(未启用时联动
 * 自然降级为单胶囊)。
 *
 * <p>开关<b>唯一真相源</b>是任务目录下的 slash token 存储({@code slash-tokens.json},
 * 斜杠层自管;kind={@link UnattendedToken#KIND}):select 只返回 bottom 胶囊,token 由
 * slash 层落盘;cancel 移除 token 即关闭。不再往任务 {@code metadata} 写副本
 * (非运行态写入会被静默丢弃,曾造成「胶囊已取消、开关仍生效」的双写不一致)。
 */
public class UnattendedSlashProvider {

    /** `/` 菜单里无人值守候选的分组名(直接作为展示标题)。 */
    private static final String UNATTENDED_GROUP = "授权";

    /** 候选在 `/` 菜单里的图标(内联 SVG,currentColor 上色:月亮,表「无人值守」)。 */
    private static final String UNATTENDED_MENU_ICON =
            "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\""
                    + " stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">"
                    + "<path d=\"M13.5 9.5A6 6 0 0 1 6.5 2.5a6 6 0 1 0 7 7Z\" /></svg>";

    /** 返回 `/无人值守` 候选项(选中即构造自包含 opaque 串 → 底部渲染胶囊,可取消)。 */
    public static List<SlashCommandItem> items(WorkerServices services) {
        String subtitle = "无人在场时 ask_user 自动作答、授权一律拒绝（本任务有效，底部可取消）";
        // 真相源 = slash token:select 只返回 bottom 胶囊,token 由 slash 层落盘;
        // 不再写任务 metadata 副本(非运行态 get() 返回 null → 静默跳过,开关不生效)。
        // 联动:额外声明应用条目 ai-review:on —— 选中「/无人值守」时同时挂上「AI 审议」胶囊
        // (前者开启后授权一律拒绝,需后者接管审议)。第二个 bottom 胶囊由 worker 斜杠层按 id 从
        // 注册表取「AI 审议」条目**自身**的 insertText(即 ai-review 插件构造的 token)生成,
        // 故本插件不依赖 ai-review 插件、也不自拼其 token;ai-review 未启用时该条目不在注册表,
        // 联动自然降级为单胶囊。
        SlashSelectHandler selectHandler = (item, taskId) -> List.of(
                SlashSelectionResult.bottom(UnattendedToken.buildToken(), "unattended:on",
                        List.of("ai-review:on")));
        return List.of(new SlashCommandItem(
                "unattended:on",
                "无人值守",
                subtitle,
                UNATTENDED_MENU_ICON,
                UNATTENDED_GROUP,
                UnattendedToken.buildToken(),
                selectHandler,
                // cancel 无业务副作用:token 移除由 slash 层负责(未挂回调 = 默认 NOOP)
                null));
    }
}
