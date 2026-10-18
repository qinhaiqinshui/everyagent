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
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
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
 * SubAgentManager 会话交替不变量 + 子 agent 跨轮可见性/续跑单测。
 *
 * <p>装配桩复刻 worker {@code AgentBuilder.build()} 的两条语义:
 * systemPrompt/userInput/conversation 种子组合为 [system?, ...prior, user?](与
 * AgentFactoryImpl 适配器同一装配顺序)、build() 自动把实体注册进 {@code agents()}。
 *
 * <p>跨轮场景(2026-12 bug:list_agents 下一轮返回空):{@code ctx.agents()} 是
 * per-run 注册表——每轮换新 Map(冷启动形态),同一 subjectId 共享 Manager 的
 * per-subject 状态(carry-over 快照),磁盘台账经 {@code ctx.dataDir()/agents.json} 兜底。
 */
class SubAgentManagerTest {

    /** 本轮(当前 run)的活动 agent 注册表;跨轮测试中途整体换新(模拟冷启动)。 */
    private final Map<String, AgentContext> agents = new HashMap<>();
    /** 主体数据目录;null = 无磁盘台账(worker 内存态/单测常态)。 */
    private Path dataDir;

    @Test
    void reuseRunWritesBackFinalAnswerBeforeNextInstruction() throws Exception {
        SubAgentManager manager = new SubAgentManager();
        AtomicReference<String> lastText = new AtomicReference<>("第一轮结论");
        ExecContext ctx = execContext("t-1", lastText);

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
        String secondRun = manager.run(ctx, "第二条指令", "标题", "sub-1");
        assertEquals("子 agent 已启动(异步): sub-1", secondRun,
                "wait_agents 报 completed 后立即续跑不得被「已在运行」守卫误拒(终态早退竞态)");
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
        ExecContext ctx = execContext("t-1", new AtomicReference<>("")); // 无最终回答(被中断形态)

        manager.run(ctx, "唯一指令", "标题", "sub-2");
        Agent sub = (Agent) agents.get("sub-2");
        manager.waitFor(ctx, "sub-2", 5_000L);

        List<Message> conv = sub.conversation();
        assertEquals(3, conv.size(), "会话应为 [system, user1, assistant占位]");
        AssistantMessage placeholder = assertInstanceOf(AssistantMessage.class, conv.get(2));
        assertEquals("[上一轮被中断,无最终回答]", placeholder.getText(),
                "空结论路径记占位轮,防续跑时出现连续 user");
    }

    /**
     * 跨轮可见性 + 跨轮续跑(bug 主场景):上一轮收口(主 agent 未 wait / 用户点停止均同路径)
     * 后,新一轮冷启动(per-run agents 注册表为空 Map)——list/wait/stop 仍能看到上一轮的
     * 子 agent(终态),run_agent 传同 agentId 经 carry-over 重建续跑(会话完整延续)。
     */
    @Test
    void crossRoundVisibilityAndContinuation() throws Exception {
        SubAgentManager manager = new SubAgentManager();
        AtomicReference<String> lastText = new AtomicReference<>("第一轮结论");

        // ---- 第一轮:派发 + 收口 ----
        ExecContext round1 = execContext("t-1", lastText);
        manager.run(round1, "第一条指令", "标题", "sub-1");
        manager.waitFor(round1, "sub-1", 5_000L);

        // ---- 第二轮:冷启动(新 agents 注册表;生命周期下行段 resetForRun) ----
        agents.clear();
        manager.resetForRun("t-1");
        ExecContext round2 = execContext("t-1", lastText);

        // list_agents:上一轮的 sub-1 必须在场(终态 completed),不再是空列表
        JsonNode listed = dev.everyagent.contract.json.Json.parse(manager.list(round2));
        JsonNode arr = listed.path("agents");
        assertEquals(1, arr.size(), "跨轮 list_agents 应包含上一轮的子 agent,实际: " + manager.list(round2));
        assertEquals("sub-1", arr.get(0).path("agentId").asString());
        assertEquals("completed", arr.get(0).path("status").asString(), "收口后的跨轮条目应报终态");
        assertEquals("标题", arr.get(0).path("title").asString());

        // wait_agents 单查:历史 agent 直接落定返回摘要
        JsonNode waited = dev.everyagent.contract.json.Json.parse(manager.waitFor(round2, "sub-1", 1_000L));
        assertEquals("completed", waited.path("waitStatus").asString());
        assertEquals("completed", waited.path("agent").path("status").asString());

        // stop_agent:历史 agent 已终态,无需停止
        assertTrue(manager.stop(round2, "sub-1").contains("已结束(completed)"),
                "历史 agent 停止请求应答已结束");

        // run_agent 跨轮续跑:carry-over 重建实体,会话延续 + 新指令
        lastText.set("第二轮结论");
        assertEquals("子 agent 已启动(异步): sub-1",
                manager.run(round2, "第二条指令", null, "sub-1"));
        Agent rebuilt = (Agent) agents.get("sub-1");
        manager.waitFor(round2, "sub-1", 5_000L);

        List<Message> conv = rebuilt.conversation();
        assertEquals(5, conv.size(), "跨轮续跑会话应为 [system, user1, assistant1, user2, assistant2],实际轮数: "
                + conv.size());
        assertInstanceOf(SystemMessage.class, conv.get(0), "carry-over 快照应含原 system 轮");
        assertInstanceOf(UserMessage.class, conv.get(1));
        assertEquals("第一轮结论", ((AssistantMessage) conv.get(2)).getText());
        assertInstanceOf(UserMessage.class, conv.get(3), "新指令追加在历史之后");
        assertEquals("第二轮结论", ((AssistantMessage) conv.get(4)).getText());
        for (int i = 1; i < conv.size(); i++) {
            assertTrue(!(conv.get(i) instanceof UserMessage && conv.get(i - 1) instanceof UserMessage),
                    "跨轮续跑后仍不得出现连续 user(第 " + i + " 项)");
        }
    }

