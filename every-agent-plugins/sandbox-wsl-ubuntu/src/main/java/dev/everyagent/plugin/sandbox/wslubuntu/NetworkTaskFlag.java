package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.WorkerServices;

/**
 * 「本任务禁用网络」开关的只读入口(任务级状态,归本插件自管)。
 *
 * <p><b>唯一真相源</b>是任务级 {@code network.access} slash token(落盘于任务目录下的
 * {@code slash-tokens.json},由 slash 层写入 / 移除):选中 /禁用网络 即写入该 token
 * (开关开),✕ 取消即移除(开关关)。读取经 {@link WorkerServices#slashTokens()} 按 kind
 * 查询,恒以磁盘为准 —— 运行中 / 终态 / 进程重启后一致,不依赖任何内存态任务。
 *
 * <p>worker 核心不感知该 token 语义,也不持有禁网状态 —— 只有 wsl-ubuntu 后端能在发行版内
 * {@code unshare -n} 真正断网,这个能力整体归本插件(架构 §7.10 网络策略)。
 *
 * <p>读:{@link WslUbuntuCommandExecutor} 每次 spawn 经本类实时读(故运行中选中/取消
 * 对本轮后续命令即时生效)。
 */
public final class NetworkTaskFlag {

    private NetworkTaskFlag() {
    }

    /**
     * 读:该任务是否已开启禁网 —— 查任务级 {@code network.access} token 是否存在。
     *
     * <p>services 或 subjectId 缺失(草稿态 / 无任务上下文)按未开启处理,绝不 NPE。
     */
    public static boolean isOn(WorkerServices services, String subjectId) {
        if (services == null || subjectId == null || subjectId.isBlank()) {
            return false;
        }
        return services.slashTokens().hasKind(subjectId, NetworkToken.KIND);
    }
}
