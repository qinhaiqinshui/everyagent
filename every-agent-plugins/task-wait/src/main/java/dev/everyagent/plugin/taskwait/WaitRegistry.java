package dev.everyagent.plugin.taskwait;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 等待绑定的内存注册表（taskId → 等待目标）。
 *
 * <p><b>不落盘</b>（需求 7）：worker 重启即丢失——重启后旧绑定失效，
 * 胶囊若出现在队列输入（queue.jsonl 回放）中，由该轮 {@link WaitBindNode}
 * 重新扫描生效。
 *
 * <p>生命周期：{@link WaitBindNode}（洋葱 879）{@link #bind} 登记 →
 * {@link WaitTaskAdvisor} 在首个模型调用 {@link #take} 原子消费——同轮后续
 * 调用、后续轮次直通；循环等待检测经只读 {@link #activeOf} 沿链逐跳走。
 *
 * <p><b>在席等待可见性（2026-11 生产互等挂起修复）</b>：{@link #take} 消费后
 * 绑定<b>迁移</b>到「在席等待」而非直接移除，直到 {@link #finish} 收口
 * （放行/失败/取消/异常）——期间 {@link #activeOf} 对循环检测保持可见。
 * 旧实现 take 即移除：两任务先后消费各自绑定后互不可见，
 * {@code detectCycle} 逐跳查 {@code activeOf} 全程落空，双方 while(true)
 * 轮询永久互等（零日志、任务恒 running；重启 worker 的 shutdown 堆栈实锤
 * 卡在轮询 sleep）。take-once 语义不变（未消费绑定已移除，二次 take 为 null）。
 */
public final class WaitRegistry {

    /** 一条等待绑定：目标任务 ID + 目标任务标题（trace / 异常信息展示用）。 */
    public record WaitBinding(String targetTaskId, String targetTitle) {
    }

    /** 未消费绑定（bind 登记、advisor take 之前）。 */
    private final ConcurrentHashMap<String, WaitBinding> bindings = new ConcurrentHashMap<>();

    /** 在席等待（take 已消费、finish 未收口）——循环等待检测的可见窗口。 */
    private final ConcurrentHashMap<String, WaitBinding> activeWaits = new ConcurrentHashMap<>();

    /** 登记绑定（覆盖式：同任务最新胶囊胜——多胶囊取最后命中的语义由此实现）。 */
    public void bind(String taskId, String targetTaskId, String targetTitle) {
        bindings.put(taskId, new WaitBinding(targetTaskId, targetTitle));
    }

    /** 只读查询当前等待（未消费绑定或在席等待；无则 null。循环等待检测用，不消费）。 */
    public WaitBinding activeOf(String taskId) {
        WaitBinding b = bindings.get(taskId);
        return b != null ? b : activeWaits.get(taskId);
    }

    /**
     * 原子取出并消费绑定（advisor 首个模型调用消费；无绑定返回 null）。
     * 消费后迁移到在席等待（{@link #activeOf} 可见），收口经 {@link #finish} 退场。
     */
    public WaitBinding take(String taskId) {
        WaitBinding b = bindings.remove(taskId);
        if (b != null) {
            activeWaits.put(taskId, b);
        }
        return b;
    }

    /** 等待收口（放行/失败/取消/异常路径统一经 finally 调用）：在席等待退场。 */
    public void finish(String taskId) {
        activeWaits.remove(taskId);
    }

    /** 清除绑定（未消费与在席等待一并清除；无绑定时空操作）。 */
    public void clear(String taskId) {
        bindings.remove(taskId);
        activeWaits.remove(taskId);
    }
}

