package dev.everyagent.worker;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 脚本化假模型(测试专用,规则按最后一条消息分派):
 * - 末条是 ToolResponseMessage → 汇总工具结果给最终回答;
 * - 用户文本含 "THINK:" → 先发两片累积 reasoningContent(测 thinking 差分);
 * - 会话含 ≥2 条 user(再运行载入历史)→ 回答前缀 "上下文N:"(测冷启动上下文携带);
 * - 用户文本含 "ASK:" → 调 ask_user;含 "SUB:" → 调 run_agent;
 * - 含 "EXEC:" → 调平台命令工具(bash / powershell,按 os.name 选;后跟原始 JSON args,测 PermissionGate 命令门禁);
 * - 含 "READOUT:" → 调 read_file(后跟路径,测工作区外读授权;用正斜杠路径免 JSON 转义);
 * - 含 "WRITEFILE:" → 调 create_file(后跟路径,测系统目录写硬拒;用正斜杠路径免 JSON 转义);
 * - 含 "FAIL:" → 抛模型错误;含 "SLOW:" → 分片延迟输出(测取消);
 * - 其余 → 回声输出(分两个 chunk,验证 delta 流)。
 */
class FakeChatModel implements org.springframework.ai.chat.model.ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
        List<ChatResponse> chunks = respond(prompt).collectList().block();
        return merge(chunks);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // 尾帧 usage(OpenAI stream_options.include_usage 语义):仅元数据,generations 为空
        return respond(prompt).concatWith(Flux.just(usageChunk()));
    }

    /**
     * 必须 override 为 OpenAiChatOptions(ToolCallingChatOptions):DefaultChatClientUtils 以
     * chatModel.getOptions() 为基底合并 prompt options,默认 DefaultChatOptions 会让
     * ToolCallingAdvisor 短路透传(工具回调丢失、循环静默不执行)。真模型同构。
     */
    @Override
    public ChatOptions getOptions() {
        return org.springframework.ai.openai.OpenAiChatOptions.builder().build();
    }

    private Flux<ChatResponse> respond(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        Message last = messages.get(messages.size() - 1);
        if (last instanceof ToolResponseMessage trm) {
            StringBuilder joined = new StringBuilder();
            trm.getResponses().forEach(r -> joined.append(r.responseData()).append(" | "));
            String answer = "已确认:" + joined;
            return Flux.just(textChunk(answer.substring(0, Math.min(6, answer.length()))),
                    finalTextChunk(answer.substring(Math.min(6, answer.length()))));
        }
        String text = last.getText() == null ? "" : last.getText();
        if (text.contains("FAIL:")) {
            return Flux.error(new RuntimeException("模拟模型故障"));
        }
        if (text.contains("SLOW:")) {
            return Flux.concat(
                    Mono.delay(Duration.ofMillis(1200)).map(v -> textChunk("慢速片段一")),
                    Mono.delay(Duration.ofMillis(4000)).map(v -> textChunk("慢速片段二")),
                    Mono.delay(Duration.ofMillis(4000)).map(v -> finalTextChunk("慢速片段三")));
        }
        // 再运行上下文验证:会话里 user 条数 ≥2 说明历史被载入,回答打上标记
        long userCount = messages.stream().filter(m -> m instanceof UserMessage).count();
        String ctx = userCount >= 2 ? "上下文" + userCount + ":" : "";
        if (text.contains("THINK:")) {
            // OpenAI 兼容流:reasoningContent 为累积值(第一片 "思考",第二片 "思考完成")。
            // 片间 150ms 延迟:hub 无缓冲,测试端订阅 stream 频道晚于发布即永久错过,
            // 给"create 返回 → sub 生效"留出确定窗口(否则瞬态帧断言随机超时)。
            return Flux.concat(
                    Mono.delay(Duration.ofMillis(150)).map(v -> reasoningChunk("思考")),
                    Mono.delay(Duration.ofMillis(150)).map(v -> reasoningChunk("思考完成")),
                    Flux.just(textChunk(ctx + "回声:"), finalTextChunk(text)));
        }
        if (text.contains("ASK:")) {
            String q = text.substring(text.indexOf("ASK:") + 4).trim();
            return Flux.just(toolCallChunk("call-ask-1", "ask_user",
                    "{\"questions\":[{\"prompt\":\"" + q + "\",\"options\":[\"是\",\"否\"]}]}"));
        }
        if (text.contains("SUB:")) {
            String subInput = text.substring(text.indexOf("SUB:") + 4).trim();
            return Flux.just(toolCallChunk("call-sub-1", "run_agent",
                    "{\"input\":\"" + subInput + "\",\"title\":\"子任务\",\"blocking\":true}"));
        }
        if (text.contains("EXEC:")) {
            // EXEC:<原始 JSON args>:透传平台命令工具参数(测危险命令授权);工具名按运行时 OS 选(与注册侧一致)
            String args = text.substring(text.indexOf("EXEC:") + 5).trim();
            String tool = System.getProperty("os.name").toLowerCase().contains("win") ? "powershell" : "bash";
            return Flux.just(toolCallChunk("call-exec-1", tool, args));
        }
        if (text.contains("READOUT:")) {
            // READOUT:<路径>:read_file 读取该路径(测工作区外读授权;路径用正斜杠)
            String p = text.substring(text.indexOf("READOUT:") + 8).trim().replace('\\', '/');
            return Flux.just(toolCallChunk("call-read-1", "read_file", "{\"path\":\"" + p + "\"}"));
        }
        if (text.contains("WRITEFILE:")) {
            // WRITEFILE:<路径>:create_file 新建该路径(测系统目录写硬拒;路径用正斜杠)
            String p = text.substring(text.indexOf("WRITEFILE:") + 10).trim().replace('\\', '/');
            return Flux.just(toolCallChunk("call-write-1", "create_file",
                    "{\"path\":\"" + p + "\",\"content\":\"x\"}"));
        }
        return Flux.just(textChunk(ctx + "回声:"), finalTextChunk(text));
    }

    private static ChatResponse textChunk(String s) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(s))));
    }

    /**
     * 终答末片:携带 finishReason=stop(对齐真实 OpenAI 流终片)。BaseAdvisor.after
     * (如 MeasureDurationAdvisor 的 task.duration)仅对带 finishReason 的 chunk 触发,
     * 全部用 textChunk 会让收口类 advisor 在测试里静默失活。
     */
    private static ChatResponse finalTextChunk(String s) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(s),
                ChatGenerationMetadata.builder().finishReason("stop").build())));
    }

    /** 尾部 usage 修正帧(仅元数据):11+7=18,聚合后触发 usage 事件带 total。 */
    private static ChatResponse usageChunk() {
        return new ChatResponse(List.of(), org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                .usage(new org.springframework.ai.chat.metadata.DefaultUsage(11, 7, 18, null)).build());
    }

    /** reasoningContent 累积值 chunk(测 AgentRunner 差分逻辑)。 */
    private static ChatResponse reasoningChunk(String accumulated) {
        AssistantMessage m = AssistantMessage.builder().content("")
                .properties(Map.of("reasoningContent", accumulated))
                .build();
        return new ChatResponse(List.of(new Generation(m)));
    }

    private static ChatResponse toolCallChunk(String id, String name, String args) {
        AssistantMessage m = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args)))
                .build();
        return new ChatResponse(List.of(new Generation(m,
                ChatGenerationMetadata.builder().finishReason("tool_calls").build())));
    }

    private static ChatResponse merge(List<ChatResponse> chunks) {
        StringBuilder sb = new StringBuilder();
        for (ChatResponse c : chunks) {
            if (c.getResult() != null && c.getResult().getOutput().getText() != null) {
                sb.append(c.getResult().getOutput().getText());
            }
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(sb.toString()))));
    }
}
