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
 * <p>每 run 新建 FileChangeAdvisor 实例；collector 则由本 provider 维护为<b>任务级共享实例</b>
 * （activeCollectors，key=taskId）——主 agent 与全部子 agent 的 advisor 记录到同一实例，
 * 子 agent 的文件改动不丢失（由主 agent 收口统一暂存）。收口时把共享 collector 暂存到
 * pendingCollectors 并退役活跃实例（下一 run 重新建，切断跨轮串扰）。当 RoundIndexStore
 * 闭合轮后回调 onRoundsClosed 时，从 Map 取出 collector，按 roundId 写
 * file-changes/&lt;roundId&gt;.json，并清理该任务残留的活跃/暂存 collector。
 */
public class FileChangeAdvisorProvider implements AdvisorProvider, RoundClosedListener {

    private static final Logger log = LoggerFactory.getLogger(FileChangeAdvisorProvider.class);

    private final TaskService taskService;
    /** 任务级活跃共享 collector(主/子 agent 同实例;run 收口或轮闭合时退役)。 */
    private final Map<String, FileChangesCollector> activeCollectors = new ConcurrentHashMap<>();
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

    /** 取/建本任务的活跃共享 collector:主 agent 与全部子 agent(经 execution().subjectId())记录到同一实例。 */
    FileChangesCollector activeCollector(String taskId) {
        return activeCollectors.computeIfAbsent(taskId, k -> new FileChangesCollector());
    }

    /**
     * FileChangeAdvisor(主 agent)收口时暂存共享 collector,并退役活跃实例——下一 run 经
     * {@link #activeCollector} 重新建,切断跨轮串扰;暂存的是实例引用,写盘(onRoundsClosed)
     * 时读最新状态,主 agent 流收口后才落定的迟到子 agent 记录不丢。
     */
    void markPending(String taskId, FileChangesCollector collector) {
        pendingCollectors.put(taskId, collector);
        activeCollectors.remove(taskId, collector);
    }

    @Override
    public void onRoundsClosed(String taskId, Path dataDir, List<RoundClosedInfo> closedRounds) {
        FileChangesCollector collector = pendingCollectors.remove(taskId);
        if (collector != null) {
            // 仅退役被收口的同一实例(新一轮 run 可能已抢先建了新 collector)
            activeCollectors.remove(taskId, collector);
        } else {
            // 无暂存(未收口/无改动):清理残留活跃实例,防止跨轮串数据
            activeCollectors.remove(taskId);
        }
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
