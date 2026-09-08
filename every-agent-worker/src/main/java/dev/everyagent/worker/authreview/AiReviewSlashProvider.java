package dev.everyagent.worker.authreview;

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
 * 「/AI 审议」命令来源(仿 {@code modelpool.ModelPoolSlashProvider}):
 * 用户选择后把 AI 审议胶囊(bottom 底部渲染)挂到本任务,业务 onSelect/onCancel
 * 用 taskId 读写任务级开关 {@code TaskEntry.aiReview} 并随 meta.json 落盘。
 *
 * <p>选中即开启<b>任务级</b>「授权弹窗改 AI 安全审议」开关并落盘——{@code PermissionGate}
 * 按 {@code t.aiReview} 分派(步骤 6 实现):开启后危险操作不再弹人工窗,改走 AI 审议
 * 自动放行/拦截。开关随任务 meta.json 持久化;胶囊底部渲染,可随时 ✕ 取消
 * (取消后置 false 并落盘,授权回人工弹窗)。可单独开启(不依赖无人值守)。
 *
 * <p>联动:选中「无人值守」时由 {@code UnattendedSlashProvider} 一次返回两个胶囊,
 * 其中「AI 审议」胶囊按本条目 id={@code ai-review:on} apply,同样走到本 provider 的
 * onSelect 置位——无需在无人值守侧硬编码置 aiReview。
 */
@Component
public class AiReviewSlashProvider {

    /** `/` 菜单里 AI 审议候选的分组名(直接作为展示标题)。 */
    private static final String AI_REVIEW_GROUP = "授权";

    /** 候选在 `/` 菜单里的图标(内联 SVG,currentColor 上色:盾牌 + 对勾,表「安全审议」)。 */
    private static final String AI_REVIEW_MENU_ICON =
            "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\""
                    + " stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">"
                    + "<path d=\"M8 1.5 13 3.5v3.6c0 3.3-2.2 5.9-5 7.4-2.8-1.5-5-4.1-5-7.4V3.5L8 1.5Z\" />"
                    + "<path d=\"M5.8 8.2 7.2 9.6 10.2 6.4\" /></svg>";

    private final TaskManager taskManager;

    public AiReviewSlashProvider(SlashCommandRegistry registry, TaskManager taskManager) {
        this.taskManager = taskManager;
        registry.registerProvider("ai-review", this::items);
    }

    /** 返回 `/AI 审议` 候选项(选中即构造自包含 opaque 串 → 底部胶囊渲染,可取消)。 */
    private List<SlashCommandItem> items() {
        String subtitle = "授权弹窗改为 AI 安全审议（本任务有效，底部可取消）";
        // 业务 onSelect:taskId 非空时用 runningTask 拿内存实体置位业务标记并落盘;
        // 返回 bottom token 供底部渲染(不写输入框),业务标记由注册方自行维护。
        SlashSelectHandler selectHandler = (item, taskId) -> {
            if (taskId != null && !taskId.isEmpty()) {
                TaskEntry t = taskManager.runningTask(taskId);
                if (t != null) {
                    t.aiReview = true; // 注册方写自己的业务标记
                    t.persist();       // 落盘 meta(persistHook → updateMeta)
                }
            }
            return List.of(SlashSelectionResult.bottom(AiReviewToken.buildToken(), "ai-review:on"));
        };
        // 业务 onCancel:taskId 非空时复位业务标记并落盘。
        SlashCancelHandler cancelHandler = (item, token, taskId) -> {
            if (taskId != null && !taskId.isEmpty()) {
                TaskEntry t = taskManager.runningTask(taskId);
                if (t != null) {
                    t.aiReview = false; // 注册方删自己的业务标记
                    t.persist();
                }
            }
        };
        // defaultSelected=true:新建任务(草稿)时默认选中本条目,前端预置底部胶囊(用户仍可 ✕ 取消)。
        return List.of(new SlashCommandItem(
                "ai-review:on",
                "AI 审议",
                subtitle,
                AI_REVIEW_MENU_ICON,
                AI_REVIEW_GROUP,
                AiReviewToken.buildToken(),
                selectHandler,
                cancelHandler,
                true));
    }
}
