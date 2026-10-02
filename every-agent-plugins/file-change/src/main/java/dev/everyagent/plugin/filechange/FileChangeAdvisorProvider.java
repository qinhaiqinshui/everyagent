package dev.everyagent.plugin.filechange;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.plugin.api.task.RoundClosedInfo;
import dev.everyagent.plugin.api.task.RoundClosedListener;
import dev.everyagent.plugin.api.task.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link FileChangeAdvisor} 适配器 + 轮闭合监听器。
 *
 * <p>每 run 新建 FileChangeAdvisor 实例。advisor 收口时把 collector 暂存到本 provider 的
 * pendingCollectors Map（key=taskId）。当 RoundIndexStore 闭合轮后回调 onRoundsClosed 时，
 * 从 Map 取出 collector，按 roundId 写 file-changes/&lt;roundId&gt;.json。
 */
public class FileChangeAdvisorProvider implements AdvisorProvider, RoundClosedListener {

    private static final Logger log = LoggerFactory.getLogger(FileChangeAdvisorProvider.class);

    private final TaskService taskService;
    private final Map<String, FileChangesCollector> pendingCollectors = new ConcurrentHashMap<>();

    public FileChangeAdvisorProvider(TaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public String pluginId() {
        return "file-change";
    }

    @Override
    public int order() {
        return Ordered.HIGHEST_PRECEDENCE + 301;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentContext a = ctx.agentEntity();
        return new FileChangeAdvisor(a, taskService, this);
    }

    /** FileChangeAdvisor 收口时暂存 collector。 */
    void storePendingCollector(String taskId, FileChangesCollector collector) {
        pendingCollectors.put(taskId, collector);
    }

    @Override
    public void onRoundsClosed(String taskId, Path dataDir, List<RoundClosedInfo> closedRounds) {
        FileChangesCollector collector = pendingCollectors.remove(taskId);
        if (collector == null) {
            log.debug("[file-change] 闭合回调无暂存 collector(本 run 未收口或无文件改动) task={} rounds={}",
                    taskId, closedRounds.stream().map(RoundClosedInfo::roundId).toList());
            return;
        }
        if (collector.isEmpty()) {
            log.debug("[file-change] 闭合回调 collector 为空(本 run 无文件改动) task={} rounds={}",
                    taskId, closedRounds.stream().map(RoundClosedInfo::roundId).toList());
            return;
        }
        for (RoundClosedInfo info : closedRounds) {
            if (info.roundId() == null || info.roundId().isBlank()) {
                continue;
            }
            try {
                Path sub = dataDir.resolve("file-changes");
                Files.createDirectories(sub);
                Path f = sub.resolve(info.roundId() + ".json");
                Files.writeString(f, Json.write(collector.buildContent()), java.nio.charset.StandardCharsets.UTF_8);
                log.debug("[file-change] 写入轮文件变更全文 task={} round={}", taskId, info.roundId());
            } catch (Exception e) {
                log.warn("[file-change] 轮文件变更全文写盘失败 task={} round={}(不影响任务运行)", taskId, info.roundId(), e);
            }
        }
    }
}
