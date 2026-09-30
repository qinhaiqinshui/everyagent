package dev.everyagent.worker.agent;

import dev.everyagent.worker.task.ContextOverflow;
import dev.everyagent.worker.task.InterceptingToolCallingManager;
import dev.everyagent.worker.task.LoopRepeatException;
import dev.everyagent.plugin.api.exception.ModelCallException;
import dev.everyagent.worker.task.SchemaStrippedToolCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * agent 运行入口（agent 层）：薄化——工具循环与轮次聚合全交 {@code ToolCallingAdvisor}，
 * 本类只负责"订阅流式 + 中断/dispose + 终态标记"，不再手写 while 循环/聚合。
 *
 * <p>解耦后：不依赖 {@code AgentClientFactory}，直接使用 {@link AgentEntity#chatClient}
 * （由 {@link AgentBuilder} 装配注入）。{@link AgentEntity#properties} 设置到
 * {@link InterceptingToolCallingManager} ThreadLocal 供工具执行拦截器使用。
 *
 * <p>纪律(AGENTS.md §13):agent 执行必须走 ChatClient + Advisor 生态,禁止手搓工具循环。
 */
@Component
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    /**
     * 跑完一个 agent(阻塞至最终回答)。会话内存由调用方预置(system + 历史 + 首条 user)。
     * 工具循环由 ChatClient 的 advisor 链递归完成;本方法订阅流式响应,支持线程中断取消。
     */
    public void run(AgentEntity a) throws InterruptedException {
        // 设置当前 agent 上下文（供 ToolExecutionInterceptor 单例通过 ThreadLocal 获取）
        InterceptingToolCallingManager.setCurrentProperties(a.properties);
        try {
            // 2.0.1:prompt options 原样透传并强转 OpenAiChatOptions(且不与默认 options 合并),
            OpenAiChatOptions.Builder options = a.options.mutate();
            if (!a.tools.isEmpty()) {
                options.toolCallbacks(a.tools.stream()
                        .map(SchemaStrippedToolCallback::new)
                        .toArray(ToolCallback[]::new));
            }
            Prompt prompt = new Prompt(new ArrayList<>(a.conversation), options.build());

            CountDownLatch done = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> error =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Disposable[] holder = new Disposable[1];
            log.debug("[run] 订阅模型流 agentId={} vtThread={}",
                    a.agentId, Thread.currentThread().getName());
            holder[0] = a.chatClient.prompt(prompt).stream().chatClientResponse()
                    .doOnSubscribe(s -> log.debug("[flux] onSubscribe agentId={} thread={}",
                            a.agentId, Thread.currentThread().getName()))
                    .doOnComplete(() -> log.debug("[flux] onComplete agentId={} thread={}",
                            a.agentId, Thread.currentThread().getName()))
                    .doOnError(e -> log.debug("[flux] onError agentId={} thread={} err={}",
                            a.agentId, Thread.currentThread().getName(), e.toString()))
                    .doOnCancel(() -> log.debug("[flux] onCancel agentId={} thread={}",
                            a.agentId, Thread.currentThread().getName()))
                    .doFinally(sig -> log.debug("[flux] doFinally agentId={} signal={} thread={}",
                            a.agentId, sig, Thread.currentThread().getName()))
                    .subscribe(
                            r -> {
                                // 响应经 advisor 链内部处理(工具循环递归);事件已在 WorkerToolEventAdvisor 发。
                            },
                            e -> {
                                error.compareAndSet(null, e);
                                done.countDown();
                                log.debug("[flux] subscriber onError→latch agentId={} err={}", a.agentId, e.toString());
                            },
                            () -> {
                                done.countDown();
                                log.debug("[flux] subscriber onComplete→latch agentId={}", a.agentId);
                            });

            try {
                while (!done.await(100, TimeUnit.MILLISECONDS)) {
                    if (Thread.currentThread().isInterrupted()) {
                        log.debug("[cancel] 轮询检测到中断标记 agentId={} holderNull={} thread={}",
                                a.agentId, holder[0] == null, Thread.currentThread().getName());
                        throw new InterruptedException("流式输出被取消");
                    }
                }
            } catch (InterruptedException awaitEx) {
                if (holder[0] != null) {
                    holder[0].dispose();
                    log.debug("[cancel] 已 dispose reactive 订阅链 agentId={} thread={}",
                            a.agentId, Thread.currentThread().getName());
                }
                throw awaitEx;
            }
            log.debug("[run] 模型流自然结束 agentId={} thread={}", a.agentId, Thread.currentThread().getName());
            Throwable t = error.get();
            if (t != null) {
                if (t instanceof InterruptedException ie) {
                    throw ie;
                }
                if (t instanceof LoopRepeatException lre) {
                    throw lre;
                }
                Throwable cause = t.getCause();
                if (cause instanceof InterruptedException ie) {
                    throw ie;
                }
                if (cause instanceof LoopRepeatException lre) {
                    throw lre;
                }
                ContextOverflow.logIfOverflow(a, t);
                throw new ModelCallException("模型调用失败: " + t.getMessage(), t);
            }
            a.finished = true;
        } finally {
            InterceptingToolCallingManager.clearCurrentProperties();
        }
    }
}
