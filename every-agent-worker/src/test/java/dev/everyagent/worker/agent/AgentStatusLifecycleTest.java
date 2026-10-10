package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * per-run agent 生命周期状态机（§7.20.1）单测：{@code AgentEntity} 是 agent.* 事件的
 * 唯一发射口，本测直接断言「哪个信号发哪些事件、发几次」——不依赖模型与 advisor 链
 * （advisor 只是把这些信号从流生命周期翻译过来）。
 *
 * <p>覆盖：一轮一次出生、终态 CAS 唯一、error→done→status 的发射次序（台账 done 会写
 * completed，终态 status 必须后发）、ask 的 waiting-user ⇄ running 翻转、
 * 新一轮 run() 复位后重新出生、跨 creator 一视同仁。
 */
class AgentStatusLifecycleTest {

    /** 记录发射到的事件（kind + status + data 里的 creator）。 */
    private static final class Recorder {
        final List<EmitEvent> events = new ArrayList<>();

        @Override
        public String toString() {
            return events.stream().map(e -> e.kind() + ":" + (e.status() == null ? "" : e.status()))
                    .toList().toString();
        }
    }

    private static AgentEntity newEntity(Recorder rec, String creator) {
        EventEmitter upstream = e -> {
            rec.events.add(e);
            return 1L;
        };
        return new AgentEntity("a-1", "标题", null, null, List.of(),
                upstream, new StubExecContext(), creator, Map.of());
    }

    @Test
    void oneRunEmitsBirthOnceAndTerminalOnce() {
        Recorder rec = new Recorder();
        AgentEntity a = newEntity(rec, "subagent");

        a.beginRun();
        a.beginRun(); // 同一轮重复进入(防御):不重发出生
        assertEquals(List.of("agent.started", "agent.status"),
                rec.events.stream().map(EmitEvent::kind).toList(), rec.toString());
        assertEquals("running", rec.events.get(1).status());

        assertTrue(a.claimTerminal(AgentEntity.MACRO_COMPLETED, null));
        assertFalse(a.claimTerminal(AgentEntity.MACRO_COMPLETED, null)); // CAS:第二轮声明失败
        assertFalse(a.claimTerminal(AgentEntity.MACRO_ERROR, "晚了"));
        assertEquals("completed", a.status());

        List<String> kinds = rec.events.stream().map(EmitEvent::kind).toList();
        assertEquals(List.of("agent.started", "agent.status", "agent.done", "agent.status"), kinds, rec.toString());
        assertEquals("done", rec.events.get(3).status());
    }

    @Test
    void failureOrderIsErrorThenDoneThenTerminalStatus() {
        Recorder rec = new Recorder();
        AgentEntity a = newEntity(rec, "task");
        a.beginRun();
        assertTrue(a.claimTerminal(AgentEntity.MACRO_ERROR, "连接被重置"));

        List<String> seq = rec.events.stream()
                .map(e -> e.kind() + (e.status() == null ? "" : "/" + e.status()))
                .toList();
        // 台账 agent.done 分支无条件写 status=completed —— 终态 status 必须发在 done 之后
        assertEquals(List.of("agent.started", "agent.status/running", "error",
                "agent.done", "agent.status/failed"), seq, rec.toString());
        assertEquals("连接被重置", rec.events.get(2).content());
        assertEquals("error", a.status());
        assertTrue(a.finished());
    }

    @Test
    void cancelCarriesCancelledNoteButNoCompletedStatus() {
        Recorder rec = new Recorder();
        AgentEntity a = newEntity(rec, "subagent");
        a.beginRun();
        assertTrue(a.claimTerminal(AgentEntity.MACRO_STOPPED, "已取消"));
        assertEquals("stopped", a.status());
        assertEquals("stopped", rec.events.get(rec.events.size() - 1).status());
        assertEquals("已取消", a.activity().error());
    }

    @Test
    void askFlipsWaitingUserAndBack() {
        Recorder rec = new Recorder();
        AgentEntity a = newEntity(rec, "task");
        a.beginRun();
        int before = rec.events.size();

        assertTrue(a.markWaitingUser());
        assertFalse(a.markWaitingUser()); // 已在 waiting-user,不重复发
        assertEquals("waiting-user", rec.events.get(before).status());

        assertTrue(a.markAskResolved());
        assertFalse(a.markAskResolved());
        assertEquals("running", rec.events.get(before + 1).status());
    }

    @Test
    void askAfterTerminalDoesNotResurrectAgent() {
        Recorder rec = new Recorder();
        AgentEntity a = newEntity(rec, "task");
        a.beginRun();
        a.claimTerminal(AgentEntity.MACRO_STOPPED, "已取消");
        int size = rec.events.size();
        assertFalse(a.markAskResolved()); // 本轮已收口:不翻回 running
        assertEquals(size, rec.events.size());
    }

    @Test
    void nextRunResetsAndGivesAFreshBirth() {
        Recorder rec = new Recorder();
        AgentEntity a = newEntity(rec, "subagent");
        a.beginRun();
        assertTrue(a.claimTerminal(AgentEntity.MACRO_COMPLETED, null));

        // 队列取下一条输入 / 复用续跑:新一轮 run()
        a.beginRun();
        String lastKind = rec.events.get(rec.events.size() - 1).kind();
        String lastStatus = rec.events.get(rec.events.size() - 1).status();
        assertEquals("agent.status", lastKind);
        assertEquals("running", lastStatus); // started 之后立刻 running
        assertEquals("running", a.status());
        assertTrue(a.claimTerminal(AgentEntity.MACRO_COMPLETED, null)); // 新的一轮可以再收口
    }

    @Test
    void startedPayloadCarriesTopLevelCreator() {
        Recorder rec = new Recorder();
        newEntity(rec, "ai-review").beginRun();
        EmitEvent started = rec.events.get(0);
        assertEquals("agent.started", started.kind());
        assertEquals("a-1", started.agentId()); // agent 级 emitter 兜底填 agentId
        tools.jackson.databind.node.ObjectNode data =
                (tools.jackson.databind.node.ObjectNode) started.data();
        assertEquals("ai-review", data.path("creator").asString(""),
                "agent.started data 携带顶级 creator: " + data);
        assertEquals("a-1", data.path("agentId").asString(""));
    }

    /** 最小 ExecContext 桩（本测不发任务级事件，只占类型位）。 */
    private record StubExecContext() implements ExecContext {
        @Override public String subjectId() { return "t-1"; }
        @Override public String workspaceRoot() { return "/tmp"; }
        @Override public String workspaceId() { return "defaultworkspace"; }
        @Override public ModelConfig snapshot() { return null; }
        @Override public EventEmitter emitter() { return e -> 1L; }
        @Override public dev.everyagent.plugin.api.agent.AgentFactory agentFactory() { return null; }
        @Override public Map<String, Object> metadata() { return Map.of(); }
        @Override public Path dataDir() { return Path.of("/tmp"); }
        @Override public boolean terminal() { return false; }
        @Override public InteractionService interaction() { return null; }
        @Override public Map<String, AgentContext> agents() { return new java.util.HashMap<>(); }
    }
}
