package dev.everyagent.worker.task;

import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ModelPoolChatModel 单测(演进记录第 18 轮):池模型「请求异常换下一成员」的容灾逻辑。
 * 全部用 mock 成员 ChatModel / mock TaskEvents,不触网。
 */
class ModelPoolChatModelTest {

    private final ChatModel m1 = mock(ChatModel.class);
    private final ChatModel m2 = mock(ChatModel.class);
    private final TaskEvents events = mock(TaskEvents.class);

    private ModelPoolChatModel newPool() {
        return new ModelPoolChatModel(
                List.of(m1, m2),
                List.of(opt("m1"), opt("m2")),
                List.of(snap("cfg-1", "m1"), snap("cfg-2", "m2")),
                events, "test-agent");
    }

    // ---- 非流式 ----

    @Test
    void callSwitchesToNextMemberOnNonNetworkError() {
        ModelPoolChatModel pool = newPool();
        when(m1.call(any(Prompt.class))).thenThrow(new RuntimeException("boom-1"));
        ChatResponse ok = new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        when(m2.call(any(Prompt.class))).thenReturn(ok);

        ChatResponse result = pool.call(new Prompt("hi"));

        assertSame(ok, result, "切到成员2 后应返回其响应");
        verify(m1, times(1)).call(any(Prompt.class));
        verify(m2, times(1)).call(any(Prompt.class));
        verify(events, times(1)).modelFailoverSwitch(eq(null), eq(snap("cfg-2", "m2")));
    }

    @Test
    void callExhaustedRethrowsLastError() {
        ModelPoolChatModel pool = newPool();
        when(m1.call(any(Prompt.class))).thenThrow(new RuntimeException("boom-1"));
        when(m2.call(any(Prompt.class))).thenThrow(new RuntimeException("boom-2"));

        RuntimeException e = assertThrows(RuntimeException.class, () -> pool.call(new Prompt("hi")));
        assertSame("boom-2", e.getMessage(), "池耗尽上抛最后异常");
        verify(m1, times(1)).call(any(Prompt.class));
        verify(m2, times(1)).call(any(Prompt.class));
    }

    @Test
    void callDoesNotSwitchOnNetworkError() {
        ModelPoolChatModel pool = newPool();
        when(m1.call(any(Prompt.class))).thenThrow(new IllegalStateException(new IOException("断网")));

        RuntimeException e = assertThrows(RuntimeException.class, () -> pool.call(new Prompt("hi")));
        assertNotNull(e);
        verify(m1, times(1)).call(any(Prompt.class));
        verify(m2, never()).call(any(Prompt.class));               // 网络异常不切换
        verify(events, never()).modelFailoverSwitch(any(), any());
    }

    // ---- 流式 ----

    @Test
    void streamSwitchesToNextMemberOnEarlyError() {
        ModelPoolChatModel pool = newPool();
        when(m1.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("boom-1")));
        ChatResponse ok = new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        when(m2.stream(any(Prompt.class))).thenReturn(Flux.just(ok));

        List<ChatResponse> result = pool.stream(new Prompt("hi")).collectList().block();

        assertNotNull(result, "流应成功结束");
        assertSame(ok, result.get(0), "切到成员2 后应透传其 chunk");
        verify(m1, times(1)).stream(any(Prompt.class));
        verify(m2, times(1)).stream(any(Prompt.class));
        verify(events, times(1)).modelFailoverSwitch(eq(null), eq(snap("cfg-2", "m2")));
    }

    @Test
    void streamDoesNotSwitchAfterSignalEmitted() {
        ModelPoolChatModel pool = newPool();
        // 成员1 已下发有效 chunk 后才中断 → 防重护栏:不切换(避免前端 delta 重复)
        ChatResponse chunk = new ChatResponse(List.of(new Generation(new AssistantMessage("可见"))));
        when(m1.stream(any(Prompt.class)))
                .thenReturn(Flux.just(chunk).concatWith(Flux.error(new RuntimeException("中段"))));

        assertThrows(RuntimeException.class, () -> pool.stream(new Prompt("hi")).collectList().block());
        verify(m1, times(1)).stream(any(Prompt.class));
        verify(m2, never()).stream(any(Prompt.class));               // 已下发信号不切换
        verify(events, never()).modelFailoverSwitch(any(), any());
    }

    @Test
    void streamDoesNotSwitchOnNetworkError() {
        ModelPoolChatModel pool = newPool();
        when(m1.stream(any(Prompt.class))).thenReturn(Flux.error(new IOException("断网")));

        assertThrows(RuntimeException.class, () -> pool.stream(new Prompt("hi")).collectList().block());
        verify(m1, times(1)).stream(any(Prompt.class));
        verify(m2, never()).stream(any(Prompt.class));
        verify(events, never()).modelFailoverSwitch(any(), any());
    }

    // ---- 辅助 ----

    private static ModelSnapshot snap(String configId, String model) {
        return new ModelSnapshot(configId, "openai-compat", "http://x", model, null);
    }

    private static OpenAiChatOptions opt(String model) {
        return OpenAiChatOptions.builder().model(model).baseUrl("http://x").apiKey("k").build();
    }
}