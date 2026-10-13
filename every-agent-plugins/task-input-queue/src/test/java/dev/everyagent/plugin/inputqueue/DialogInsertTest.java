package dev.everyagent.plugin.inputqueue;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.attachment.UserInputMaterializer;
import dev.everyagent.plugin.api.event.StreamEmitter;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.rpc.RpcContext;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.task.TaskService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「插入到当前对话」链路单测（2026-10 点插入按钮无反应回归）。
 * <ul>
 *   <li>{@link QueueDispatchNode}：insert 必须<b>先从输入队列摘掉该项</b>再入插入对话队列——
 *       不摘则面板上该项原地不动（用户看到的「点了没反应」），且本轮跑完后 queue.loop 还会
 *       把它当新一轮输入重复提交；入队的必须是队列原 ctx（带 rawContent），而非前端回传的裸文本。</li>
 *   <li>{@link DialogInsertAdvisor}：advisor 实例在建主 agent（{@code main.agent} 390）时创建，
 *       插入队列却是用户点击时才懒建——构造期缓存引用会整个 run 一直持 null（静默失效），
 *       故必须按 taskId 现取；drain 时发 {@code user.message} 事件 + 追加 conversation，
 *       否则前端看不见这条输入、跨轮上下文也丢；且<b>必须经核心物化服务</b>
 *       （{@link UserInputMaterializer}）处理队列项——直接投 {@code queued.input()} 会让
 *       正文里的 @ 文件引用以裸路径形态进模型（图片不入模型，模型转而去 read_file 图片失败）。</li>
 * </ul>
 * 桩一律实现 plugin-api 接口 / mock 接口，不借 worker 具体类。
 */
class DialogInsertTest {

    private static final String TASK_ID = "t_insert";

    // ── queue.dispatch：插入即从输入队列摘项 ───────────────────────────────

    @Test
    void dispatchInsertRemovesQueueItemAndOffersItsOwnContext() throws Exception {
        TaskQueueRegistry registry = new TaskQueueRegistry();
        InputQueue inputQueue = registry.getOrCreateInputQueue(TASK_ID);
        TaskLifecycleContext target = stub("要插入的", "要插入的[[[[agent-token::::kind]]]]");
        inputQueue.offer(stub("第一条", null));
        inputQueue.offer(target);

        RpcContext rpc = mock(RpcContext.class);
        TaskLifecycleContext incoming = stubWithRunParams("要插入的", null,
                Map.of("insert", true, "index", 1L), rpc);

        Object result = new QueueDispatchNode(registry, taskService(running()), mock(StreamEmitter.class))
                .invoke(incoming, ctx -> {
                    throw new IllegalStateException("运行中插入应短路，不得继续下行");
                });

        assertNull(result, "insert 短路返回 null");
        assertEquals(List.of("第一条"), inputQueue.snapshot(),
                "插入项已从输入队列摘除（面板据此收敛，且不会被 queue.loop 重复续跑）");

        TaskLifecycleContext inserted = registry.getDialogInsertQueue(TASK_ID).poll();
        assertSame(target, inserted, "入插入队列的是队列原 ctx，不是前端回传的裸文本 ctx");
        assertEquals("要插入的[[[[agent-token::::kind]]]]", inserted.rawContent(),
                "rawContent 随队列原 ctx 保住（@文件胶囊回放不丢）");
        verify(rpc).ok(any());
    }

    @Test
    void dispatchWithoutIndexFallsBackToTextMatch() throws Exception {
        TaskQueueRegistry registry = new TaskQueueRegistry();
        InputQueue inputQueue = registry.getOrCreateInputQueue(TASK_ID);
        inputQueue.offer(stub("甲", null));
        inputQueue.offer(stub("乙", null));

        new QueueDispatchNode(registry, taskService(running()), mock(StreamEmitter.class)).invoke(
                stubWithRunParams("乙", null, Map.of("insert", true), mock(RpcContext.class)),
                ctx -> null);

        assertEquals(List.of("甲"), inputQueue.snapshot(), "无 index 时按正文匹配摘除");
        assertEquals("乙", registry.getDialogInsertQueue(TASK_ID).poll().input());
    }

