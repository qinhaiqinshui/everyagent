package dev.everyagent.plugin.api.task;

import java.nio.file.Path;
import java.util.List;

/**
 * 轮闭合监听器：由 {@code RoundIndexStore} 在持久化新闭合轮后回调。
 *
 * <p>用途：file-change 等插件需要在轮闭合时获取 roundId 以写入按轮分片的数据文件，
 * 但 roundId 由 RoundIndexStore 在闭合时生成，晚于 advisor 的 doOnComplete。
 * 通过此回调机制，插件可以在 roundId 可用后延迟写入。
 */
public interface RoundClosedListener {

    /**
     * 一批轮刚刚闭合并落盘。
     *
     * @param taskId 任务 id
     * @param dataDir 任务数据目录
     * @param closedRounds 新闭合的轮信息列表（可能为空）
     */
    void onRoundsClosed(String taskId, Path dataDir, List<RoundClosedInfo> closedRounds);
}
