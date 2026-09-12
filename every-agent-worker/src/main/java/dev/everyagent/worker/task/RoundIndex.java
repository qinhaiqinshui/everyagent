package dev.everyagent.worker.task;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 任务轮次索引(rounds.jsonl 内存形)。
 * <p>一轮 = 主 agent 一次用户输入(user.message)到其最终回复(无 toolCalls 且有正文的 message);
 * endSeq 为 null 表示该轮未闭合(运行中/中断/失败/取消)。subs 记录该轮内子 agent 的活动区间,
 * 供前端展开该轮时按 seq 区间精准拉取,避免逐条扫描定位。
 */
public final class RoundIndex {

    private RoundIndex() {
    }

    /** 一轮内的单个子 agent 活动区间(agent.started → agent.done/扫描窗口末尾)。 */
    public record SubRange(String agentId, String title, Long startSeq, Long endSeq) {
        public SubRange {
            agentId = agentId == null ? "" : agentId;
            title = title == null ? "" : title;
        }

        /** 是否已收口(agent.done 已到;endSeq 为 null 即仍在运行,序列化为空串)。 */
        public boolean closed() {
            return endSeq != null;
        }
    }

    /**
     * 一轮索引;finalReply 只存正文,不含 thinking;endSeq 为 null 表示未闭合。
     * startedAt = 本轮开轮落盘时刻(epoch 毫秒,{@code openRoundAtStart} 记录并随行落盘;
     * 0 表示未知——旧行/scan 阶段)。durationMs = 本轮用户任务端到端耗时,不再内存中计算:
     * 闭合轮时经 {@code RoundIndexStore#applyRounds} 取当前时间减去磁盘行的 startedAt 得到
     * (0 表示未记录或 startedAt 未知;未闭合轮恒为 0)。中断/失败后继续时,startedAt 始终是
     * 最初开轮落盘值,跨运行延续,耗时不再随续跑重打点而失真。
     * roundId = 稳定主键(开轮时由 ShortIds 生成,一旦生成不再变;续跑改判闭合沿用磁盘行值;
     * 旧数据/scan 阶段可为 null)。fileChanges = 本轮文件变更<b>轻量摘要数组</b>
     * (仅 filePath/fileName/changeType/saveCount,不含 before/after 全文;无变更时 null;
     * 全文另存 {@code <任务目录>/file-changes/<roundId>.json},经 task.fileChanges RPC 读取)。
     * userMessage = 完整 user.message 事件 payload(开轮路径存当时入队输入 payload;scan 路径存
     * 磁盘事件 payload;可为 null)。前端用它直接构造平铺线程骨架的 user 气泡,不再单独补拉。
     */
    public record Round(String roundId, long index, long startSeq, Long endSeq, String user,
            String finalReply, List<SubRange> subs, long durationMs, long startedAt,
            JsonNode fileChanges, JsonNode userMessage) {
        public Round {
            user = user == null ? "" : user;
            finalReply = finalReply == null ? "" : finalReply;
            subs = subs == null ? List.of() : List.copyOf(subs);
            startedAt = Math.max(0L, startedAt); // 负值归 0(未知)
            // roundId 空串 → null(缺失/旧行);fileChanges 不额外处理(可为 null)
            roundId = (roundId == null || roundId.isEmpty()) ? null : roundId;
        }

        /** 是否已闭合(存在最终回复;endSeq 为 null 即未闭合,序列化为空串)。 */
        public boolean closed() {
            return endSeq != null;
        }
    }
}
