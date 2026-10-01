package dev.everyagent.plugin.aireview;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.task.TaskService;
import dev.everyagent.plugin.api.slash.SlashCommandItem;
import dev.everyagent.plugin.api.slash.SlashDisplayPosition;
import dev.everyagent.plugin.api.slash.SlashSelectionResult;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiReviewSlashProvider 单测:`/` 候选 id=ai-review:on;
 * select 返回单个 bottom 结果;taskId 非空时置 metadata["ai-review"]=true 并落盘,
 * 为空时不写业务标记仍返回胶囊;cancel 复位并落盘。
 *
 * <p>约束(§14.9):插件对 worker 任何 scope 零依赖——直接断言 provider 静态产物
 * (聚合是 worker SlashCommandRegistry 的职责,不在本插件测试范围),
 * 任务桩在本类内自建(实现 plugin-api TaskRuntime),不引用 worker TaskEntry。
 */
class AiReviewSlashProviderTest {

    private WorkerServices services;
    private TaskService taskService;
    private StubTaskRuntime task;

    @BeforeEach
    void setUp() {
        services = mock(WorkerServices.class);
        taskService = mock(TaskService.class);
        task = new StubTaskRuntime();
        when(services.task()).thenReturn(taskService);
        when(taskService.get("t-1")).thenReturn(task);
    }

    private SlashCommandItem item() {
        return AiReviewSlashProvider.items(services).stream()
                .filter(i -> "ai-review:on".equals(i.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未注册 ai-review:on 条目"));
    }

    // ---- 候选 ----

    @Test
    void itemsExposeAiReviewCandidate() {
        SlashCommandItem it = item();
        assertEquals("ai-review:on", it.id());
        assertEquals("AI 审议", it.title());
        assertTrue(it.defaultSelected(), "AI 审议应默认选中(新建任务时预置底部胶囊)");
        assertTrue(AiReviewToken.enabledIn(it.insertText()), "insertText 应为 AI 审议 opaque token");
    }

    // ---- select:单个 bottom 胶囊 ----

    @Test
    void selectReturnsSingleBottomCapsuleAndTurnsOnAiReview() {
        SlashCommandItem it = item();
        List<SlashSelectionResult> results = it.selectHandler().onSelect(it, "t-1");

        assertEquals(1, results.size(), "AI 审议 onSelect 应返回单胶囊");
        SlashSelectionResult r = results.get(0);
        assertEquals("ai-review:on", r.id(), "胶囊归属 AI 审议条目");
        assertEquals(SlashDisplayPosition.BOTTOM, r.position(), "AI 审议胶囊 bottom 渲染");
        SlashTokenEncoder.ParsedToken tok = SlashTokenEncoder.parseToken(r.token());
        assertNotNull(tok, "token 可 parse");
        assertEquals(AiReviewToken.KIND, tok.kind(), "token kind=ai.review");

        verify(taskService).get("t-1");
        assertTrue(Boolean.TRUE.equals(task.metadata().getOrDefault("ai-review", false)), "业务标记 ai-review 应置位");
        verify(taskService).publishUpdated("t-1");
    }

    // ---- select:空 taskId 不写业务标记 ----

    @Test
    void selectWithEmptyTaskIdStillReturnsCapsuleWithoutBusinessMark() {
        SlashCommandItem it = item();
        List<SlashSelectionResult> results = it.selectHandler().onSelect(it, "");

        assertEquals(1, results.size(), "空 taskId(草稿态)仍返回胶囊");
        assertEquals("ai-review:on", results.get(0).id());
        verify(taskService, never()).get(any());
        assertFalse(Boolean.TRUE.equals(task.metadata().getOrDefault("ai-review", false)), "空 taskId 不写业务标记");
        verify(taskService, never()).publishUpdated(any());
    }

    // ---- cancel:复位 aiReview ----

    @Test
    void cancelTurnsAiReviewOff() {
        task.metadata().put("ai-review", true);
        SlashCommandItem it = item();
        it.cancelHandler().onCancel(it, AiReviewToken.buildToken(), "t-1");
        assertFalse(Boolean.TRUE.equals(task.metadata().getOrDefault("ai-review", false)), "取消 AI 审议胶囊应复位 aiReview");
        verify(taskService).publishUpdated("t-1");
    }

    // ---- 自建等价桩(实现 plugin-api 接口;§14.9) ----

    /** TaskRuntime 最小桩(原借 worker TaskEntry):provider 只读写 metadata()。 */
    private static final class StubTaskRuntime implements TaskRuntime {
        private final Map<String, Object> metadata = new HashMap<>();

        @Override public String taskId() { return "t-1"; }
        @Override public String status() { return "running"; }
        @Override public boolean terminal() { return false; }
        @Override public Map<String, Object> metadata() { return metadata; }
        @Override public Path taskDir() { return Path.of("workspaces", "defaultworkspace", "tasks", "t-1"); }
        @Override public String workspaceRoot() { return "ws"; }
        @Override public String workspaceId() { return "defaultworkspace"; }
        @Override public String mainAgentId() { return "main-agent"; }
        @Override public ModelConfig snapshot() {
            return new ModelConfig("cfg", "openai-compat", "http://localhost:9999/v1", "m", null);
        }
        @Override public dev.everyagent.plugin.api.model.EventEmitter events() { return e -> e.id(); }
        @Override public Map<String, AgentContext> agents() { return new HashMap<>(); }
        @Override public dev.everyagent.plugin.api.agent.AgentFactory agentFactory() { return null; }
        @Override public dev.everyagent.plugin.api.interaction.InteractionService interaction() { return null; }
        @Override public AgentContext main() { return null; }
        @Override public dev.everyagent.plugin.api.event.EventLogReader log() {
            return new dev.everyagent.plugin.api.event.EventLogReader() {
                @Override public List<dev.everyagent.plugin.api.event.EventRecord> readFrom(int from, int max) { return List.of(); }
                @Override public List<dev.everyagent.plugin.api.event.EventRecord> readAfterSeq(long afterSeq, int max) { return List.of(); }
                @Override public void addListener(Listener listener) { }
                @Override public void removeListener(Listener listener) { }
            };
        }
        @Override public dev.everyagent.plugin.api.task.FileChangesCollector fileChanges() { return null; }
        @Override public void fileChanges(dev.everyagent.plugin.api.task.FileChangesCollector collector) { }
        @Override public tools.jackson.databind.JsonNode fileChangesLight() { return null; }
        @Override public void fileChangesLight(tools.jackson.databind.JsonNode light) { }
        @Override public tools.jackson.databind.JsonNode fileChangesFull() { return null; }
        @Override public void fileChangesFull(tools.jackson.databind.JsonNode full) { }
        @Override public long startedAt() { return 0; }
        @Override public long endedAt() { return 0; }
        @Override public void touch() { }
        @Override public tools.jackson.databind.node.ObjectNode summaryJson() { return null; }
        @Override public void truncateLogAfter(long targetSeq) { }
    }
}