    /**
     * 磁盘台账兜底(worker 重启后):carry-over 随进程丢失,agents.json 是唯一来源——
     * list_agents 经磁盘台账可见;非在场实体的 running/waiting-user 归一 stopped;
     * creator=task 的主 agent 条目不进子 agent 列表。
     */
    @Test
    void diskLedgerFallbackAfterRestart(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        dataDir = tmp;
        Files.writeString(tmp.resolve("agents.json"), """
                {"agents":[
                  {"agentId":"main-1","creator":"task","title":"主 agent","status":"completed","createdAt":1},
                  {"agentId":"sub-9","creator":"subagent","title":"重启前的子任务","status":"running","createdAt":2,
                   "latestActivity":{"content":"跑到一半","updatedAt":99}}
                ]}
                """);

        SubAgentManager manager = new SubAgentManager(); // 新进程:无内存 carry-over
        ExecContext ctx = execContext("t-2", new AtomicReference<>(""));

        JsonNode listed = dev.everyagent.contract.json.Json.parse(manager.list(ctx));
        JsonNode arr = listed.path("agents");
        assertEquals(1, arr.size(), "磁盘兜底列表只含 subagent 条目(主 agent 剔除),实际: " + manager.list(ctx));
        assertEquals("sub-9", arr.get(0).path("agentId").asString());
        assertEquals("stopped", arr.get(0).path("status").asString(),
                "重启后无在场实体,running 应归一 stopped(与 AgentLedger 冷启动归一同规则)");
        assertEquals("重启前的子任务", arr.get(0).path("title").asString());
        assertEquals("跑到一半", arr.get(0).path("latestActivity").path("content").asString());

        // wait/stop 对磁盘历史 agent 同样可用
        assertEquals("completed",
                dev.everyagent.contract.json.Json.parse(manager.waitFor(ctx, "sub-9", 1_000L))
                        .path("waitStatus").asString());
        assertTrue(manager.stop(ctx, "sub-9").contains("已结束(stopped)"));
    }

    /** ExecContext 桩:subjectId/agents()/dataDir/agentFactory()/interaction 槽位,装配桩自动注册子 agent。 */
    private ExecContext execContext(String subjectId, AtomicReference<String> lastText) {
        ExecContext ctx = mock(ExecContext.class);
        InteractionService interaction = mock(InteractionService.class);
        // 先建好桩再 stub:在 thenReturn 里现造 mock/工厂会触发 Mockito UnfinishedStubbing
        AgentFactory agentFactory = factory(lastText);
        when(ctx.subjectId()).thenReturn(subjectId);
        when(ctx.agents()).thenAnswer(inv -> agents); // 字段读取:跨轮换 Map 后取到新表
        when(ctx.dataDir()).thenAnswer(inv -> dataDir);
        when(ctx.interaction()).thenReturn(interaction);
        when(ctx.agentFactory()).thenReturn(agentFactory);
        return ctx;
    }

    /** AgentFactory 桩:复刻 worker 适配器的会话装配语义([system?, ...prior, user?])+ build() 自动注册。 */
    private AgentFactory factory(AtomicReference<String> lastText) {
        AgentFactory factory = mock(AgentFactory.class);
        AgentBuilder build = mock(AgentBuilder.class);
        AtomicReference<String> creatingId = new AtomicReference<>();
        AtomicReference<String> systemPrompt = new AtomicReference<>();
        AtomicReference<String> userInput = new AtomicReference<>();
        List<Message> seed = new ArrayList<>();

        when(factory.create(anyString())).thenAnswer(inv -> {
            creatingId.set(inv.getArgument(0));
            systemPrompt.set(null);
            userInput.set(null);
            seed.clear();
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
        when(build.conversation(any())).thenAnswer(inv -> {
            seed.addAll(inv.getArgument(0));
            return build;
        });
        when(build.build()).thenAnswer(inv -> {
            String id = creatingId.get();
            List<Message> conversation = new ArrayList<>();
            if (systemPrompt.get() != null) {
                conversation.add(new SystemMessage(systemPrompt.get()));
            }
            conversation.addAll(seed);
            if (userInput.get() != null) {
                conversation.add(new UserMessage(userInput.get()));
            }
            Agent agent = mock(Agent.class);
            AtomicReference<String> status = new AtomicReference<>("running");
            when(agent.agentId()).thenReturn(id);
            when(agent.title()).thenReturn("标题");
            when(agent.creator()).thenReturn("subagent");
            when(agent.createdAt()).thenReturn(System.currentTimeMillis());
            when(agent.status()).thenAnswer(s -> status.get());
            when(agent.finished()).thenReturn(true);
            when(agent.activity()).thenReturn(new AgentActivity(null, null, null, null, null));
            when(agent.conversation()).thenReturn(conversation);
            when(agent.lastText()).thenAnswer(lt -> lastText.get());
            when(agent.claimTerminal(anyString(), (String) any())).thenAnswer(ct -> {
                status.set(ct.getArgument(0));
                return true;
            });
            when(agent.claimTerminal(anyString())).thenAnswer(ct -> {
                status.set(ct.getArgument(0));
                return true;
            });
            org.mockito.Mockito.doAnswer(rr -> {
                status.set("running");
                return null;
            }).when(agent).resetForRerun();
            agents.put(id, agent); // 复刻 AgentBuilder.build() 的自动注册
            return agent;
        });
        return factory;
    }
}
