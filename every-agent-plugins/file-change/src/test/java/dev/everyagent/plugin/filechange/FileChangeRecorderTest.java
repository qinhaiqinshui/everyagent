package dev.everyagent.plugin.filechange;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.spi.FileWriteListener;
import dev.everyagent.plugin.api.task.StoredTaskInfo;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.task.TaskService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileChangeRecorder} 文件写监听一阶段记账测试(架构 §7.15.2):
 * <ul>
 *   <li>写盘成功回调(工具锁内触发)→ 记入任务级共享 collector,Source 按
 *       agentId==mainAgentId 标注 MAIN/SUB_AGENT,update 落 modified + 写前/写后全文,
 *       create 落 created + before 为空(文件此前不存在);</li>
 *   <li><b>同文件多次写聚合全文快照</b>(本修复的回归锚点):update_file 的入参
 *       oldcontent/content 只是被替换的<b>片段</b>,监听器必须携带文件级全文,聚合才是
 *       「首条 before 全文 + 末条 after 全文」——否则两段不相干片段拼 diff,轮末面板
 *       呈错误变更(真实故障:同轮 4 次 update_file,分片 diff 显示 -11/+1);</li>
 *   <li>失败调用不回调即不记账(无「假变更」);主体已收口不记账(宁漏记);
 *       无任务上下文/无 agent 归属不收集。</li>
 * </ul>
 *
 * <p>脚手架按 §14.9 插件测试零 worker 依赖:ExecContext/TaskRuntime 均手写桩。
 */
class FileChangeRecorderTest {

    private static final String TASK = "t_fc";
    private static final String MAIN = "a_main";
    private static final String SUB = "a_sub";

    private final TaskService taskService = taskService(MAIN);
    private final FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(taskService);
    private final FileChangeRecorder recorder = new FileChangeRecorder(taskService, provider);

    @Test
    void successfulWritesRecordedWithFullSnapshotsAndSource() {
        // 主 agent update_file 成功:携带写前/写后全文
        recorder.onFileWritten(exec(TASK, false), MAIN, FileWriteListener.TOOL_UPDATE_FILE,
                "/a.md", "写前全文", "写后全文");
        // 子 agent create_file 成功:before=null(文件此前不存在)
        recorder.onFileWritten(exec(TASK, false), SUB, FileWriteListener.TOOL_CREATE_FILE,
                "/b.md", null, "B");

        var summaries = provider.activeCollector(TASK).buildSummaries();
        assertEquals(2, summaries.size(), "两条成功写各记一条");
        assertEquals("/a.md", summaries.get(0).filePath());
        assertEquals("updated", summaries.get(0).changeType());
        assertEquals("写前全文", summaries.get(0).beforeContent(), "update 记写前全文(非入参片段)");
        assertEquals("写后全文", summaries.get(0).afterContent(), "update 记写后全文(非入参片段)");
        assertEquals("created", summaries.get(1).changeType());
        assertEquals("", summaries.get(1).beforeContent(), "create 的 before(null)归一为空串");
        JsonNode meta = provider.activeCollector(TASK).buildMetadata().path("changes");
        assertEquals("MAIN", meta.get(0).path("source").asString(), "主 agent 改动标 MAIN");
        assertEquals("SUB_AGENT", meta.get(1).path("source").asString(), "子 agent 改动标 SUB_AGENT");
    }

    /**
     * 本修复的核心回归锚点:同文件多次写,聚合取「首条 before 全文 + 末条 after 全文 + saveCount」。
     * 旧实现(解析 update_file 入参当全文)会把首次 oldcontent 片段与末次 content 片段拼在一起。
     */
    @Test
    void sameFileTwoWritesAggregateFullSnapshots() {
        recorder.onFileWritten(exec(TASK, false), MAIN, FileWriteListener.TOOL_UPDATE_FILE,
                "/novel/ch1.md", "全文A(修改前)", "全文B");
        recorder.onFileWritten(exec(TASK, false), MAIN, FileWriteListener.TOOL_UPDATE_FILE,
                "/novel/ch1.md", "全文B", "全文C(最终)");

        var summaries = provider.activeCollector(TASK).buildSummaries();
        assertEquals(1, summaries.size(), "同文件聚合为一条");
        assertEquals("全文A(修改前)", summaries.get(0).beforeContent(),
                "before 取首次写前全文(而非首次调用入参片段)");
        assertEquals("全文C(最终)", summaries.get(0).afterContent(),
                "after 取末次写后全文(而非末次调用入参片段)");
        assertEquals(2, summaries.get(0).saveCount());
    }

    @Test
    void terminalSubjectSkipsRecording() {
        recorder.onFileWritten(exec(TASK, true), MAIN, FileWriteListener.TOOL_CREATE_FILE,
                "/d.md", null, "D");

        assertTrue(provider.activeCollector(TASK).buildSummaries().isEmpty(),
                "主体已收口(轮已闭合)不再记账,宁漏记不造孤儿 collector");
    }

    @Test
    void withoutTaskContextOrAgentIdNothingCollected() {
        recorder.onFileWritten(exec(null, false), MAIN, FileWriteListener.TOOL_CREATE_FILE,
                "/a", null, "A");
        recorder.onFileWritten(exec(TASK, false), null, FileWriteListener.TOOL_CREATE_FILE,
                "/b", null, "B");
        recorder.onFileWritten(null, MAIN, FileWriteListener.TOOL_CREATE_FILE, "/c", null, "C");

        assertTrue(provider.activeCollector(TASK).buildSummaries().isEmpty(),
                "无任务上下文/无 agent 归属(裸用)不收集");
    }

    @Test
    void blankPathIsIgnored() {
        recorder.onFileWritten(exec(TASK, false), MAIN, FileWriteListener.TOOL_UPDATE_FILE,
                " ", "x", "y");
        assertTrue(provider.activeCollector(TASK).buildSummaries().isEmpty(), "空路径不收集");
    }

    // ---- 测试脚手架(§14.9:自建桩,不依赖 worker) ----

    /** ExecContext 最小桩:recorder 只读 subjectId/terminal。 */
    private static ExecContext exec(String subjectId, boolean terminal) {
        return (ExecContext) Proxy.newProxyInstance(
                FileChangeRecorderTest.class.getClassLoader(),
                new Class<?>[] { ExecContext.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "subjectId" -> subjectId;
                    case "terminal" -> terminal;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static TaskService taskService(String mainAgentId) {
        TaskRuntime runtime = (TaskRuntime) Proxy.newProxyInstance(
                FileChangeRecorderTest.class.getClassLoader(),
                new Class<?>[] { TaskRuntime.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "mainAgentId" -> mainAgentId;
                    case "taskId", "subjectId" -> TASK;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new TaskService() {
            @Override
            public TaskRuntime get(String taskId) {
                return TASK.equals(taskId) ? runtime : null;
            }

            @Override
            public StoredTaskInfo diskEntry(String taskId) {
                return null;
            }

            @Override
            public void publishUpdated(String taskId) {
            }
        };
    }
}
