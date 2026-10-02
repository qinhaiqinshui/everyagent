package dev.everyagent.plugin.api.task;

/**
 * Task 域服务子接口 —— 由 {@code WorkerServices} 继承。
 *
 * <p>需要 task 功能的插件可向下转型为 {@code TaskServices} 获取服务，
 * 不需要 task 功能的插件只面向基础 {@code WorkerServices} 编程，
 * 不被迫传递性 import task 域类型。
 */
public interface TaskServices {

    /** 任务服务（查询任务信息、广播 task.updated）。 */
    TaskService task();

    /** 任务落盘服务（队列读写、截断、meta 读写、会话重建等）。 */
    TaskStoreService store();
}
