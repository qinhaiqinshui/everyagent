package dev.everyagent.plugin.api.spi;

import dev.everyagent.plugin.api.exception.NotFoundException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 工作区路径沙箱（plugin-api 契约）—— fs.* / git.* 等 RPC 的工作区路径越界校验抽象。
 *
 * <p>每次 RPC 调用按 {@code workspace} 参数解析出工作区根后绑定一个本接口实例；
 * 所有相对路径经此解析并校验不逃逸工作区根（词法前缀 + realpath 双校验，防符号链接逃逸）。
 *
 * <p>worker 的 {@code dev.everyagent.worker.modules.Sandbox} 实现此接口；
 * 插件经 {@link WorkspaceManager#sandboxFor(String)} 获取实例，无需依赖 worker 具体类。
 *
 * <p>方法集仅暴露插件（如 git 插件）实际调用的能力，不做过度设计。
 */
public interface WorkspaceSandbox {

    /** 绑定的工作区根（normalize 后绝对路径）。 */
    Path root();

    /** 附加根（授权目录 + 系统只读根；空 = 无越界授权）。 */
    List<Path> extraRoots();

    /** 相对工作区根的显示路径（事件/返回值用 / 分隔）；工作区外（授权根内）返回绝对路径。 */
    String display(Path p) throws IOException;

    /** 解析已存在路径：normalize + realpath，双前缀校验（工作区根 ∨ 任一授权附加根）。 */
    Path resolveExisting(String rel) throws IOException, NotFoundException;

    /** 解析写入目标（可不存在）：父目录（按需创建）realpath + 文件名，防中间符号链接逃逸。 */
    Path resolveTarget(String rel) throws IOException;

    /**
     * 解析「可能存在也可能不存在」的路径（git.discard 恢复已删除/丢失文件等场景）：
     * normalize + 词法前缀校验；路径存在则 realpath 防符号链接逃逸，不存在则对最近的
     * 已存在祖先做 realpath 校验。不创建任何目录。
     */
    Path resolveLoose(String rel) throws IOException;

    /** 破坏性操作（delete/move 源）禁止作用于工作区根本身或任一授权根本身。 */
    void requireNotRoot(Path p);
}
