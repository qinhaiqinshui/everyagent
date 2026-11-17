package dev.everyagent.plugin.contextcompression;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手动压缩请求内存注册表 —— /压缩上下文 inline 胶囊的<b>一次性请求</b>载体
 * (仿 task-wait 的 {@code WaitRegistry})。
 *
 * <p>链路:{@link CompressContextRequestNode}(轮次循环内)扫到本轮输入 rawContent 里的
 * {@code system.compress_context} token 时 {@link #mark};主 agent 首个模型请求由
 * {@link ContextCompressionAdvisor} 检测到即强制压缩并 {@link #consume} —— 用后即弃,
 * 不落盘、不跨 run 残留(用户需要再次压缩时重新选胶囊即可)。
 *
 * <p>为什么不再用任务级 {@code slash-tokens.json}(旧 bottom 胶囊方案):任务级 token 是
 * <b>持久</b>开关语义,一次性命令挂在上面既需要「select 时写盘」又需要 advisor 消费后
 * 广播移除,链路重且曾因移除口失效导致胶囊常驻、每轮重复压缩;inline 胶囊随消息提交,
 * 请求生命周期=一个 run,内存注册表天然一次性。
 *
 * <p>线程模型:mark 发生在任务生命周期线程,has/consume 发生在 agent 执行线程,
 * 用 {@link ConcurrentHashMap} keySet 保证可见与原子。
 */
public final class ManualCompressRegistry {

    /** 待处理手动压缩请求的任务 ID 集(存在即请求待消费)。 */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    /** 登记一次手动压缩请求(幂等:重复 mark 同一任务只算一次)。 */
    public void mark(String taskId) {
        if (taskId != null && !taskId.isEmpty()) {
            pending.add(taskId);
        }
    }

    /** 该任务是否存在待消费的手动压缩请求。 */
    public boolean has(String taskId) {
        return taskId != null && pending.contains(taskId);
    }

    /** 消费(移除)该任务的手动压缩请求;不存在时无操作(幂等)。 */
    public void consume(String taskId) {
        if (taskId != null) {
            pending.remove(taskId);
        }
    }
}
