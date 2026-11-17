package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.worker.config.WorkerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AgentRunner} 无活动<b>诊断</b>(2026-11 t_145g 静默挂起排障)单测:
 * <ul>
 *   <li>诊断<b>不打断</b>:续体丢失形态(订阅后零事件零终态)下 run() 保持阻塞,
 *       现场保留(根因未明前不销毁证据),仅周期打 WARN 日志;</li>
 *   <li>正常取消路径不受影响(interrupt → InterruptedException);</li>
 *   <li>打点单点:agent 级 emitter 每次发射刷新 lastActivityMs。</li>
 * </ul>
 */
class AgentRunnerWatchdogTest {

    /** ChatClient 深桩:prompt(prompt).stream().chatClientResponse() 返回给定 Flux。 */
    private static ChatClient chatClientReturning(Flux<ChatClientResponse> flux) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt(any(Prompt.class))).thenReturn(spec);
        when(spec.stream()).thenReturn(stream);
        when(stream.chatClientResponse()).thenReturn(flux);
        return chatClient;
    }

    private static AgentEntity entity(ChatClient chatClient) {
        ExecContext exec = mock(ExecContext.class);
        when(exec.subjectId()).thenReturn("t_test");
        AgentEntity a = new AgentEntity("a_test", "", null,
                OpenAiChatOptions.builder().build(), List.of(),
                e -> 1L, exec, "test", Map.of());
        a.chatClient = chatClient;
        return a;
    }

    private static WorkerProperties props(long inactivityMs) {
        WorkerProperties p = new WorkerProperties();
        p.setAgentInactivityTimeoutMs(inactivityMs);
        return p;
    }

    @Test
    void deadChainStaysDiagnosedButNotInterrupted() throws Exception {
        // 续体丢失形态:订阅后零事件零终态(Flux.never)。诊断阈值 500ms;
        // 验证 3s 后 run() 仍阻塞(现场保留),且 interrupt 仍可正常取消。
        AgentEntity a = entity(chatClientReturning(Flux.never()));
        AgentRunner runner = new AgentRunner(props(500));

        Thread runnerThread = new Thread(() -> {
            try {
                runner.run(a);
            }
            catch (InterruptedException expected) {
                // 测试主动中断,正常取消路径
            }
        });
        runnerThread.setDaemon(true);
        runnerThread.start();

        // 阈值 500ms + 诊断周期 → 3s 内早已超阈值,但诊断必须不打断
        TimeUnit.MILLISECONDS.sleep(3_000);
        assertThat(runnerThread.isAlive()).isTrue();

        runnerThread.interrupt();
        runnerThread.join(5_000);
        assertThat(runnerThread.isAlive()).isFalse();
    }

    @Test
    void normalCompletionUnaffected() throws InterruptedException {
        AgentEntity a = entity(chatClientReturning(Flux.empty()));
        AgentRunner runner = new AgentRunner(props(60_000));

        runner.run(a); // 流立即 onComplete → 正常返回
        assertThat(a.finished).isTrue();
    }

    @Test
    void emitterEmitRefreshesActivityTimestamp() {
        // 打点单点:agent 级 emitter 每次发射(任意事件)刷新 lastActivityMs
        AgentEntity a = entity(chatClientReturning(Flux.empty()));
        assertThat(a.lastActivityMs()).isZero(); // 尚未发射

        a.emitter.emit(mock(EmitEvent.class));

        assertThat(a.lastActivityMs()).isPositive();
    }
}
