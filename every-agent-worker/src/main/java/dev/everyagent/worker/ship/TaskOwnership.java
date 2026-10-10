package dev.everyagent.worker.ship;

/**
 * 任务归属查询口（基础设施·通信侧定义，task 层实现）。
 *
 * <p>与 {@link TaskInputHandler} 同范式：通信基础设施需要知道「这个 taskId 是不是本 worker 的」，
 * 但**不得反向依赖** {@code TaskManager} 具体类（§7.14.2 依赖反转）——故在此声明接口，
 * 由 task 层实现、Spring 经 {@code ObjectProvider} 延迟解析（避免构造循环依赖）。
 *
 * <p>用途：{@link DataPusherManager} 收到 {@code subscriber.join} 时先校验归属，
 * 非本 worker 的任务**不建定向推送器**。没有这道校验时，一次指向不存在任务的订阅
 * 就会凭空占住一个虚拟线程推送器 + 一个永不释放的背压窗口（前端不会再发 ack），
 * 属于可被外部触发的资源泄漏（架构 §7.13）。
 */
public interface TaskOwnership {

    /**
     * 本 worker 是否持有该任务（内存运行中 ∪ 磁盘索引 ∪ 任务目录存在，任一即可）。
     *
     * @param taskId 任务 ID
     * @return true = 属于本 worker，可以为其建定向推送器
     */
    boolean ownsTask(String taskId);
}
