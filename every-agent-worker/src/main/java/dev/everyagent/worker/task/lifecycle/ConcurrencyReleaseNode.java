package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行节点(order=800)：active.decrementAndGet()。临界段内。
 */
public final class ConcurrencyReleaseNode extends UpstreamNode {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyReleaseNode.class);

    @Override
    public String id() { return "concurrency.release"; }

    @Override
    public float order() { return 800; }

    @Override
    protected Object up(TaskLifecycleContext ctx, Object result) {
        try {
            ((TaskLifecycleContextImpl) ctx).concurrencyReleaser().run();
        } catch (RuntimeException e) {
            log.warn("并发计数释放异常 task={}", ctx.taskId(), e);
        }
        return result;
    }
}
