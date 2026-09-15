package dev.everyagent.worker.task;

import dev.everyagent.worker.AgentClientFactory;
import dev.everyagent.worker.skill.SkillAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * agent 运行入口(架构 §5.2,薄化:工具循环与轮次聚合全交 {@code ToolCallingAdvisor},
 * 本类只负责"订阅流式 + 中断/dispose + 终态标记",不再手写 while 循环/聚合)。
 *
 * <p>纪律(AGENTS.md §13):agent 执行必须走 ChatClient + Advisor 生态,禁止手搓工具循环。
 * 具体链路:{@link AgentClientFactory#forAgent} 按 {@link AgentEntity.Kind} 装配
 * {@link SkillAdvisor}(仅主)+ {@link WorkerToolEventAdvisor}(事件发射),底层
 * {@link ToolCallingAdvisor} 递归工具循环由框架驱动;worker 事件协议({@code message}/
 * {@code toolCall}/{@code toolResult}/{@code delta}/...)由 {@link WorkerToolEventAdvisor}
 * 在循环 hook 内发射,本类不直接发事件。
 */
@Component
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    private final AgentClientFactory clientFactory;
    private final SkillAdvisor skillAdvisor;
    private final ToolCallingManager toolCallingManager;

    public AgentRunner(AgentClientFactory clientFactory, SkillAdvisor skillAdvisor,
            ToolCallingManager toolCallingManager) {
        this.clientFactory = clientFactory;
        this.skillAdvisor = skillAdvisor;
        this.toolCallingManager = toolCallingManager;
    }

    /**
     * 跑完一个 agent(阻塞至最终回答)。会话内存由调用方预置(system + 历史 + 首条 user)。
     * 工具循环由 ChatClient 的 advisor 链递归完成;本方法订阅流式响应,支持线程中断取消。
     */
    public void run(AgentEntity a) throws InterruptedException {
        // 2.0.1:prompt options 原样透传并强转 OpenAiChatOptions(且不与默认 options 合并),
        // 必须用完整快照 mutate()(复制 baseUrl/apiKey/model/采样参数),不能用泛型
        // ToolCallingChatOptions——模型层只发定义不执行工具,循环由 advisor 链驱动(§5.2)。
        OpenAiChatOptions.Builder options = a.options.mutate();
        if (!a.tools.isEmpty()) {
            // 统一经 SchemaStrippedToolCallback 包装:剥掉 inputSchema 里无用的 $schema 声明,
            // 节省每轮发给模型的工具定义 token;调用行为原样委托(见 SchemaStrippedToolCallback)。
            options.toolCallbacks(a.tools.stream()
                    .map(SchemaStrippedToolCallback::new)
                    .toArray(ToolCallback[]::new));
        }
        Prompt prompt = new Prompt(new ArrayList<>(a.conversation), options.build());

        ChatClient cc = clientFactory.forAgent(a, skillAdvisor, toolCallingManager);
        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> error =
                new java.util.concurrent.atomic.AtomicReference<>();
        Disposable[] holder = new Disposable[1];
        log.debug("[run] 订阅模型流 agentId={} kind={} taskId={} vtThread={}",
                a.agentId, a.kind, a.task.taskId, Thread.currentThread().getName());
        holder[0] = cc.prompt(prompt).stream().chatClientResponse()
                // 生命周期诊断:观察 flux 终态信号与执行线程,确认 dispose/中断后流是否真的结束。
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
                    // 聚合后的最终轮 ChatClientResponse 在此可忽略——终态由 flux 完成判定。
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
                    log.debug("[cancel] 轮询检测到中断标记,执行 dispose agentId={} holderNull={} thread={}",
                            a.agentId, holder[0] == null, Thread.currentThread().getName());
                    holder[0].dispose();
                    throw new InterruptedException("流式输出被取消");
                }
            }
        } catch (InterruptedException awaitEx) {
            // 关键诊断:await 自身被中断抛出时会跳过上面的 dispose,flux 可能仍在后台运行。
            log.debug("[cancel] done.await 抛出 InterruptedException(跳过 in-loop dispose) agentId={} holderNull={} thread={}",
                    a.agentId, holder[0] == null, Thread.currentThread().getName());
            throw awaitEx;
        }
        log.debug("[run] 模型流自然结束 agentId={} thread={}", a.agentId, Thread.currentThread().getName());
        Throwable t = error.get();
        if (t != null) {
            if (t instanceof InterruptedException ie) {
                throw ie;
            }
            // 死循环检测收口:文案已完整,原样上抛(TaskManager 统一 error 收口)
            if (t instanceof LoopRepeatException lre) {
                throw lre;
            }
            // reactor block 可能包 RejectedExecution / 取消类,统一转 ModelCallException
            Throwable cause = t.getCause();
            if (cause instanceof InterruptedException ie) {
                throw ie;
            }
            if (cause instanceof LoopRepeatException lre) {
                throw lre;
            }
            // 上下文超限(public API 400 "maximum context length"):打印超限的模型配置与
            // 会话规模专项诊断(主/子 agent 共用;纯日志,不改变异常语义)。
            ContextOverflow.logIfOverflow(a, t);
            throw new ModelCallException("模型调用失败: " + t.getMessage(), t);
        }
        // 工具循环已自然终止(无 tool call 的终轮)→ agent 跑完
        a.finished = true;
    }
}
