package dev.everyagent.plugin.inputqueue;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.attachment.UserInputMaterializer;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
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
 * 任务队列「插入到当前对话」advisor（主 agent 专属，由 {@link DialogInsertAdvisorProvider} 装配）。
 * <p>用户点队列项「插入」→ {@code task.run{metadata:{insert,index}}} → {@link QueueDispatchNode}
 * 从输入队列摘掉该项并把它的 ctx offer 到本任务的插入对话队列；本 advisor 在工具循环<b>下行阶段</b>
 * drain 该队列，把每条用户输入以 role=user 追加到发给模型的 instructions 末尾（即「随工具结果一起
 * 提交给 AI」），同时：
 * <ul>
 *   <li>先经 {@link UserInputMaterializer} 物化（与轮次路径同一条 {@code file.reference.process}
 *       实现）：正文里的 @ 文件引用改写为「（附图：xxx）」等替换文本、附件转 Media 并合并进任务
 *       metadata.attachments——<b>不经物化直接投 {@code queued.input()} 会让 AI 只看到裸路径</b>
 *       （图片不进模型，模型转而去 read_file 图片失败；2026-10 实锤）；</li>
 *   <li>发射 {@code user.message} 事件——payload 形状严格镜像核心 {@code consumeInput}
 *       （{@code {content, data:{rawContent}}}，{@code Mode.REPLACE}），保证前端对话区即时可见、
 *       事件日志回放完整；曾误写顶层 {@code {text, rawContent}} 致 @文件胶囊丢失；</li>
 *   <li>追加进 {@link AgentContext#conversation()}——同 run 后续轮次与再运行的上下文不丢
 *       （会话内存只进正文，媒体形态随 metadata.attachments 由 FileAttachmentAdvisor 注入，
 *       与轮次路径一致）。</li>
 * </ul>
 * <p><b>队列按 taskId 现取，不缓存引用</b>：advisor 实例在 {@code main.agent}(order=390) 建主
 * agent 时创建，早于 {@code queue.loop}(870) 与用户点击；插入对话队列对象是点击时才由
 * {@link TaskQueueRegistry#getOrCreateDialogInsertQueue} 懒建、run 收口时随 {@code unregister}
 * 移除。构造期缓存引用会在整个 run 内一直持着 {@code null}（插入静默失效）或已注销的旧对象，
 * 故每次 drain 前现查注册表。
 * <p>本 run 再无下行时机（AI 已收尾 / 取消 / 失败）而未消费的插入项，由 {@link QueueLoopNode}
 * 回填输入队列并落盘 {@code queue.jsonl}，下次运行作普通轮次消费——用户输入不丢。
 */
public class DialogInsertAdvisor implements StreamAdvisor {

    private final TaskQueueRegistry registry;
    private final String taskId;
    private final AgentContext agent;
    private final UserInputMaterializer inputMaterializer;
    private final ExecContext exec;

    public DialogInsertAdvisor(TaskQueueRegistry registry, String taskId, AgentContext agent,
            UserInputMaterializer inputMaterializer, ExecContext exec) {
        this.registry = registry;
        this.taskId = taskId;
        this.agent = agent;
        this.inputMaterializer = inputMaterializer;
        this.exec = exec;
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
        ConcurrentLinkedQueue<TaskLifecycleContext> queue =
                registry == null ? null : registry.getDialogInsertQueue(taskId);
        if (queue == null || queue.isEmpty()) {
            return chatClientRequest;
        }
        if (chatClientRequest.prompt() == null) {
            return chatClientRequest;
        }
        List<Message> instructions = new ArrayList<>(chatClientRequest.prompt().getInstructions());
        boolean changed = false;
        // 队列项为 ctx 引用：input=人类可读正文（进 AI 上下文），rawContent=原始 opaque 串（仅供前端回放还原胶囊）
        TaskLifecycleContext queued;
        while ((queued = queue.poll()) != null) {
            // 与轮次路径同一条物化实现：@ 引用 → 「（附图：xxx）」正文 + 附件（Media 随本条消息进
            // 本轮模型调用；attachments 并入 metadata 供后续轮次持续注入）。
            UserMessage message = inputMaterializer.materialize(queued.input(), queued.rawContent(), exec);
            String text = message.getText();
            if (text == null || text.isEmpty()) {
                continue;
            }
            String rawContent = queued.rawContent();
            if (agent != null) {
                // user.message：前端右侧用户消息区显示 + 落盘回放完整（与核心 consumeInput 同事件）。
                agent.emitter().emit(EmitEvent.of(SnowflakeId.next(), "user.message", null,
                        null, null, text, null,
                        rawContent != null && !rawContent.isEmpty()
                                ? Json.obj().put("rawContent", rawContent) : null,
                        EmitEvent.Mode.REPLACE));
                // 同步进工作态会话：同 run 后续轮次 / 再运行上下文不丢（与 consumeInput 同形态：
                // 会话内存只存正文，媒体由 metadata.attachments 经 FileAttachmentAdvisor 注入）。
                agent.conversation().add(new UserMessage(text));
            }
            instructions.add(message);
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
