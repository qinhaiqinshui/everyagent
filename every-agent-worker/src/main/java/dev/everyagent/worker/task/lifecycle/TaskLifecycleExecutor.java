package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskKernel;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 任务生命周期执行器：按 order 升序把节点组装为嵌套链，链尾接内核（洋葱模型实现）。
 * <p>组装规则：
 * <ol>
 *   <li>按 order 升序折叠为嵌套链</li>
 *   <li>临界段识别：连续且 order ∈ [420,850] 的 SectionNode 序列 →
 *       段内节点的下行段 {@code down(ctx)} 在段边界外按 order 升序先执行（无锁），
 *       随后进入内层链；内层返回后段边界包一次 synchronized(ctx.taskLock())，
 *       段内节点的上行段 {@code up(ctx, result)} 按 order 降序直接调用</li>
 *   <li>段内上行执行序 = order 降序（段首最先）</li>
 *   <li>段外节点按 invoke 语义</li>
 * </ol>
 */
@Component
public final class TaskLifecycleExecutor {

    private static final Logger log = LoggerFactory.getLogger(TaskLifecycleExecutor.class);

    /** 临界段 order 范围：[420, 850]，此区间内连续的 SectionNode 共享一次 synchronized。 */
    private static final float CRITICAL_SECTION_MIN = 420f;
    private static final float CRITICAL_SECTION_MAX = 850f;

    /**
     * 组装并执行生命周期链。
     * @param nodes 节点列表（将被稳定排序）
     * @param kernel 任务内核（异常已翻译为 TaskOutcome）
     * @param ctx 生命周期上下文
     * @return 任务结局
     */
    public Object run(List<TaskLifecycleNode> nodes, TaskKernel kernel, TaskLifecycleContext ctx) {
        // 稳定排序：同 order 按注册顺序
        List<TaskLifecycleNode> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparingDouble(TaskLifecycleNode::order));

        // 从内到外折叠为嵌套链
        TaskChain chain = kernel::run;  // 链尾 = 内核
        int i = sorted.size() - 1;
        while (i >= 0) {
            TaskLifecycleNode node = sorted.get(i);
            // 检查是否属于临界段
            if (isInCriticalSection(node)) {
                // 收集连续的临界段节点（从高 order 到低 order）
                int end = i;
                List<SectionNode> critNodes = new ArrayList<>();
                while (i >= 0 && isInCriticalSection(sorted.get(i))) {
                    critNodes.add((SectionNode) sorted.get(i));
                    i--;
                }
                // critNodes 按 order 降序排列（高 order 先），即上行执行序；
                // 下行段需按 order 升序执行，反转之
                final List<SectionNode> downNodes = new ArrayList<>(critNodes);
                java.util.Collections.reverse(downNodes);
                final List<SectionNode> upNodes = critNodes;
                final TaskChain inner = chain;
                chain = c -> {
                    // 下行段：段边界外、无锁、order 升序；异常向外传播（否决：内层不执行）
                    for (SectionNode sn : downNodes) {
                        sn.down(c);
                    }
                    Object result = inner.proceed(c);
                    // 上行段：共享一次临界区、order 降序
                    synchronized (c.taskLock()) {
                        for (SectionNode sn : upNodes) {
                            result = sn.up(c, result);
                        }
                    }
                    return result;
                };
            } else {
                // 段外节点：正常 invoke 语义
                final TaskLifecycleNode n = node;
                final TaskChain inner = chain;
                chain = c -> n.invoke(c, inner);
                i--;
            }
        }

        // 执行链
        try {
            return chain.proceed(ctx);
        } catch (InterruptedException e) {
            // 节点下行段被取消中断 → CANCELLED（非 FAILED）
            Thread.currentThread().interrupt();
            return TaskOutcome.cancelled();
        } catch (Throwable t) {
            // 最外层之外的兜底（节点否决异常等）
            return TaskOutcome.failed(t);
        }
    }

    /**
     * 判断节点是否属于临界段：SectionNode 实例且 order ∈ [420, 850]。
     */
    private static boolean isInCriticalSection(TaskLifecycleNode node) {
        return node instanceof SectionNode
                && node.order() >= CRITICAL_SECTION_MIN
                && node.order() <= CRITICAL_SECTION_MAX;
    }
}
