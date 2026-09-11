package dev.everyagent.worker.powershell;

import java.util.List;

import org.springframework.stereotype.Component;

import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.slash.SlashCancelHandler;
import dev.everyagent.worker.slash.SlashCommandItem;
import dev.everyagent.worker.slash.SlashCommandRegistry;
import dev.everyagent.worker.slash.SlashSelectHandler;
import dev.everyagent.worker.slash.SlashSelectionResult;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskManager;

/**
 * 「/启用powershell」命令来源(仿 {@code network.NetworkSlashProvider}):
 * 用户选择后把「启用powershell」胶囊(bottom 底部渲染)挂到本任务,业务 onSelect/onCancel
 * 用 taskId 读写任务级开关 {@code TaskEntry.powershellEnabled} 并随 meta.json 落盘。
 *
 * <p><b>仅 WSL+Linux 沙箱后端注册</b>({@link OsSandbox#isWslBackend()},即
 * wsl-bwrap / wsl-direct):WSL 后端命令方言是 bash,AI 默认只有 bash 工具;选中本条目后
 * 主/子 agent 工具集在 bash 之外<b>追加</b>{@code powershell} 工具(经发行版内 pwsh 执行,
 * 须 {@code worker.sandbox.wsl.pwsh-enabled=true}),让 AI 同时拥有 powershell 与 bash。
 * <b>windows-mic(Windows+ACL)后端不注册</b>:该后端命令工具本就是 PowerShellTool,
 * 无「追加 powershell」需求,故 `/` 菜单不出现本条目。
 *
 * <p>开关随任务 meta.json 持久化;胶囊底部渲染,可随时 ✕ 取消(取消后置 false 并落盘,
 * 后续轮次/再运行工具集不再追加 powershell)。agent 工具集在<b>每次运行</b>(每条用户输入
 * 构建主/子 agent 实体)时按 {@code t.powershellEnabled} 实时重建,故选中/取消从下一轮
 * (下一条用户输入)或再运行起生效。
 */
@Component
public class PowerShellEnableSlashProvider {

    /** `/` 菜单里启用 powershell 候选的分组名(直接作为展示标题)。 */
    private static final String GROUP = "工具";

    /** 候选在 `/` 菜单里的图标(内联 SVG,currentColor 上色:终端窗口,表「命令工具」)。 */
    private static final String MENU_ICON =
            "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\""
                    + " stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">"
                    + "<rect x=\"1.5\" y=\"2.5\" width=\"13\" height=\"11\" rx=\"1.5\"/>"
                    + "<path d=\"M4 5.5 6.5 8 4 10.5M8.5 10.5H12\"/></svg>";

    private final TaskManager taskManager;

    public PowerShellEnableSlashProvider(SlashCommandRegistry registry, TaskManager taskManager,
            OsSandbox sandbox) {
        this.taskManager = taskManager;
        // 仅 WSL+Linux 后端注册;windows-mic(Windows+ACL)后端不注册
        // (该后端命令工具本就是 PowerShellTool,无「追加 powershell」需求)。
        if (sandbox.isWslBackend()) {
            registry.registerProvider("powershell-enable", this::items);
        }
    }

    /** 返回 `/启用powershell` 候选项(选中即构造自包含 opaque 串 → 底部胶囊渲染,可取消)。 */
    private List<SlashCommandItem> items() {
        String subtitle = "为 WSL 沙箱追加 powershell 工具，与 bash 并存（本任务有效，底部可取消）";
        // 业务 onSelect:taskId 非空时用 runningTask 拿内存实体置位业务标记并落盘;
        // 返回 bottom token 供底部渲染(不写输入框),业务标记由注册方自行维护。
        SlashSelectHandler selectHandler = (item, taskId) -> {
            if (taskId != null && !taskId.isEmpty()) {
                TaskEntry t = taskManager.runningTask(taskId);
                if (t != null) {
                    t.powershellEnabled = true; // 注册方写自己的业务标记
                    t.persist();                // 落盘 meta(persistHook → updateMeta)
                }
            }
            return List.of(SlashSelectionResult.bottom(PowerShellEnableToken.buildToken()));
        };
        // 业务 onCancel:taskId 非空时复位业务标记并落盘。
        SlashCancelHandler cancelHandler = (item, token, taskId) -> {
            if (taskId != null && !taskId.isEmpty()) {
                TaskEntry t = taskManager.runningTask(taskId);
                if (t != null) {
                    t.powershellEnabled = false; // 注册方删自己的业务标记
                    t.persist();
                }
            }
        };
        return List.of(new SlashCommandItem(
                "powershell-enable:on",
                "启用powershell",
                subtitle,
                MENU_ICON,
                GROUP,
                PowerShellEnableToken.buildToken(),
                selectHandler,
                cancelHandler));
    }
}
