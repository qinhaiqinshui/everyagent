package dev.everyagent.worker;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.io.IOException;

/**
 * 网络故障模型(测试专用):任何请求抛 {@link IOException}
 * (测模型池「网络异常不介入」——换模型无意义,应原样上抛不切换)。
 */
class FailIoChatModel implements ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
        throw new IllegalStateException(new IOException("模拟网络中断"));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.error(new IOException("模拟网络中断"));
    }

    @Override
    public ChatOptions getOptions() {
        return org.springframework.ai.openai.OpenAiChatOptions.builder().build();
    }
}