    // ── DialogInsertAdvisor：队列懒建后现取 + 发事件 + 入会话 ────────────────

    @Test
    void advisorDrainsQueueLazilyCreatedAfterAdvisorConstruction() {
        TaskQueueRegistry registry = new TaskQueueRegistry();
        List<Message> conversation = new ArrayList<>();
        conversation.add(new UserMessage("本轮原输入"));
        EventEmitter emitter = mock(EventEmitter.class);
        AgentContext agent = mock(AgentContext.class);
        when(agent.emitter()).thenReturn(emitter);
        when(agent.conversation()).thenReturn(conversation);

        // advisor 先建（main.agent 390）——此刻插入队列尚不存在
        DialogInsertAdvisor advisor = new DialogInsertAdvisor(registry, TASK_ID, agent,
                identityMaterializer(), mock(ExecContext.class));
        // 用户随后点「插入」：queue.dispatch 懒建队列并 offer
        registry.getOrCreateDialogInsertQueue(TASK_ID).offer(stub("补一句", "补一句的raw"));

        ChatClientRequest out = sendThrough(advisor, "跑个任务");

        List<Message> instructions = out.prompt().getInstructions();
        assertEquals(2, instructions.size(), "原 instructions + 插入的 user 消息");
        assertEquals("补一句", ((UserMessage) instructions.get(1)).getText(),
                "插入文本以 role=user 随本轮工具结果提交给 AI");
        assertTrue(registry.getDialogInsertQueue(TASK_ID).isEmpty(), "已 drain");

        ArgumentCaptor<EmitEvent> event = ArgumentCaptor.forClass(EmitEvent.class);
        verify(emitter).emit(event.capture());
        assertEquals("user.message", event.getValue().kind(), "前端对话区可见 + 落盘回放完整");
        assertEquals("补一句", event.getValue().content());
        assertEquals(2, conversation.size(), "同步进工作态会话，跨轮/再运行上下文不丢");
    }

    @Test
    void advisorKeepsRequestUntouchedWhenQueueAbsent() {
        TaskQueueRegistry registry = new TaskQueueRegistry();
        DialogInsertAdvisor advisor = new DialogInsertAdvisor(registry, TASK_ID, mock(AgentContext.class),
                identityMaterializer(), mock(ExecContext.class));

        ChatClientRequest request = requestWith("跑个任务");
        assertSame(request, sendThrough(advisor, request), "无插入队列时原样透传，不改写请求");
        assertNull(registry.getDialogInsertQueue(TASK_ID), "advisor 不懒建队列，避免每次模型调用塞注册表");
    }

    /**
     * drain 必须经核心物化服务：正文改写（@ 文件引用 → 「（附图：xxx）」）与附件 Media 都来自它——
     * 直接投 {@code queued.input()} 是「插入的 @ 图片 AI 看不到」的根因。
     */
    @Test
    void advisorMaterializesQueuedItemViaCoreService() {
        TaskQueueRegistry registry = new TaskQueueRegistry();
        List<Message> conversation = new ArrayList<>();
        EventEmitter emitter = mock(EventEmitter.class);
        AgentContext agent = mock(AgentContext.class);
        when(agent.emitter()).thenReturn(emitter);
        when(agent.conversation()).thenReturn(conversation);
        ExecContext exec = mock(ExecContext.class);
        List<String> calls = new ArrayList<>();
        // 物化服务替身：记录入参（正文/rawContent/执行上下文），返回改写后的多模态消息
        UserInputMaterializer materializer = (text, rawContent, ctx) -> {
            calls.add(text + "|" + rawContent + "|" + (ctx == exec));
            return UserMessage.builder()
                    .text("（附图：图.png）" + text)
                    .media(Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG)
                            .data("data:image/png;base64,QUJD").build())
                    .build();
        };
        DialogInsertAdvisor advisor = new DialogInsertAdvisor(registry, TASK_ID, agent, materializer, exec);
        registry.getOrCreateDialogInsertQueue(TASK_ID).offer(stub("图.png 看图", "图.png 看图[[[[token]]]]"));

