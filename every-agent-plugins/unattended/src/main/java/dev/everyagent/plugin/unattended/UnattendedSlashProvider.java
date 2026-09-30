package dev.everyagent.plugin.unattended;

import java.util.List;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.permission.TaskInfo;
import dev.everyagent.plugin.api.slash.SlashCancelHandler;
import dev.everyagent.plugin.api.slash.SlashCommandItem;
import dev.everyagent.plugin.api.slash.SlashSelectHandler;
import dev.everyagent.plugin.api.slash.SlashSelectionResult;

/**
 * 「/无人值守」命令来源(仿 {@code modelpool.ModelPoolSlashProvider}):
 * 用户选择后把「无人值守」胶囊(bottom 底部渲染)挂到本任务,业务 onSelect/onCancel
 * 用 taskId 读写任务级开关并随 meta.json 落盘。
 *
 * <p>选中 /无人值守 即开启任务级「无人值守」开关并落盘——{@code UnattendedToolInterceptor}
 * 在 ask_user 工具执行瞬间拦截调用、代替人工逐题选择第一个选项并以「题干：首选项」
 * 格式回传作答文本;{@code UnattendedAuthHandler} 在授权链上直接拒绝授权。
 * 本次任务后续所有轮次(含再运行)持续生效。开关随任务 meta.json 持久化;
 * 胶囊底部渲染,可随时 ✕ 取消(取消后置 false 并落盘)。
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
        SlashSelectHandler selectHandler = (item, taskId) -> {
            if (taskId != null && !taskId.isEmpty()) {
                TaskInfo t = services.task().get(taskId);
                if (t != null) {
                    t.metadata().put("unattended", true);
                    services.task().publishUpdated(taskId);
                }
            }
            return List.of(SlashSelectionResult.bottom(UnattendedToken.buildToken(), "unattended:on"));
        };
        SlashCancelHandler cancelHandler = (item, token, taskId) -> {
            if (taskId != null && !taskId.isEmpty()) {
                TaskInfo t = services.task().get(taskId);
                if (t != null) {
                    t.metadata().put("unattended", false);
                    services.task().publishUpdated(taskId);
                }
            }
        };
        return List.of(new SlashCommandItem(
                "unattended:on",
                "无人值守",
                subtitle,
                UNATTENDED_MENU_ICON,
                UNATTENDED_GROUP,
                UnattendedToken.buildToken(),
                selectHandler,
                cancelHandler));
    }
}
