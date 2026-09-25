package dev.everyagent.plugin.editresend;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 编辑重发节点（order=395.5，虚拟线程阶段，位于 queue.loop(395) 与 consume.input(396) 之间）。
 * 从 ctx.metadata() 取 editSeq，有就截断，然后放行到 consume.input(396)。
 * 插件不存在时此节点不存在，queue.loop(395) → consume.input(396) 直连。
 */
public final class EditResendNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(EditResendNode.class);

    private final EditTruncateProcessor truncateProcessor;

    public EditResendNode(EditTruncateProcessor truncateProcessor) {
        this.truncateProcessor = truncateProcessor;
    }

    @Override
    public String id() { return "edit.resend"; }

    @Override
    public float order() { return 395.5f; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        Map<String, Object> metadata = ctx.metadata();
        if (metadata != null) {
            String editSeq = (String) metadata.get("editSeq");
            if (editSeq != null && !editSeq.isEmpty()) {
                try {
                    truncateProcessor.truncate(ctx.taskId(), editSeq, ctx.input(), ctx.rawContent(), ctx);
                } catch (Exception e) {
                    // 截断失败不阻塞后续 consume.input（风险表：截断失败时 consumeInput 仍执行）
                    log.error("编辑截断异常 task={} editSeq={}", ctx.taskId(), editSeq, e);
                }
            }
        }
        return next.proceed(ctx);  // → consume.input(396) → kernel
    }
}
