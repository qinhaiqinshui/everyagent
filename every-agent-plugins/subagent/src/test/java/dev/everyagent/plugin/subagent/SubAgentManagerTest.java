package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.agent.Agent;
import dev.everyagent.plugin.api.agent.AgentActivity;
import dev.everyagent.plugin.api.agent.AgentBuilder;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.InteractionService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SubAgentManager 会话交替不变量单测:同 agentId 续跑(run_agent 复用)时,
 * 上一轮最终回答必须以 assistant 轮在场,否则子 agent 会看到两条连续 user 并重复回答上一轮指令
 * (agent 执行链不回写会话内存,回写责任在复用方;主 agent 侧同一条不变量由
 * ConversationLoader.catchUpRuntime 维持)。
 *
 * <p>装配桩复刻 worker {@code AgentBuilder.build()} 的两条语义:
 * systemPrompt/userInput 在 build() 时进会话内存;build() 自动把实体注册进 {@code agents()}。
 */
class SubAgentManagerTest {

    private final Map<String, AgentContext> agents = new HashMap<>();

    @Test
    void reuseRunWritesBackFinalAnswerBeforeNextInstruction() throws Exception {
        SubAgentManager manager = new SubAgentManager();
        AtomicReference<String> lastText = new AtomicReference<>("第一轮结论");
        ExecContext ctx = execContext(lastText);

        assertEquals("子 agent 已启动(异步): sub-1", manager.run(ctx, "第一条指令", "标题", "sub-1"));
        Agent sub = (Agent) agents.get("sub-1");
        manager.waitFor(ctx, "sub-1", 5_000L); // 等本轮落定,复现「已落定 → 复用续跑」

        List<Message> conv = sub.conversation();
        assertEquals(3, conv.size(), "首次运行后会话应为 [system, user1, assistant1]");
        assertInstanceOf(SystemMessage.class, conv.get(0));
        assertInstanceOf(UserMessage.class, conv.get(1));
        AssistantMessage firstAnswer = assertInstanceOf(AssistantMessage.class, conv.get(2));
        assertEquals("第一轮结论", firstAnswer.getText(), "本轮最终回答应作为 assistant 轮回写会话");

        lastText.set("第二轮结论");
        manager.run(ctx, "第二条指令", "标题", "sub-1");
        manager.waitFor(ctx, "sub-1", 5_000L);

        conv = sub.conversation();
        assertEquals(5, conv.size(), "续跑后会话应为 [system, user1, assistant1, user2, assistant2]");
        assertInstanceOf(UserMessage.class, conv.get(3));
        AssistantMessage secondAnswer = assertInstanceOf(AssistantMessage.class, conv.get(4));
        assertEquals("第二轮结论", secondAnswer.getText(), "续跑轮的回答同样回写");
        for (int i = 1; i < conv.size(); i++) {
            assertTrue(!(conv.get(i) instanceof UserMessage && conv.get(i - 1) instanceof UserMessage),
                    "会话内不得出现连续 user(第 " + i + " 项)");
        }
    }

    @Test
    void interruptedRunWritesPlaceholderAssistantTurn() throws Exception {
        SubAgentManager manager = new SubAgentManager();
        ExecContext ctx = execContext(new AtomicReference<>("")); // 无最终回答(被中断形态)

        manager.run(ctx, "唯一指令", "标题", "sub-2");
        Agent sub = (Agent) agents.get("sub-2");
        manager.waitFor(ctx, "sub-2", 5_000L);

        List<Message> conv = sub.conversation();
        assertEquals(3, conv.size(), "会话应为 [system, user1, assistant占位]");
        AssistantMessage placeholder = assertInstanceOf(AssistantMessage.class, conv.get(2));
        assertEquals("[上一轮被中断,无最终回答]", placeholder.getText(),
                "空结论路径记占位轮,防续跑时出现连续 user");
    }

    /** ExecContext 桩:subjectId/agents()/agentFactory()/interaction 槽位,装配桩自动注册子 agent。 */
    private ExecContext execContext(AtomicReference<String> lastText) {
        ExecContext ctx = mock(ExecContext.class);
        InteractionService interaction = mock(InteractionService.class);
        // 先建好桩再 stub:在 thenReturn 里现造 mock/工厂会触发 Mockito UnfinishedStubbing
        AgentFactory agentFactory = factory(lastText);
        when(ctx.subjectId()).thenReturn("t-1");
        when(ctx.agents()).thenReturn(agents);
        when(ctx.interaction()).thenReturn(interaction);
        when(ctx.agentFactory()).thenReturn(agentFactory);
        return ctx;
    }

    /** AgentFactory 桩:复刻 worker AgentBuilder 的会话装配 + build() 自动注册语义。 */
    private AgentFactory factory(AtomicReference<String> lastText) {
        AgentFactory factory = mock(AgentFactory.class);
        AgentBuilder build = mock(AgentBuilder.class);
        AtomicReference<String> creatingId = new AtomicReference<>();
        AtomicReference<String> systemPrompt = new AtomicReference<>();
        AtomicReference<String> userInput = new AtomicReference<>();

        when(factory.create(anyString())).thenAnswer(inv -> {
            creatingId.set(inv.getArgument(0));
            return build;
        });
        when(build.title(anyString())).thenReturn(build);
        when(build.creator(anyString())).thenReturn(build);
        when(build.tools(any(), any())).thenReturn(build);
        when(build.systemPrompt(anyString())).thenAnswer(inv -> {
            systemPrompt.set(inv.getArgument(0));
            return build;
        });
        when(build.userInput(anyString())).thenAnswer(inv -> {
            userInput.set(inv.getArgument(0));
            return build;
        });
        when(build.build()).thenAnswer(inv -> {
            String id = creatingId.get();
            List<Message> conversation = new ArrayList<>();
            conversation.add(new SystemMessage(systemPrompt.get()));
            conversation.add(new UserMessage(userInput.get()));
            Agent agent = mock(Agent.class);
            when(agent.agentId()).thenReturn(id);
            when(agent.title()).thenReturn("标题");
            when(agent.createdAt()).thenReturn(System.currentTimeMillis());
            when(agent.status()).thenReturn("running");
            when(agent.finished()).thenReturn(true);
            when(agent.activity()).thenReturn(new AgentActivity(null, null, null, null, null));
            when(agent.conversation()).thenReturn(conversation);
            when(agent.lastText()).thenAnswer(lt -> lastText.get());
            agents.put(id, agent); // 复刻 AgentBuilder.build() 的自动注册
            return agent;
        });
        return factory;
    }
}