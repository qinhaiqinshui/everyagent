package dev.everyagent.worker.task;

import org.junit.jupiter.api.Test;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.slash.SlashTokenHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「任务引用」token 提交解析器：正常 payload 渲染「[引用任务] 标题 (taskId)」、
 * 标题缺省回退「任务 &lt;id&gt;」、缺 taskId 返回 null（保留原串兜底）。
 */
class TaskReferenceTokenResolverTest {

    private final TaskReferenceTokenResolver resolver = new TaskReferenceTokenResolver();

    @Test
    void kindMatches() {
        assertEquals("task.reference", resolver.kind());
        assertTrue(resolver instanceof SlashTokenHandler.SlashTokenResolver);
    }

    @Test
    void rendersTitleAndTaskId() {
        String text = resolver.resolveSubmissionText(Json.obj()
                .put("taskId", "t_abc123")
                .put("title", "修复搜索弹窗"));
        assertEquals("[引用任务] 《修复搜索弹窗》(taskId=t_abc123)", text);
    }

    @Test
    void missingTitleFallsBackToTaskLabel() {
        String text = resolver.resolveSubmissionText(Json.obj().put("taskId", "t_x"));
        assertEquals("[引用任务] 《任务 t_x》(taskId=t_x)", text);
    }

    @Test
    void missingTaskIdReturnsNullToKeepOriginalToken() {
        assertNull(resolver.resolveSubmissionText(Json.obj().put("title", "无 id")));
        assertNull(resolver.resolveSubmissionText(null));
    }
}
