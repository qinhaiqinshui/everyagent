package dev.everyagent.plugin.api.spi;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 沙箱后端接口 —— {@link SandboxProvider#create} 的产物。
 *
 * <p>极简化设计：沙箱后端只负责<strong>挂载</strong>和<strong>工作区生命周期</strong>，
 * 不执行命令、不翻译路径、不涉及工具注册、不涉及授权策略。
 *
 * <p>核心通过 {@code SandboxPathRegistry} 做路径翻译中间人：
 * 核心调 {@link #mount} 拿到映射关系后自己查表翻译，不依赖沙箱。
 * 命令执行由沙箱插件自己的 CommandExecutor 直接构造进程执行。
 */
public interface SandboxBackend {

    /**
     * 批量挂载宿主路径到沙箱内，返回映射表。
     *
     * <p>核心一次性传入所有需要挂载的路径（工作区根 + 外部授权根 + skills 根等），
     * 沙箱返回每个路径的沙箱内路径。
     *
     * <ul>
     *   <li>DIRECT / windows-mic: 不挂载，返回原路径。</li>
     *   <li>wsl-ubuntu: 批量 drvfs 挂载，返回 /c/Users/... 形态。</li>
     *   <li>docker: 批量 bind mount，返回 /workspace 等挂载点。</li>
     * </ul>
     *
     * <p>幂等：重复调用相同路径不重复挂载。
     *
     * @param requests 挂载请求列表
     * @return 宿主路径 → 沙箱内路径 的映射表
     */
    default Map<Path, String> mount(List<MountRequest> requests) {
        Map<Path, String> result = new LinkedHashMap<>();
        for (MountRequest req : requests) {
            result.put(req.hostPath(), req.hostPath().toString());
        }
        return result;
    }

    /**
     * 工作区被删除时调用;后端 best-effort 清理挂载等;默认 no-op。
     *
     * @param root 被删除的工作区根路径
     */
    default void onWorkspaceRemoved(Path root) {
    }

    /** 后端 id（与 {@link SandboxProvider#id()} 一致）。 */
    String id();

    /** 单个挂载请求。 */
    record MountRequest(Path hostPath, Access access) {
    }

    /** 挂载访问语义。 */
    enum Access {
        READ_ONLY,
        READ_WRITE
    }
}
