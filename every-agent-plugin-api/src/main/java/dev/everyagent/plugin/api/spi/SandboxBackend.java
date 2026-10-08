package dev.everyagent.plugin.api.spi;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 沙箱后端接口 —— {@link SandboxProvider#create} 的产物。
 *
 * <p><b>职责边界（§7.8 / §7.10 定案）</b>：沙箱后端只表达「对沙箱做了什么」——
 * <b>{@link #grant} 让某些宿主路径在沙箱世界里可访问，{@link #revoke} 撤销</b>；
 * <b>何时调用由上层决定</b>（沙箱不感知任务 / 工作区语义，故不订阅任何领域事件）。
 * 后端不执行命令、不涉及工具注册、不做授权策略判定。
 *
 * <p><b>路径翻译是沙箱世界的纯属性</b>：{@link #toSandbox}/{@link #toHost} 是
 * <b>无副作用的纯查询</b>，默认恒等（不需要翻译的后端零成本）。上层无条件调用一次，
 * 不需要判断「这个后端是否需要翻译」。
 *
 * <p><b>映射基准由 grant 确立</b>：一个宿主路径在沙箱里长什么样，取决于它被授权 /
 * 挂载到哪里（wsl 的 drvfs 挂载天然同时确立「可访问」与「映射」；windows ACE 只涉
 * 权限，映射为恒等）。故翻译不是独立概念，而是授权的衍生物。
 *
 * <p><b>幂等与重放</b>：{@link #grant}/{@link #revoke} 是幂等的「期望状态」声明。
 * worker 是唯一真相源、沙箱侧是缓存，会话建立 / 启动时由上层重放当前生效集合，
 * 以解决「授权早于容器存在」与重启漂移。
 */
public interface SandboxBackend {

    /** 后端 id（与 {@link SandboxProvider#id()} 一致）。 */
    String id();

    // ── 效果层：上层决定「何时」，幂等，可重放 ──────────────────────────

    /**
     * 声明这些宿主路径在沙箱内可访问（建立权限 + 映射基准）；幂等。
     *
     * <p><b>best-effort 且不放大（§7.8 P5）</b>：若某授权单元无法在请求粒度上落地
     * （典型：<b>待建文件</b>——创建需要父目录写权限），后端应<b>跳过并记日志</b>，
     * <b>绝不自动放大到父目录</b>。该路径仍可经 worker 的 file 工具通道访问。
     */
    default void grant(List<PathGrant> grants) {
    }

    /**
     * 撤销这些宿主路径的权限与映射；幂等（对未授权的路径静默忽略）。
     *
     * <p>只带路径、不带任务 / 工作区语义——是否还有人需要该根由<b>上层聚合判断</b>。
     * 物理回收不得打断可能存活的子进程，失败仅记 WARN。
     */
    default void revoke(List<Path> hostPaths) {
    }

    // ── 查询层：纯函数，无副作用，默认恒等 ─────────────────────────────

    /** 宿主路径在沙箱世界里的形态；默认恒等（= 不需要翻译的后端）。 */
    default String toSandbox(Path hostPath) {
        return hostPath.toString();
    }

    /** 沙箱内形态还原为宿主路径（模型回显 / fs 工具入参用，best-effort）；默认恒等。 */
    default Path toHost(String sandboxPath) {
        return Path.of(sandboxPath);
    }

    // ── 兼容层（迁移期保留，阶段 C 删除） ───────────────────────────────

    /**
     * 批量挂载宿主路径到沙箱内，返回映射表。
     *
     * @deprecated 兼两职（路径映射 + 权限登记）导致触发时机错位与粒度错位；
     *             改用 {@link #grant}（效果）+ {@link #toSandbox}（查询）。
     *             迁移期保留，待全部消费者迁移后删除。
     */
    @Deprecated(since = "refactor/grant-revoke", forRemoval = true)
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
     * @deprecated 工作区语义 + 只有单根清理；改用路径级、无语义的 {@link #revoke}。
     */
    @Deprecated(since = "refactor/grant-revoke", forRemoval = true)
    default void onWorkspaceRemoved(Path root) {
    }

    /** 单个挂载请求。 */
    @Deprecated(since = "refactor/grant-revoke", forRemoval = true)
    record MountRequest(Path hostPath, Access access) {
    }

    /** 单个授权声明：宿主路径 + 访问语义。 */
    record PathGrant(Path hostPath, Access access) {
    }

    /** 访问语义。 */
    enum Access {
        READ_ONLY,
        READ_WRITE
    }
}
