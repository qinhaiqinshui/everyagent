package dev.everyagent.worker.network;

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
 * 「/禁用网络」命令来源(仿 {@code modelpool.ModelPoolSlashProvider}):
 * 用户选择后把「禁用网络」胶囊(bottom 底部渲染)挂到本任务,业务 onSelect/onCancel
 * 用 taskId 读写任务级开关 {@code TaskEntry.networkBlocked} 并随 meta.json 落盘。
 *
 * <p>选中即开启<b>任务级</b>「禁止访问网络」开关并落盘——{@code CommandExecutor}
 * 每次 spawn 实时读开关(volatile):本次任务后续所有轮次(含再运行)持续生效,
 * 且运行中选中/取消<b>对当前轮次后续命令即时生效</b>。开关随任务 meta.json 持久化;
 * 胶囊底部渲染,可随时 ✕ 取消(取消后置 false 并落盘)。
 * worker 级默认 {@code sandbox.allow-network=true}(放行网络),本开关只对单任务收窄。
 */
@Component
public class NetworkSlashProvider {

    /** `/` 菜单里网络候选的分组名(直接作为展示标题)。 */
    private static final String NETWORK_GROUP = "禁用网络";

    /** 候选在 `/` 菜单里的图标(内联 SVG,currentColor 上色:地球,表「网络」)。 */
    private static final String NETWORK_MENU_ICON =
            "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\""
                    + " stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">"
                    + "<circle cx=\"8\" cy=\"8\" r=\"6\"/><path d=\"M2 8h12M8 2c2 2 3 4 3 6s-1 4-3 6c-2-2-3-4-3-6s1-4 3-6Z\"/></svg>";

    private final TaskManager taskManager;

    public NetworkSlashProvider(SlashCommandRegistry registry, TaskManager taskManager) {
        this.taskManager = taskManager;
        registry.registerProvider("network", this::items);
    }

    /** 返回 `/禁用网络` 候选项(选中即构造自包含 opaque 串 → 底部胶囊渲染,可取消)。 */
    private List<SlashCommandItem> items() {
        String subtitle = "禁止本任务访问网络（默认放行，本任务有效，底部可取消）";
        // 业务 onSelect:taskId 非空时用 runningTask 拿内存实体置位业务标记并落盘;
        // 返回 bottom token 供底部渲染(不写输入框),业务标记由注册方自行维护。
        SlashSelectHandler selectHandler = (item, taskId) -> {
            if (taskId != null && !taskId.isEmpty()) {
                TaskEntry t = taskManager.runningTask(taskId);
                if (t != null) {
                    t.networkBlocked = true; // 注册方写自己的业务标记
                    t.persist();             // 落盘 meta(persistHook → updateMeta)
                }
            }
            return List.of(SlashSelectionResult.bottom(NetworkToken.buildToken()));
        };
        // 业务 onCancel:taskId 非空时复位业务标记并落盘。
        SlashCancelHandler cancelHandler = (item, token, taskId) -> {
            if (taskId != null && !taskId.isEmpty()) {
                TaskEntry t = taskManager.runningTask(taskId);
                if (t != null) {
                    t.networkBlocked = false; // 注册方删自己的业务标记
                    t.persist();
                }
            }
        };
        return List.of(new SlashCommandItem(
                "network:on",
                "禁用网络",
                subtitle,
                NETWORK_MENU_ICON,
                NETWORK_GROUP,
                NetworkToken.buildToken(),
                selectHandler,
                cancelHandler));
    }
}
