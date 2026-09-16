package dev.everyagent.worker.task;

import dev.everyagent.worker.slash.SlashTokenHandler;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务队列「插入到当前对话」advisor(一个 advisor 只负责一个功能;普通 {@link StreamAdvisor})。
 *
 * <p>用户点击队列项「插入」按钮后,事件携带正文被 {@link TaskManager} 写入主 agent 实体的
 * {@link AgentEntity#pendingDialogInserts}(本轮有效,不跨 run 共享);本 advisor 在洋葱模型的
 * <b>下行阶段</b>(工具循环每个迭代把请求交给内层链前的 {@code before})drain 该队列,把每条
 * 用户输入追加为 {@code role=user} 消息再传给下一层——即「随着工具结果一起提交给 AI」:
 * {@code ToolCallingAdvisor} 在工具执行完毕后会用含工具结果的 conversationHistory 作为下一轮
 * instructions 递归调用内层链,此时本 advisor 的 {@code before} 再次命中,注入的用户消息正好
 * 排在同批工具结果之后。同时发射 {@code user.message} 事件(前端右侧用户消息区可见、落盘回放完整)
 * 并同步进 {@code a.conversation}(跨轮/再运行上下文不丢)。
 *
 * <p>slashitem 解析:由于 {@link dev.everyagent.worker.skill.SlashTokenResolveAdvisor}(order +150)
 * 位于工具循环<b>之外</b>(外层,每请求仅运行一次),而本 advisor 在工具循环<b>内侧</b>才注入消息,
 * 外层 advisor 已扫过、不会回头处理被插入的消息。因此本 advisor 在注入前自行调用
 * {@link SlashTokenHandler#resolve(String, dev.everyagent.worker.task.TaskEntry)}
 * 把 opaque token 解析为提交文本——保证当前轮模型即可见解析后文本,与正常
 * {@code consumeInput} 路径(下一轮由 {@code SlashTokenResolveAdvisor} 解析)效果一致。
 * 解析后文本同步写入 {@code a.conversation} 与 {@code user.message} 事件;
 * {@code rawContent} 仍保留原始 opaque 串供前端回放还原胶囊。
 *
 * <p>顺序:order = {@code ToolCallingAdvisor.DEFAULT_ORDER + 30}(= HIGHEST+330),位于工具循环
 * 内侧、FileChangeAdvisor(+301)之后、EmptyResponseRetryAdvisor(+400)之前——每个工具循环迭代的下行阶段
 * 都穿过本 advisor,而空响应/瞬时错误重试各自 copy 其后的链,不经过本 advisor,不会重复注入。主/子 agent 共用同一
 * 运行入口与 advisor 链,但插入只对<b>主 agent</b> 生效(子 agent 对话是父的一次性嵌套,不接收
 * 任务队列的用户输入)——{@code a.kind == MAIN} 判定,子 agent 原样透传。
 *
 * <p>生命周期与停止语义:插入队列挂在 {@link AgentEntity}(per-run)上,本 advisor 每 run 新建;
 * 用户停止/任务终态时 AgentEntity 随本轮 run 销毁,积压的插入用户消息随之作废,不会带进下一轮。
 */
public class DialogInsertAdvisor implements StreamAdvisor {

    private final AgentEntity a;
    private final SlashTokenHandler slashTokenHandler;

    public DialogInsertAdvisor(AgentEntity a, SlashTokenHandler slashTokenHandler) {
        this.a = a;
        this.slashTokenHandler = slashTokenHandler;
    }

    @Override
    public String getName() {
        return "Dialog Insert Advisor";
    }

    @Override
    public int getOrder() {
        // 工具循环内侧:DEFAULT_ORDER = HIGHEST_PRECEDENCE + 300;+30 → HIGHEST+330,
        // 位于 FileChangeAdvisor(+301)之后、EmptyResponseRetryAdvisor(+400)之前。
        return ToolCallingAdvisor.DEFAULT_ORDER + 30;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
            StreamAdvisorChain streamAdvisorChain) {
        return streamAdvisorChain.nextStream(inject(chatClientRequest));
    }

    /**
     * 下行阶段注入:把本轮积压的插入用户输入 drain 并追加到发给模型的历史末尾。
     * 注入前调用 {@link SlashTokenHandler#resolve} 解析 opaque token(外层
     * {@code SlashTokenResolveAdvisor} 已执行过、不会回头处理工具循环内侧注入的消息)。
     * 队列空 / 子 agent / 无主 agent 实体时原样返回,不改写请求。
     */
    private ChatClientRequest inject(ChatClientRequest chatClientRequest) {
        if (a.kind != AgentEntity.Kind.MAIN) {
            return chatClientRequest;
        }
        if (chatClientRequest.prompt() == null || a.pendingDialogInserts.isEmpty()) {
            return chatClientRequest;
        }
        List<Message> instructions = new ArrayList<>(chatClientRequest.prompt().getInstructions());
        boolean changed = false;
        UserInput input;
        while ((input = a.pendingDialogInserts.poll()) != null) {
            String text = input.text();
            String resolved = slashTokenHandler.resolve(text, a.task);
            // user.message 事件:前端右侧用户消息区显示 + 落盘回放完整(与 consumeInput 同事件)。
            // resolved 为 AI 实际可见文本;rawContent 保留原始 opaque 串,供前端回放还原胶囊。
            a.task.events.userMessage(resolved, input.rawContent());
            // 同步进工作态会话:跨轮 / 同任务后续 run 上下文不丢(存解析后文本,
            // 下一轮 SlashTokenResolveAdvisor 扫描无 token 幂等跳过)
            a.conversation.add(new UserMessage(resolved));
            // 追加到下一轮 instructions(排在工具结果之后,随工具结果一起提交给 AI)
            instructions.add(new UserMessage(resolved));
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