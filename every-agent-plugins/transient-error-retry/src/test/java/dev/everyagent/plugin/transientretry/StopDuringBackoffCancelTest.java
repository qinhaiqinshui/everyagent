package dev.everyagent.plugin.transientretry;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentActivity;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.event.Usage;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.emptyretry.EmptyResponseRetryAdvisor;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * bug 复现(2026-11 t_5v9a / sub_5v917):子 agent 在瞬时错误退避读秒期间被
 * stop_agent 停止({@code FutureTask.cancel(true)} 中断子 agent 虚拟线程,
 * {@code AgentRunner} 捕获中断后 dispose 订阅)后,读秒 interval 应被取消、
 * 不应再发生下一次重试——否则旧流会在读秒走完后"复活",与重新派发的新 run 并发跑。
 *
 * <p>复现路径与生产同构:ChatClient(框架自动注册 ToolCallingAdvisor) +
 * {@link TransientErrorRetryAdvisor};模型第一次订阅抛网络错误(可重试),
 * 退避读秒(backoff=6s,每秒 1 tick 瞬态事件)期间模拟 stop——断言读秒事件
 * 不再增长、模型不再被重订。
 */
class StopDuringBackoffCancelTest {

    /** 前两次订阅挂起(永不发 chunk,交 FirstChunkWatchdog 超时),其后订阅返回正常文本流;记录订阅次数。 */
    static final class RetryThenOkChatModel implements ChatModel {
        final AtomicInteger subscriptions = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> {
                int n = subscriptions.incrementAndGet();
                if (n <= 2) {
                    return Flux.never();
                }
                return Flux.just(ChatResponse.builder()
                        .generations(List.of(new org.springframework.ai.chat.model.Generation(
                                new AssistantMessage("重试成功后的回复"))))
                        .build());
            });
        }

        @Override
        public ChatOptions getOptions() {
            return OpenAiChatOptions.builder().build();
        }
    }

    /** 最小 AgentContext 桩:收集 retry 瞬态事件、计数。 */
    static final class RecordingAgentContext implements AgentContext {
        final AtomicInteger transientRetryEvents = new AtomicInteger();
        final AtomicInteger persistentRetryEvents = new AtomicInteger();

        @Override
        public String agentId() {
            return "sub-test";
        }

        @Override
        public String title() {
            return "test";
        }

        @Override
        public long createdAt() {
            return 1;
        }

        @Override
        public ExecContext execution() {
            return STUB_EXEC;
        }

        @Override
        public EventEmitter emitter() {
            return e -> {
                if ("retry".equals(e.kind())) {
                    if (!e.persist()) {
                        transientRetryEvents.incrementAndGet();
                    } else {
                        persistentRetryEvents.incrementAndGet();
                    }
                }
                return 1L;
            };
        }

        @Override
        public String status() {
            return "running";
        }

        @Override
        public boolean finished() {
            return false;
        }

        @Override
        public void finished(boolean finished) {
        }

        @Override
        public String lastText() {
            return "";
        }

        @Override
        public List<org.springframework.ai.chat.messages.Message> conversation() {
            return List.of();
        }

        @Override
        public boolean claimTerminal(String status) {
            return true;
        }

        @Override
        public ChatModel chatModel() {
            return null;
        }

        @Override
        public String currentModel() {
            return null;
        }

        @Override
        public Usage lastRound() {
            return Usage.zero();
        }

        @Override
        public String lastModel() {
            return "";
        }

        @Override
        public Usage usage() {
            return Usage.zero();
        }

        @Override
        public AgentActivity activity() {
            return new AgentActivity(null, null, null, null, null);
        }

        @Override
        public void updateActivity(String reasoning, String content, String error) {
        }

        @Override
        public void resetForRerun() {
        }
    }

    /** 最小 ExecContext 桩(TransientErrorRetryAdvisor 仅取 subjectId 作日志前缀)。 */
    private static final ExecContext STUB_EXEC = new ExecContext() {
        @Override
        public String subjectId() {
            return "t-test";
        }

        @Override
        public String workspaceRoot() {
            return "/tmp";
        }

        @Override
        public String workspaceId() {
            return "w-test";
        }

        @Override
        public ModelConfig snapshot() {
            return null;
        }

        @Override
        public EventEmitter emitter() {
            return e -> 1L;
        }

        @Override
        public dev.everyagent.plugin.api.agent.AgentFactory agentFactory() {
            return null;
        }

        @Override
        public Map<String, Object> metadata() {
            return Map.of();
        }

        @Override
        public java.nio.file.Path dataDir() {
            return java.nio.file.Path.of("/tmp");
        }

        @Override
        public boolean terminal() {
            return false;
        }

        @Override
        public InteractionService interaction() {
            return null;
        }

        @Override
        public Map<String, AgentContext> agents() {
            return Map.of();
        }
    };

    private static WorkerConfig.Retry retryCfg(long backoffMs) {
        return new WorkerConfig.Retry() {
            @Override
            public int maxEmptyResponseRetries() {
                return 2;
            }

            @Override
            public int maxRequestRetries() {
                return 3;
            }

            @Override
            public long backoffMs(long attempt) {
                return backoffMs;
            }
        };
    }

    @Test
    void disposeDuringBackoffCancelsCountdownAndRetry() throws Exception {
        RetryThenOkChatModel model = new RetryThenOkChatModel();
        RecordingAgentContext agentCtx = new RecordingAgentContext();
        // 退避 6s ×2 轮:第 1 次读秒(6 tick) → 重订#2 挂起超时 → 第 2 次读秒(6 tick)。
        // dispose 落在第 2 次读秒中段 —— 断言读秒停、重订#3 不发生。
        // 链贴近生产:EmptyResponseRetry(外层) + TransientErrorRetry + 首帧看门狗(1s,等价
        // worker FirstChunkWatchdogAdvisor.guard 的 timeout 语义)。
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(
                        new EmptyResponseRetryAdvisor(agentCtx, retryCfg(6_000)),
                        new TransientErrorRetryAdvisor(agentCtx, retryCfg(6_000)),
                        new FirstChunkWatchdogLikeAdvisor(1_000, 60_000))
                .build();

        ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();
        java.util.concurrent.atomic.AtomicReference<Throwable> runError = new AtomicReference<>();
        FutureTask<Void> ft = new FutureTask<>(() -> {
            try {
                runLikeAgentRunner(chatClient);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // stop 路径的正常收口
            } catch (Throwable t) {
                runError.set(t);
            }
            return null;
        });
        vt.execute(ft);

        // 第 1 次读秒 6 tick + 重订#2(瞬时 error) + 第 2 波读秒开始:等第 2 波的瞬态事件
        // (第 2 波首帧 = 总瞬态事件数 > 6 后的下一帧)
        Awaitility.await().atMost(Duration.ofSeconds(30))
                .until(() -> agentCtx.transientRetryEvents.get() >= 9);
        int eventsAtStop = agentCtx.transientRetryEvents.get();
        int subsAtStop = model.subscriptions.get();

        // 模拟 stop_agent:cancel(true) 中断子 agent 线程
        boolean cancelled = ft.cancel(true);
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(ft::isDone);
        if (!cancelled) {
            fail("future 应该被成功 cancel");
        }

        // stop 后等 10s(超过第 2 波剩余读秒 + buffer):读秒应停、重订#3 不应发生
        Thread.sleep(10_000);

        System.out.println("[test] eventsAtStop=" + eventsAtStop
                + " eventsAfter=" + agentCtx.transientRetryEvents.get()
                + " subsAtStop=" + subsAtStop
                + " subsAfter=" + model.subscriptions.get());
        assertEquals(subsAtStop, model.subscriptions.get(),
                "stop 后读秒到期不应再重订模型(旧流复活)");
    }

    /**
     * 首帧看门狗替身:与 worker FirstChunkWatchdogAdvisor.guard 相同的 timeout 语义
     * (订阅后 first 内无首 chunk → TimeoutException → 交 TransientErrorRetry 读秒)。
     */
    static final class FirstChunkWatchdogLikeAdvisor
            implements org.springframework.ai.chat.client.advisor.api.StreamAdvisor {

        private final long firstMs;
        private final long nextMs;

        FirstChunkWatchdogLikeAdvisor(long firstMs, long nextMs) {
            this.firstMs = firstMs;
            this.nextMs = Math.max(nextMs, firstMs);
        }

        @Override
        public String getName() {
            return "First Chunk Watchdog Like";
        }

        @Override
        public int getOrder() {
            return org.springframework.ai.chat.client.advisor.ToolCallingAdvisor.DEFAULT_ORDER + 550;
        }

        @Override
        public Flux<org.springframework.ai.chat.client.ChatClientResponse> adviseStream(
                org.springframework.ai.chat.client.ChatClientRequest request,
                org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain chain) {
            return chain.nextStream(request)
                    .timeout(
                            Flux.interval(Duration.ofMillis(firstMs)).take(1),
                            t -> Flux.interval(Duration.ofMillis(nextMs)).take(1));
        }
    }

    /** 与 AgentRunner.run 同构的订阅循环:await(100ms) 轮询 + 中断检测 → dispose。 */
    private static void runLikeAgentRunner(ChatClient chatClient) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        reactor.core.Disposable[] holder = new reactor.core.Disposable[1];
        holder[0] = chatClient.prompt()
                .user("测试输入")
                .stream()
                .chatClientResponse()
                .subscribe(
                        r -> {
                        },
                        e -> {
                            error.compareAndSet(null, e);
                            done.countDown();
                        },
                        done::countDown);
        try {
            while (!done.await(100, TimeUnit.MILLISECONDS)) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("流式输出被取消");
                }
            }
        } catch (InterruptedException awaitEx) {
            if (holder[0] != null) {
                holder[0].dispose();
            }
            throw awaitEx;
        }
        Throwable t = error.get();
        if (t != null) {
            fail("模型流不应报错(重试应恢复): " + t);
        }
    }
}
