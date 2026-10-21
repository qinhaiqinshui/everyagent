package dev.everyagent.plugin.filechange;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.spi.FileWriteListener;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.task.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文件写一阶段记账监听器(file-change 插件注册的 {@link FileWriteListener},架构 §7.15.2)。
 *
 * <p>worker 文件工具(update_file/create_file)写盘成功后在<b>写锁内</b>回调本监听器,
 * 携带文件级写前/写后<b>全文</b>——同文件并发写按 pathLock 串行,回调序=落盘序,
 * {@link FileChangesCollector} 按「首条 before 全文 + 末条 after 全文 + saveCount」聚合
 * 文件级净状态,diff 不错位。失败调用不回调即天然不记账(原 per-call 链「解析入参当全文」
 * 记账已退役:update_file 的 oldcontent/content 只是被替换的<b>片段</b>,同文件多次写时
 * 聚合出「首条 before=首次入参片段、末条 after=末次入参片段」,两段不相干片段拼 diff,
 * 轮末面板呈错误变更)。
 *
 * <p>Entry.source 按 {@code agentId==mainAgentId} 标注 MAIN/SUB_AGENT,子 agent 的文件
 * 改动不丢失;主体已收口({@code terminal()})不再记账——轮已闭合,记账只会造孤儿
 * collector(宁漏记)。无状态(共享 collector/taskService 均任务键控),多任务并发安全;
 * 回调只做内存聚合,须快进快出(监听器异常由 FsToolSupport 隔离,不影响工具结果)。
 */
public class FileChangeRecorder implements FileWriteListener {

    private static final Logger log = LoggerFactory.getLogger(FileChangeRecorder.class);

    private final TaskService taskService;
    private final FileChangeAdvisorProvider provider;

    public FileChangeRecorder(TaskService taskService, FileChangeAdvisorProvider provider) {
        this.taskService = taskService;
        this.provider = provider;
    }

    @Override
    public void onFileWritten(ExecContext exec, String agentId, String tool, String path,
            String before, String after) {
        String taskId = exec == null ? null : exec.subjectId();
        if (taskId == null || taskId.isEmpty() || agentId == null || agentId.isEmpty()) {
            return; // 无任务上下文/无 agent 归属(裸用/单测):变更无归属,不收集
        }
        if (exec.terminal()) {
            log.debug("[file-change] 主体已收口,丢弃文件写记账 task={} agent={} path={}",
                    taskId, agentId, path);
            return; // 轮已闭合:记账只会造孤儿 collector(宁漏记)
        }
        if (path == null || path.isBlank()) {
            return;
        }
        boolean created = FileWriteListener.TOOL_CREATE_FILE.equals(tool);
        provider.activeCollector(taskId)
                .onFileSaved(agentId, sourceOf(taskId, agentId), path,
                        created ? "" : (before == null ? "" : before),
                        after == null ? "" : after,
                        created ? "created" : "modified");
    }

    /** 来源标注:agentId == 任务 mainAgentId → MAIN,否则 SUB_AGENT(任务查不到视为子,与既往口径一致)。 */
    private FileChangesCollector.Source sourceOf(String taskId, String agentId) {
        TaskRuntime taskRuntime = taskService == null ? null : taskService.get(taskId);
        boolean main = taskRuntime != null && agentId.equals(taskRuntime.mainAgentId());
        return main ? FileChangesCollector.Source.MAIN : FileChangesCollector.Source.SUB_AGENT;
    }
}
