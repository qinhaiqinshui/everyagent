package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.permission.TaskInfo;
import dev.everyagent.plugin.api.task.TaskService;

/**
 * 「本任务禁用网络」开关的唯一读写入口(任务级状态,归本插件自管)。
 *
 * <p>状态存在 worker 通用任务槽 {@code TaskInfo.metadata()} 里,key =
 * {@link #META_KEY},随任务 meta.json 落盘、再运行仍保持;worker 核心不感知该 key,
 * 也不持有禁网状态 —— 因为只有 wsl-ubuntu 后端能在发行版内 {@code unshare -n}
 * 真正断网,这个能力整体归本插件(架构 §7.10 网络策略)。
 *
 * <p>写:{@link NetworkSlashProvider} 的 onSelect/onCancel;
 * 读:{@link WslUbuntuCommandExecutor} 每次 spawn 实时读(故运行中选中/取消
 * 对本轮后续命令即时生效)。
 */
public final class NetworkTaskFlag {

    /** 任务 metadata 中的键(Boolean;缺失或 false = 放行网络)。 */
    public static final String META_KEY = "networkBlocked";

    private NetworkTaskFlag() {
    }

    /** 读:该任务是否已开启禁网(task 为 null = 任务不可寻,按未开启处理)。 */
    public static boolean isOn(TaskInfo task) {
        return task != null && Boolean.TRUE.equals(task.metadata().get(META_KEY));
    }

    /**
     * 写:置位/复位任务级禁网开关并广播 {@code task.updated}(meta 由 slash 层与任务
     * 生命周期统一落盘)。taskId 为空(草稿态)或任务不可寻时不做任何写入。
     */
    public static void set(TaskService tasks, String taskId, boolean blocked) {
        if (tasks == null || taskId == null || taskId.isEmpty()) {
            return;
        }
        TaskInfo t = tasks.get(taskId);
        if (t == null) {
            return;
        }
        t.metadata().put(META_KEY, blocked);
        tasks.publishUpdated(taskId);
    }
}
