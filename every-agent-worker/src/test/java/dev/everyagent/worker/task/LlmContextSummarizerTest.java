package dev.everyagent.worker.task;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LlmContextSummarizer} 单测:正常摘要、空响应返回空串、异常返回空串且不抛出。
 * 用 Mockito 桩 ChatModel(spring-boot-starter-test 自带)。
 */
class LlmContextSummarizerTest {

    private static ChatResponse responseWith(String text) {
        AssistantMessage out = AssistantMessage.builder().content(text).build();
        return new ChatResponse(List.of(new Generation(out)));
    }

    @Test
    void returnsTrimmedSummaryOnNormalResponse() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(responseWith("  摘要内容  "));

        LlmContextSummarizer summarizer = new LlmContextSummarizer(model, 512);
        assertEquals("摘要内容", summarizer.summarize("很长的历史文本", 100),
                "正常返回应 trim 摘要");
    }

    @Test
    void returnsEmptyWhenResponseNull() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(null);

        LlmContextSummarizer summarizer = new LlmContextSummarizer(model, 512);
        assertEquals("", summarizer.summarize("历史", 100));
    }

    @Test
    void returnsEmptyWhenOutputTextEmpty() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(responseWith(""));

        LlmContextSummarizer summarizer = new LlmContextSummarizer(model, 512);
        assertEquals("", summarizer.summarize("历史", 100));
    }

    @Test
    void returnsEmptyWhenBlankInput() {
        ChatModel model = mock(ChatModel.class);
        LlmContextSummarizer summarizer = new LlmContextSummarizer(model, 512);
        assertEquals("", summarizer.summarize("   ", 100));
        assertEquals("", summarizer.summarize(null, 100));
    }

    @Test
    void swallowsModelExceptionInsteadOfThrowing() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("模型炸了"));

        LlmContextSummarizer summarizer = new LlmContextSummarizer(model, 512);
        // 内部 try/catch 返回空串,不向外抛出
        assertEquals("", summarizer.summarize("历史", 100));
    }
}