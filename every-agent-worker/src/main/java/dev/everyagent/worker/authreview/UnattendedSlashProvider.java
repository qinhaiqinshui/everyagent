package dev.everyagent.worker.authreview;

import java.util.List;

import org.springframework.stereotype.Component;

import dev.everyagent.worker.slash.SlashCancelHandler;
import dev.everyagent.worker.slash.SlashCommandItem;
import dev.everyagent.worker.slash.SlashCommandRegistry;
import dev.everyagent.worker.slash.SlashSelectHandler;
import dev.everyagent.worker.slash.SlashSelectionResult;
import dev.everyagent.worker.slash.SlashTokenEncoder;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskManager;

/**
 * 「/无人值守」命令来源(仿 {@code modelpool.ModelPoolSlashProvider}):
 * 用户选择后把「无人值守」胶囊(bottom 底部渲染)挂到本任务,业务 onSelect/onCancel
 * 用 taskId 读写任务级开关 {@code TaskEntry.unattended} 并随 meta.json 落盘。
 *
 * <p><b>联动开启 AI 审议(一次返回两个胶囊)</b>:onSelect 返回
 * {@code List.of(bottom(UnattendedToken), bottom(AiReviewToken))}——前端循环 apply,
 * 「无人值守」胶囊置 {@code unattended=true}、「AI 审议」胶囊按 id={@code ai-review:on}
 * 走到 {@link AiReviewSlashProvider} 置 {@code aiReview=true}。本 provider <b>不硬编码</b>
 * {@code t.aiReview=true},联动由前端把 AI 审议胶囊也 apply 实现;因此事后 ✕ 关掉 AI 审议
 * 胶囊只复位 aiReview,无人值守仍在(开启时联动、事后可拆分)。
 *
 * <p>选中 /无人值守 即开启任务级「无人值守」开关并落盘——{@code UnattendedModeAdvisor}
 * 读取后,本次任务后续所有轮次(含再运行)持续生效:剥离 ask_user 工具 + 注入无人值守提示词。
 * 开关随任务 meta.json 持久化;胶囊底部渲染,可随时 ✕ 取消(取消后置 false 并落盘)。
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

    /** 返回 `/无人值守` 候选项(选中即构造自包含 opaque 串 → 底部渲染两个胶囊,可取消)。 */
    private List<SlashCommandItem> items() {
        String subtitle = "无人在场时授权交 AI 审议；同时开启 AI 审议（本任务有效，底部可取消）";
        // 业务 onSelect:taskId 非空时用 runningTask 拿内存实体置位业务标记并落盘。
        // 联动:一次返回两个 bottom 胶囊 —— 前端各自 apply 各自置位(本侧只置 unattended)。
        SlashSelectHandler selectHandler = (item, taskId) -> {
            if (taskId != null && !taskId.isEmpty()) {
                TaskEntry t = taskManager.runningTask(taskId);
                if (t != null) {
                    t.unattended = true; // 注册方写自己的业务标记
                    t.persist();         // 落盘 meta(persistHook → updateMeta)
                }
            }
            return List.of(
                    SlashSelectionResult.bottom(UnattendedToken.buildToken(), "unattended:on"),
                    SlashSelectionResult.bottom(AiReviewToken.buildToken(), "ai-review:on"));
        };
        // 业务 onCancel:按被取消的 opaque token 反查 kind,各自复位并落盘——
        // ✕「无人值守」胶囊 → unattended=false;✕「AI 审议」胶囊 → aiReview=false。
        SlashCancelHandler cancelHandler = (item, token, taskId) -> {
            if (taskId == null || taskId.isEmpty()) {
                return;
            }
            TaskEntry t = taskManager.runningTask(taskId);
            if (t == null) {
                return;
            }
            SlashTokenEncoder.ParsedToken parsed = SlashTokenEncoder.parseToken(token);
            String kind = parsed == null ? null : parsed.kind();
            if (UnattendedToken.KIND.equals(kind)) {
                t.unattended = false;
                t.persist();
            } else if (AiReviewToken.KIND.equals(kind)) {
                t.aiReview = false;
                t.persist();
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
