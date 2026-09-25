package dev.everyagent.plugin.inputqueue;

import dev.everyagent.worker.task.AgentEntity;
import dev.everyagent.worker.task.UserInput;
import dev.everyagent.worker.slash.SlashTokenHandler;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 任务队列「插入到当前对话」advisor（从 worker 核心迁入插件）。
 * <p>用户点击队列项「插入」按钮后，正文经 QueueInputInterceptor 写入插入对话队列；
 * 本 advisor 在工具循环下行阶段 drain 该队列，把每条用户输入追加为 role=user 消息
 * 随工具结果一起提交给 AI，同时发射 user.message 事件。
 */
public class DialogInsertAdvisor implements StreamAdvisor {

    private final ConcurrentLinkedQueue<UserInput> dialogInsertQueue;
    private final String taskId;

    public DialogInsertAdvisor(ConcurrentLinkedQueue<UserInput> dialogInsertQueue, String taskId) {
        this.dialogInsertQueue = dialogInsertQueue;
        this.taskId = taskId;
    }

    @Override
    public String getName() {
        return "Dialog Insert Advisor";
    }

    @Override
    public int getOrder() {
        return org.springframework.ai.chat.client.advisor.ToolCallingAdvisor.DEFAULT_ORDER + 30;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
            StreamAdvisorChain streamAdvisorChain) {
        return streamAdvisorChain.nextStream(inject(chatClientRequest));
    }

    private ChatClientRequest inject(ChatClientRequest chatClientRequest) {
        if (dialogInsertQueue == null || dialogInsertQueue.isEmpty()) {
            return chatClientRequest;
        }
        if (chatClientRequest.prompt() == null) {
            return chatClientRequest;
        }
        List<Message> instructions = new ArrayList<>(chatClientRequest.prompt().getInstructions());
        boolean changed = false;
        UserInput input;
        while ((input = dialogInsertQueue.poll()) != null) {
            String text = input.text();
            instructions.add(new UserMessage(text));
            changed = true;
        }
        if (!changed) {
            return chatClientRequest;
        }
        return chatClientRequest.mutate()
                .prompt(new Prompt(instructions, chatClientRequest.prompt().getOptions()))
                .build();
    }
}
