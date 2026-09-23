package dev.everyagent.plugin.api.spi;

import java.nio.file.Path;
import java.util.List;

/**
 * 权限门最小接口 —— 插件经 {@link ToolContext#gate()} 或 {@code WorkerServices.gate()} 访问。
 *
 * <p>worker 的 {@code dev.everyagent.worker.tools.PermissionGate} 实现此接口。
 * 内置适配器可按需强转为具体类型以调用 {@code requirePath} / {@code requireCommand} 等方法
 * （这些方法引用 worker 内部类型 {@code TaskEntry}，不在此接口暴露）。
 */
public interface PermissionGate {

    /** 已授权外部根（realpath + 词法形态），供沙箱附加放行。 */
    List<Path> extraRoots(String taskId);

    /** 已授权的命令 EXEC 根（realpath），供命令执行器做 Windows Low 完整性标注。 */
    List<Path> execRoots(String taskId);

    /** 新一条用户输入到达：本轮（run）授权即失效（任务级不受影响）。 */
    void beginRun(String taskId);

    /** 任务终态：内存驱逐（任务级授权已在磁盘，再运行时 lazy 重载）。 */
    void untrack(String taskId);
}