        ChatClientRequest out = sendThrough(advisor, "跑个任务");

        assertEquals(List.of("图.png 看图|图.png 看图[[[[token]]]]|true"), calls,
                "队列项必须带 rawContent 与执行上下文过核心物化服务");
        List<Message> instructions = out.prompt().getInstructions();
        UserMessage inserted = (UserMessage) instructions.get(1);
        assertTrue(inserted.getText().contains("（附图：图.png）"),
                "投给模型的是物化后的正文（不是裸路径）: " + inserted.getText());
        assertEquals(1, inserted.getMedia().size(), "图片 Media 随本条消息进本轮模型调用");

        ArgumentCaptor<EmitEvent> event = ArgumentCaptor.forClass(EmitEvent.class);
        verify(emitter).emit(event.capture());
        assertEquals("（附图：图.png）图.png 看图", event.getValue().content(),
                "user.message 正文同为物化结果（前端与模型看到同一份）");
        assertEquals(1, conversation.size(), "会话内存追加一条");
        assertEquals(0, ((UserMessage) conversation.get(0)).getMedia().size(),
                "会话内存只进正文（媒体由 metadata.attachments 经 FileAttachmentAdvisor 注入，与轮次路径一致）");
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    /** 恒等物化替身（不改写、不带附件）：只关心 drain/事件/会话行为的用例用它。 */
    private static UserInputMaterializer identityMaterializer() {
        return (text, rawContent, ctx) -> UserMessage.builder().text(text).build();
    }

    private static TaskRuntime running() {
        TaskRuntime t = mock(TaskRuntime.class);
        when(t.terminal()).thenReturn(false);
        when(t.status()).thenReturn("running");
        when(t.taskId()).thenReturn(TASK_ID);
        when(t.summaryJson()).thenReturn(Json.obj());
        return t;
    }

    private static TaskService taskService(TaskRuntime t) {
        TaskService service = mock(TaskService.class);
        when(service.get(TASK_ID)).thenReturn(t);
        return service;
    }

    /** 把请求过一遍 advisor 的下行注入，取它交给下一层链的请求。 */
    private static ChatClientRequest sendThrough(DialogInsertAdvisor advisor, String userText) {
        return sendThrough(advisor, requestWith(userText));
    }

    private static ChatClientRequest sendThrough(DialogInsertAdvisor advisor, ChatClientRequest request) {
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        ArgumentCaptor<ChatClientRequest> captor = ArgumentCaptor.forClass(ChatClientRequest.class);
        when(chain.nextStream(captor.capture())).thenReturn(Flux.<ChatClientResponse>empty());
        advisor.adviseStream(request, chain).subscribe();
        return captor.getValue();
    }

    private static ChatClientRequest requestWith(String userText) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage(userText)), mock(ChatOptions.class)))
                .build();
    }

    private static TaskLifecycleContext stub(String input, String rawContent) {
        return stubWithRunParams(input, rawContent, Map.of(), null);
    }

    private static TaskLifecycleContext stubWithRunParams(String input, String rawContent,
            Map<String, Object> runParams, Object rpcContext) {
        return new TaskLifecycleContext() {
            @Override public String taskId() { return TASK_ID; }
            @Override public String title() { return ""; }
            @Override public String workspaceRoot() { return ""; }
            @Override public String workspaceId() { return "defaultworkspace"; }
            @Override public String mainAgentId() { return "a_main"; }
            @Override public String status() { return "running"; }
            @Override public TaskRuntime taskInfo() { return null; }
            @Override public Object taskLock() { return new Object(); }
            @Override public long startedAt() { return 0; }
            @Override public void startedAt(long ms) { }
            @Override public void onUsageBroadcast(Runnable hook) { }
            @Override public String input() { return input; }
            @Override public String rawContent() { return rawContent; }
            @Override public Map<String, Object> runParams() { return runParams; }
            @Override public Object rpcContext() { return rpcContext; }
            @Override public void input(String i) { }
            @Override public void rawContent(String r) { }
            @Override public void runParams(Map<String, Object> r) { }
            @Override public void metadata(Map<String, Object> m) { }
        };
    }
}
