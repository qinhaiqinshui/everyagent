package dev.everyagent.plugin.unattended;

import java.util.List;

import org.springframework.stereotype.Component;

import dev.everyagent.worker.slash.SlashCancelHandler;
import dev.everyagent.worker.slash.SlashCommandItem;
import dev.everyagent.worker.slash.SlashCommandRegistry;
import dev.everyagent.worker.slash.SlashSelectHandler;
import dev.everyagent.worker.slash.SlashSelectionResult;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskManager;

/**
 * 「/无人值守」命令来源(仿 {@code modelpool.ModelPoolSlashProvider}):
 * 用户选择后把「无人值守」胶囊(bottom 底部渲染)挂到本任务,业务 onSelect/onCancel
 * 用 taskId 读写任务级开关 {@code TaskEntry.unattended} 并随 meta.json 落盘。
 *
 * <p>选中 /无人值守 即开启任务级「无人值守」开关并落盘——{@code UnattendedToolInterceptor}
 * 在 ask_user 工具执行瞬间拦截调用、代替人工逐题选择第一个选项并以「题干：首选项」
 * 格式回传作答文本;{@code UnattendedAuthHandler} 在授权链上直接拒绝授权。
 * 本次任务后续所有轮次(含再运行)持续生效。开关随任务 meta.json 持久化;
 * 胶囊底部渲染,可随时 ✕ 取消(取消后置 false 并落盘)。
 */
@Component
public class UnattendedSlashProvider {

    /** `/` 菜单里无人值守候选的分组名(直接作为展示标题)。 */
    private static final String UNATTENDED_GROUP = "授权";

    /** 候选在 `/` 菜单里的图标(内联 SVG,currentColor 上色:月亮,表「无人值守」)。 */
    private static final String UNATTENDED_MENU_ICON =
            "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\""
                    + " stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">"
                    + "<path d=\"M13.5 9.5A6 6 0 0 1 6.5 2.5a6 6 0 1 0 7 7Z\" /></svg>";

    private final TaskManager taskManager;

    public UnattendedSlashProvider(SlashCommandRegistry registry, TaskManager taskManager) {
        this.taskManager = taskManager;
        registry.registerProvider("unattended", this::items);
    }

    /** 返回 `/无人值守` 候选项(选中即构造自包含 opaque 串 → 底部渲染胶囊,可取消)。 */
    private List<SlashCommandItem> items() {
        String subtitle = "无人在场时 ask_user 自动作答、授权一律拒绝（本任务有效，底部可取消）";
        SlashSelectHandler selectHandler = (item, taskId) -> {
            if (taskId != null && !taskId.isEmpty()) {
                TaskEntry t = taskManager.runningTask(taskId);
                if (t != null) {
                    t.taskFlags.put("unattended", true);
                    t.persist();
                }
            }
            return List.of(SlashSelectionResult.bottom(UnattendedToken.buildToken(), "unattended:on"));
        };
        SlashCancelHandler cancelHandler = (item, token, taskId) -> {
            if (taskId != null && !taskId.isEmpty()) {
                TaskEntry t = taskManager.runningTask(taskId);
                if (t != null) {
                    t.taskFlags.put("unattended", false);
                    t.persist();
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
