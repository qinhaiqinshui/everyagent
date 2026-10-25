package dev.everyagent.plugin.taskwait;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;

import java.util.List;

/**
 * 等待绑定登记节点（洋葱 879）。
 *
 * <p>order=879：queue.loop(870) 轮次循环内、edit.resend(877) 之后、
 * consume.input(880) 之前——<b>严禁落在 [420,850] 临界段</b>（主 agent 构建段，
 * 节点在任务虚拟线程上执行会死锁）。两个队列插件禁用时轮次循环缺席，
 * 本节点照常执行一次（每次 task.run 下行扫一遍 rawContent），等待功能不受影响。
 *
 * <p>职责唯一（红线）：只登记绑定。下行扫 {@link TaskLifecycleContext#rawContent()}
 * 中的等待胶囊（{@link WaitTaskToken#scanWaits}），<b>取最后一个命中</b>（多胶囊时
 * 最近意图胜）→ {@link WaitRegistry#bind} 内存登记；随后 {@code next.proceed}。
 * 不等待、不发事件、不校验目标存在性（校验全部留给 {@link WaitTaskAdvisor}）；
 * 无命中 / rawContent 为空 → 直接 proceed（零行为）。
 */
public final class WaitBindNode implements TaskLifecycleNode {

    private final WaitRegistry registry;

    public WaitBindNode(WaitRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String id() { return "wait.bind"; }

    @Override
    public float order() { return 879; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        String raw = ctx.rawContent();
        if (raw != null && !raw.isEmpty()) {
            List<WaitTaskToken.WaitRef> refs = WaitTaskToken.scanWaits(raw);
            if (!refs.isEmpty()) {
                // 多胶囊取最后一个：最近意图胜
                WaitTaskToken.WaitRef last = refs.get(refs.size() - 1);
                registry.bind(ctx.taskId(), last.targetTaskId(), last.title());
            }
        }
        return next.proceed(ctx);
    }
}
