package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.slash.SlashTaskCallbacks;

/**
 * 下行节点(order=90)：slash 任务级 token 建后回调（一事）。
 * 对每个已写入 token 调业务 onSelect(taskId)（实现见 SlashTaskCallbacks）。
 * 新建/再运行两条路径统一至此（原 rpcTaskRun 尾部与 startRerun 尾部各一份）。
 * 在 persistence.track(100) 之前执行（与现状时序一致：两条路径的回调原均在 track 之前）。
 */
public final class SlashNotifyNode implements TaskLifecycleNode {

    private final SlashTaskCallbacks slashCallbacks;

    public SlashNotifyNode(SlashTaskCallbacks slashCallbacks) {
        this.slashCallbacks = slashCallbacks;
    }

    @Override
    public String id() { return "slash.notify"; }

    @Override
    public float order() { return 60; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntryImpl();
        slashCallbacks.notifySlashCallbacks(t, t.taskId);
        return next.proceed(ctx);
    }
}
