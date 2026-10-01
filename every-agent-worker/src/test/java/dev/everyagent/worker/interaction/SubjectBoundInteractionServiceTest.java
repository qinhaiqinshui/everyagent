package dev.everyagent.worker.interaction;

import dev.everyagent.plugin.api.interaction.AskQuestion;
import dev.everyagent.plugin.api.interaction.AskResult;
import dev.everyagent.plugin.api.interaction.InteractionService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SubjectBoundInteractionService(ExecContext.interaction() 预绑定代理,§4.1):
 * ask/askAsync 的 context 为 null 或缺 "taskId" 键时自动填 subjectId(wire 键名不变),
 * 其余键保留;hasPendingFor 原样透传。
 */
class SubjectBoundInteractionServiceTest {

    /** 记录调用参数的假交互服务。 */
    private static final class RecordingService implements InteractionService {
        volatile Map<String, String> lastContext;
        volatile int calls;

        @Override
        public AskResult ask(List<AskQuestion> questions, long timeoutMs, Map<String, String> context) {
            calls++;
            lastContext = context;
            return new AskResult("answered", "ok");
        }

        @Override
        public void askAsync(List<AskQuestion> questions, long timeoutMs, Map<String, String> context,
                Consumer<AskResult> callback) {
            calls++;
            lastContext = context;
        }

        @Override
        public boolean hasPendingFor(String taskId, String agentId) {
            return "t-1".equals(taskId) && "a-1".equals(agentId);
        }
    }

    private static final List<AskQuestion> QS = List.of(new AskQuestion("", "是否授权?", List.of()));

    @Test
    void nullContextGetsSubjectIdFilled() throws InterruptedException {
        RecordingService raw = new RecordingService();
        SubjectBoundInteractionService svc = new SubjectBoundInteractionService(raw, "t-1");
        AskResult r = svc.ask(QS, 1000, null);
        assertEquals("answered", r.status());
        assertEquals("t-1", raw.lastContext.get("taskId"));
        assertEquals(1, raw.lastContext.size(), "null context 只补 taskId 一键");
    }

    @Test
    void missingTaskIdKeyFilledOthersKept() throws InterruptedException {
        RecordingService raw = new RecordingService();
        SubjectBoundInteractionService svc = new SubjectBoundInteractionService(raw, "t-1");
        Map<String, String> ctx = new HashMap<>(Map.of("agentId", "a-1"));
        svc.ask(QS, 1000, ctx);
        assertEquals("t-1", raw.lastContext.get("taskId"));
        assertEquals("a-1", raw.lastContext.get("agentId"), "调用方其余键保留");
        assertEquals(2, raw.lastContext.size());
        assertNull(ctx.get("taskId"), "原传入 map 不被就地修改");
    }

    @Test
    void explicitTaskIdWins() {
        RecordingService raw = new RecordingService();
        SubjectBoundInteractionService svc = new SubjectBoundInteractionService(raw, "t-1");
        svc.askAsync(QS, 1000, Map.of("taskId", "t-other"), r -> { });
        assertEquals("t-other", raw.lastContext.get("taskId"), "已含 taskId 键时原样透传");
    }

    @Test
    void hasPendingForPassthrough() {
        SubjectBoundInteractionService svc =
                new SubjectBoundInteractionService(new RecordingService(), "t-1");
        assertTrue(svc.hasPendingFor("t-1", "a-1"));
    }
}
