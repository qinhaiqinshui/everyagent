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
 */
public final class WaitRegistry {

    /** 一条等待绑定：目标任务 ID + 目标任务标题（trace / 异常信息展示用）。 */
    public record WaitBinding(String targetTaskId, String targetTitle) {
    }

    private final ConcurrentHashMap<String, WaitBinding> bindings = new ConcurrentHashMap<>();

    /** 登记绑定（覆盖式：同任务最新胶囊胜——多胶囊取最后命中的语义由此实现）。 */
    public void bind(String taskId, String targetTaskId, String targetTitle) {
        bindings.put(taskId, new WaitBinding(targetTaskId, targetTitle));
    }

    /** 只读查询当前绑定（无绑定返回 null；循环等待检测用，不消费）。 */
    public WaitBinding activeOf(String taskId) {
        return bindings.get(taskId);
    }

    /** 原子取出并移除绑定（advisor 首个模型调用消费；无绑定返回 null）。 */
    public WaitBinding take(String taskId) {
        return bindings.remove(taskId);
    }

    /** 清除绑定（无绑定时空操作）。 */
    public void clear(String taskId) {
        bindings.remove(taskId);
    }
}
