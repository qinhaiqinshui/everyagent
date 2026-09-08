package dev.everyagent.worker;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 非网络故障模型(测试专用):任何请求抛 {@link RuntimeException}
 * (测模型池换模型——该错误不可重试、非网络,应触发池内切换)。
 */
class FailChatModel implements ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
        throw new RuntimeException("模拟模型故障");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.error(new RuntimeException("模拟模型故障"));
    }

    @Override
    public ChatOptions getOptions() {
        return org.springframework.ai.openai.OpenAiChatOptions.builder().build();
    }
}
