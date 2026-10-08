package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.worker.plugin.registry.SearchProviderRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 统一搜索结果模型(§8.5)在 task.search 增补聚合的行为单测(纯函数,不依赖 rg):
 * <ol>
 *   <li>内置 rg 任务项标记 {@code kind=task} + {@code providerId=builtin.rg}(score 不设);</li>
 *   <li>去重键升级为 {@code kind}+位置键:同 kind 同位置去重、跨 kind 不去重
 *       (未声明 kind 的 provider 按 task.search 归一为 task,与现状完全一致);</li>
 *   <li>新建 provider 任务项标记 {@code providerId=provider.id()}(自带则尊重不覆盖)、
 *       {@code score} 仅 provider 提供时携带;并入既有任务项不改写首次写入者的标记。</li>
 * </ol>
 */
class UnifiedSearchModelTaskTest {

    /** 不限时 provider 预算(0 = 仅异常护栏,与 worker.search.provider-timeout-ms 默认一致)。 */
    private static final long NO_TIMEOUT = 0;

    // ---- 桩与帮助 ----

    /** 桩 provider:固定返回任务结果列表。 */
    private static final class StubProvider implements SearchProvider {
        private final String id;
        private final List<TaskSearchResult> tasks;

        StubProvider(String id, List<TaskSearchResult> tasks) {
            this.id = id;
            this.tasks = tasks;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public List<SearchResult> searchFiles(SearchRequest req) {
            return List.of();
        }

        @Override
        public List<TaskSearchResult> searchTasks(TaskSearchRequest req) {
            return tasks;
        }
    }

    private static SearchProvider.TaskSearchResult.Match tmatch(int roundIndex, String field, int matchIndex) {
        return new SearchProvider.TaskSearchResult.Match(roundIndex, field, "line " + field, matchIndex, "x");
    }

    private static SearchProvider.TaskSearchRequest anyTaskReq() {
        return new SearchProvider.TaskSearchRequest("w1", "x", false, false, false, 500);
    }

    /** 经生产路径 {@link TaskSearchService#builtinTaskNode} 造内置 rg 形态的任务 outcome(含内置标记)。 */
    private static TaskSearchService.SearchOutcome builtinOutcomeOf(String taskId, String... fields) {
        ObjectNode task = TaskSearchService.builtinTaskNode(
                new TaskStore.StoredTask(taskId, Path.of("."), Json.obj(), "w1"));
        ArrayNode arr = Json.arr();
        int round = 0;
        for (String field : fields) {
            arr.add(Json.obj().put("roundIndex", round).put("field", field).put("line", "l")
                    .put("matchIndex", 0).put("matchText", "x"));
            round++;
        }
        task.set("matches", arr);
        LinkedHashMap<String, ObjectNode> files = new LinkedHashMap<>();
        files.put(taskId, task);
        return new TaskSearchService.SearchOutcome(files, fields.length, false);
    }

    // ---- ① 内置 rg 标记 ----

    @Test
    void builtinTaskNodeMarkedKindTaskAndProviderBuiltinRg() {
        ObjectNode summary = Json.obj().put("title", "标题").put("status", "done");
        ObjectNode n = TaskSearchService.builtinTaskNode(
                new TaskStore.StoredTask("t1", Path.of("."), summary, "w1"));
        assertEquals("t1", n.path("taskId").asString());
        assertEquals("标题", n.path("title").asString(), "既有元数据字段不动");
        assertEquals("task", n.path("kind").asString(), "内置任务项标记 kind=task");
        assertEquals("builtin.rg", n.path("providerId").asString(), "内置任务项标记 providerId=builtin.rg");
        assertFalse(n.has("score"), "内置不设 score");
    }

    // ---- ② 去重键升级:kind + 位置键 ----

    @Test
    void sameKindSamePositionDedupedButCrossKindKept() {
        TaskSearchService.SearchOutcome builtIn = builtinOutcomeOf("t1", "user");
        SearchProviderRegistry reg = new SearchProviderRegistry();
        reg.register(new StubProvider("vec", List.of(
                // kind 缺省归一 task → 与内置同 kind 同位置 → 去重
                new SearchProvider.TaskSearchResult("t1", "ti", "/ws", "w1", "done",
                        List.of(tmatch(0, "user", 0))),
                // 跨 kind(semantic)同位置 → 不去重(§8.5:跨 kind 不判重)
                new SearchProvider.TaskSearchResult("t1", "ti", "/ws", "w1", "done",
                        List.of(tmatch(0, "user", 0)), "semantic", null, null))));

        TaskSearchService.SearchOutcome merged = TaskSearchService.mergeProviderResults(
                builtIn, reg, anyTaskReq(), 500, NO_TIMEOUT);

        assertEquals(2, merged.matchCount(), "task 同位置去重 1,semantic 同位置保留 1");
        JsonNode t1 = merged.files().get("t1");
        assertEquals(2, t1.path("matches").size(), "跨 kind 命中并入同一任务项(任务项键恒 taskId)");
        // 并入既有任务项:不改写首次写入者(内置)的统一模型标记
        assertEquals("task", t1.path("kind").asString());
        assertEquals("builtin.rg", t1.path("providerId").asString());
    }

    // ---- ③ provider 增补字段标记 ----

    @Test
    void newProviderTaskEntriesCarryUnifiedMarks() {
        TaskSearchService.SearchOutcome builtIn = builtinOutcomeOf("t1", "user");
        SearchProviderRegistry reg = new SearchProviderRegistry();
        reg.register(new StubProvider("vec", List.of(
                // 全缺省 → kind 归一 task、providerId 填 provider.id()、score 不输出
                new SearchProvider.TaskSearchResult("t8", "缺省任务", "/ws", "w1", "running",
                        List.of(tmatch(0, "user", 0))),
                // 自带 kind/providerId/score → 尊重不覆盖、score 透传
                new SearchProvider.TaskSearchResult("t9", "自带任务", "/ws", "w1", "running",
                        List.of(tmatch(1, "finalReply", 0)), "semantic", "self.vec", 0.9))));

        TaskSearchService.SearchOutcome merged = TaskSearchService.mergeProviderResults(
                builtIn, reg, anyTaskReq(), 500, NO_TIMEOUT);

        JsonNode t8 = merged.files().get("t8");
        assertEquals("task", t8.path("kind").asString(), "kind 缺省归一 task");
        assertEquals("vec", t8.path("providerId").asString(), "未自带则填 provider.id()");
        assertFalse(t8.has("score"), "score 缺省不输出");
        JsonNode t9 = merged.files().get("t9");
        assertEquals("semantic", t9.path("kind").asString(), "自带 kind 保留");
        assertEquals("self.vec", t9.path("providerId").asString(), "自带 providerId 尊重不覆盖");
        assertEquals(0.9, t9.path("score").asDouble(), "score 透传(仅排序提示)");
        assertEquals(3, merged.matchCount(), "内置 1 + t8 1 + t9 1");
    }
}
