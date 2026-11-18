package dev.everyagent.plugin.contextcompression;

import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;

/**
 * 手动压缩请求登记节点(洋葱 881)。
 *
 * <p>order=881:queue.loop(870) 轮次循环内、consume.input(880) 之后、cascade.stop(900)
 * 之前——<b>严禁落在 [420,850] 临界段</b>(主 agent 构建段,节点在任务虚拟线程上执行会
 * 死锁)。队列插件禁用时轮次循环缺席,本节点照常执行一次(每次 task.run 下行扫一遍
 * rawContent),压缩请求不受影响(仿 {@code WaitBindNode}=879 先例)。
 *
 * <p>职责唯一(红线):只登记请求。下行扫 {@link TaskLifecycleContext#rawContent()} 中的
 * /压缩上下文 inline 胶囊(含 {@code system.compress_context} kind 的 opaque token,
 * 多胶囊只登记一次——一次性语义)→ {@link ManualCompressRegistry#mark} 内存登记;
 * 随后 {@code next.proceed}。不做压缩、不发事件;真正的压缩由
 * {@link ContextCompressionAdvisor} 在 advisor 链内消费该请求完成。
 *
 * <p>只扫 rawContent(展示原文,opaque 原样保留):提交正文 input 可能已被前端/worker
 * 解析替换为空串,不可作检测源;rawContent 为空/无命中 → 直接 proceed(零行为)。
 */
public final class CompressContextRequestNode implements TaskLifecycleNode {

    private final ManualCompressRegistry registry;

    public CompressContextRequestNode(ManualCompressRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String id() {
        return "compress.request";
    }

    @Override
    public float order() {
        return 881f;
    }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        String raw = ctx.rawContent();
        if (raw != null && !raw.isEmpty() && CompressContextToken.scanRequest(raw)) {
            registry.mark(ctx.taskId());
        }
        return next.proceed(ctx);
    }
}
