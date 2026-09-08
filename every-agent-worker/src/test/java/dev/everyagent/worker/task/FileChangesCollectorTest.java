package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FileChangesCollector 文件变更聚合单测(重点覆盖新增的 buildLightSummary):
 * 轻量摘要数组只含 filePath/fileName/changeType/saveCount 四字段,不含 before/after 全文
 * (全文保持 buildContent 单独落盘 file-changes/&lt;roundId&gt;.json);同文件多次保存聚合为一条。
 */
class FileChangesCollectorTest {

    private final FileChangesCollector c = new FileChangesCollector();

    private void save(String path, String before, String after, String type) {
        c.onFileSaved("main", path, before, after, type);
    }

    @Test
    void buildLightSummaryContainsOnlySummaryFields() {
        save("/a.md", "", "内容A", "created");
        save("/b/c.txt", "旧", "新", "modified");
        ArrayNode light = c.buildLightSummary();
        assertEquals(2, light.size(), "两条文件保存聚合成两个文件级条目");
        for (JsonNode n : light) {
            assertTrue(n.path("filePath").isTextual(), "含 filePath: " + n);
            assertTrue(n.path("fileName").isTextual(), "含 fileName: " + n);
            assertTrue(n.path("changeType").isTextual(), "含 changeType: " + n);
            assertTrue(n.path("saveCount").isInt(), "含 saveCount: " + n);
            assertTrue(n.path("beforeContent").isMissingNode(), "轻量摘要不含 beforeContent: " + n);
            assertTrue(n.path("afterContent").isMissingNode(), "轻量摘要不含 afterContent: " + n);
        }
        // 按 filePath 升序(与 buildSummaries 同口径)
        assertEquals("/a.md", light.get(0).path("filePath").asString());
        assertEquals("/b/c.txt", light.get(1).path("filePath").asString());
        assertEquals("created", light.get(0).path("changeType").asString());
        assertEquals(1, light.get(0).path("saveCount").asInt());
        assertEquals("a.md", light.get(0).path("fileName").asString());
    }

    @Test
    void buildLightSummaryAggregatesSameFileSaves() {
        save("/a.md", "", "内容A", "created");
        save("/a.md", "", "内容B", "created"); // 同文件二次保存 → saveCount 累加,净类型不变
        ArrayNode light = c.buildLightSummary();
        assertEquals(1, light.size(), "同文件聚合为一条");
        assertEquals("/a.md", light.get(0).path("filePath").asString());
        assertEquals("created", light.get(0).path("changeType").asString());
        assertEquals(2, light.get(0).path("saveCount").asInt());
    }

    @Test
    void buildContentKeepsFullFieldsSameSourceAsLight() {
        save("/a.md", "", "内容A", "created");
        save("/a.md", "", "内容B", "created");
        ObjectNode full = c.buildContent();
        JsonNode changes = full.path("changes");
        assertEquals(1, changes.size(), "同文件聚合为一条");
        JsonNode item = changes.get(0);
        assertEquals("/a.md", item.path("filePath").asString());
        assertEquals("created", item.path("changeType").asString());
        assertEquals(2, item.path("saveCount").asInt());
        assertTrue(item.path("beforeContent").isTextual(), "全文含 beforeContent");
        assertTrue(item.path("afterContent").isTextual(), "全文含 afterContent");
        // 轻量摘要与全文同源(同 filePath/changeType/saveCount),但字段集不同
        JsonNode light0 = c.buildLightSummary().get(0);
        assertEquals(item.path("filePath"), light0.path("filePath"));
        assertEquals(item.path("saveCount"), light0.path("saveCount"));
        assertFalse(light0.has("beforeContent"), "轻量摘要不含全文字段");
    }
}
