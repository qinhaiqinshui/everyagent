package dev.everyagent.worker.task;

import dev.everyagent.worker.proto.Events;
import dev.everyagent.worker.proto.ShortIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 任务轮次索引扫描(用户口径:一轮 = 用户输入 → 直到第一条主 Agent「无工具调用且有正文」的 message)。
 * <p>输入为按 seq 升序合并的全 agent 事件列表(含主/子),输出全部轮次:
 * <ul>
 * <li>轮次起点 = 主 agent(agentId == mainAgentId)的 user.message,<b>仅当前无开着的轮时开轮</b>:
 *     开着的轮内再遇 user.message(授权回复/中断后补充输入等中间输入)<b>不开新轮</b>,作为该轮
 *     过程内容——最终回复闭合的是最初开轮那条输入所在的轮;</li>
 * <li>轮次终点 = 其后第一条主 agent 的 message 且 payload.toolCalls 缺失/空数组、
 *     且 payload.text 非空(即「AI 最终回复」);此后该轮闭合,endSeq = 该事件 seq;</li>
 * <li>若某 user.message 之后直到列表末尾都没有最终回复,该轮「未闭合」:endSeq/finalReply 为空
 *     (该行已在开轮时以 endSeq="" 落盘,续跑补出最终回复后由增量路径原位改写闭合);</li>
 * <li>中间过程事件(delta/thinking/message 带 toolCalls/tool.result/agent.status/task.trace/usage/
 *     ask 系列/error/cancelled 等)不单独成轮、不进轮记录正文,只用于子 agent 区间判断;</li>
 * <li>子 agent 归入「它 started 时所在」的当前轮:agent.started 开始一条 SubRange,
 *     同 id 的 agent.done 关闭;到扫描窗口末尾仍未 done 则 endSeq 为 null;</li>
 * <li>一轮内可有多个子 agent,subs 按出现顺序;尚无主 agent 轮时的子 agent 事件忽略;
 *     agentId 缺失的旧 events.jsonl 行视为主线程(wireEvent 同口径)。</li>
 * </ul>
 */
@Component
public class RoundIndexStore {

    private static final Logger LOG = LoggerFactory.getLogger(RoundIndexStore.class);

    /** 增量闭合的单次扫描窗口上限(记录数;内存日志护栏 50 万,一轮远小于此,超限由 task.rounds 惰性重建兜底)。 */
    private static final int PERSIST_WINDOW_MAX = 20_000;

    /** 扫描窗口内正在构建的轮。 */
    private static final class RoundBuilder {
        final long index;
        final long startSeq;
        /** 完整 user.message payload(scan 时取事件原文;开轮路径走 openRoundAtStart 不建 builder)。 */
        final JsonNode userMessage;
        /** 稳定主键:scan 阶段未知为 null,由 applyRounds 用磁盘 prior 行的 roundId 填充。 */
        String roundId;
        Long endSeq;
        String user;
        String finalReply = "";
        final List<SubBuilder> subs = new ArrayList<>();

        RoundBuilder(long index, long startSeq, String user, JsonNode userMessage) {
            this.index = index;
            this.startSeq = startSeq;
            this.user = user == null ? "" : user;
            this.userMessage = userMessage;
        }

        RoundIndex.Round toRound() {
            // durationMs 由 MeasureDurationAdvisor 在流收口时回填,扫描阶段恒为 0;
            // fileChanges 扫描阶段未知为 null(由 applyRounds 按轻量摘要写入闭合行)。
            return new RoundIndex.Round(roundId, index, startSeq, endSeq, user, finalReply,
                    subs.stream().map(SubBuilder::toSubRange).toList(), 0L, null,
                    userMessage);
        }
    }

    /** 一轮内单个子 agent 区间(未 done 时 endSeq 为 null)。 */
    private static final class SubBuilder {
        final String agentId;
        final String title;
        final Long startSeq;
        Long endSeq;

        SubBuilder(String agentId, String title, Long startSeq) {
            this.agentId = agentId == null ? "" : agentId;
            this.title = title == null ? "" : title;
            this.startSeq = startSeq;
        }

        RoundIndex.SubRange toSubRange() {
            return new RoundIndex.SubRange(agentId, title, startSeq, endSeq);
        }
    }

    /**
     * 扫描全部轮次(输入须已按 seq 升序;不做重排)。
     *
     * @param events      全 agent 事件列表(含主/子,seq 升序)
     * @param mainAgentId 主 agent id(meta.json 的 mainAgentId)
     * @return 轮次列表,index 从 1 递增
     */
    public List<RoundIndex.Round> scan(List<EventRecord> events, String mainAgentId) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<RoundBuilder> builders = new ArrayList<>();
        RoundBuilder current = null;
        // sub agentId → 当前轮内子区间(跨轮保留:done 可能在 started 所在轮之后才到达)
        Map<String, SubBuilder> openSubs = new LinkedHashMap<>();

        for (EventRecord r : events) {
            String event = r.event() == null ? "" : r.event();
            // 归属判定同 wireEvent 口径:agentId 缺失(旧 events.jsonl 行)视为主线程
            String id = r.agentId();
            boolean main = id == null || id.isEmpty() || id.equals(mainAgentId);
            boolean subLifecycle = !main;

            if (main && Events.USER_MESSAGE.equals(event)) {
                if (current == null) {
                    // 仅当前无开着的轮时开轮
                    current = new RoundBuilder(builders.size() + 1L, r.seq(), textOf(r.payload()), r.payload());
                    builders.add(current);
                }
                // else:开着的轮内又来用户输入(中间输入)→ 不开新轮,归入当前轮过程内容
            } else if (main && Events.MESSAGE.equals(event) && current != null && isFinalReply(r.payload())) {
                current.endSeq = r.seq();
                current.finalReply = textOf(r.payload());
                current = null; // 该轮已闭合,后续事件不再进轮
            } else if (subLifecycle && Events.AGENT_STARTED.equals(event)) {
                if (current == null) {
                    continue; // 尚无主 agent 轮:异常,忽略
                }
                SubBuilder sub = new SubBuilder(subAgentId(r), subTitle(r), r.seq());
                current.subs.add(sub);
                openSubs.put(sub.agentId, sub);
            } else if (subLifecycle && Events.AGENT_DONE.equals(event)) {
                SubBuilder sub = openSubs.remove(subAgentId(r));
                if (sub != null) {
                    sub.endSeq = r.seq();
                }
            }
            // 其余过程事件(delta/thinking/带 toolCalls 的 message/tool.result/trace/usage/ask/error 等)
            // 与中间用户输入:不改变轮次结构。
        }
        return builders.stream().map(RoundBuilder::toRound).toList();
    }

    /**
     * 整体重排 index:把每轮 index 加 baseIndex(例如磁盘已有 N 行,新轮从 N+1 起)。
     * 轮内容(roundId/startSeq/endSeq/user/finalReply/subs/fileChanges)原样保留。
     */
    public List<RoundIndex.Round> reindex(List<RoundIndex.Round> rounds, long baseIndex) {
        if (rounds == null || rounds.isEmpty()) {
            return List.of();
        }
        List<RoundIndex.Round> out = new ArrayList<>(rounds.size());
        for (RoundIndex.Round r : rounds) {
            out.add(new RoundIndex.Round(r.roundId(), r.index() + baseIndex, r.startSeq(),
                    r.endSeq(), r.user(), r.finalReply(), r.subs(),
                    r.durationMs(), r.fileChanges(), r.userMessage()));
        }
        return out;
    }

    /**
     * 开轮落盘(用户输入被消费时调用,user.message 落盘后):
     * 读 rounds.jsonl 最后一行——
     * <ul>
     * <li>文件为空或最后一行已闭合 → 追加一条 endSeq="" 的未闭合轮;</li>
     * <li>最后一行未闭合 → 沿用当前轮,不写(中间输入/续跑不开新轮)。</li>
     * </ul>
     * 不扫描事件、不做终态补写/对账:轮行随开轮即持久化,中断/失败/取消的未闭合轮自然留在文件里。
     * 异常全部吞掉(仅记日志),绝不阻断任务输入消费。
     *
     * @return true=真的追加了新行(新开一轮);false=沿用未闭合尾行未写或写盘失败
     */
    public boolean openRoundAtStart(TaskStore store, String taskId, long startSeq, String user,
            JsonNode userMessage) {
        try {
            Path dir = store.dirOf(taskId);
            List<RoundIndex.Round> existing = store.readRounds(dir);
            RoundIndex.Round last = existing.isEmpty() ? null : existing.get(existing.size() - 1);
            if (last != null && !last.closed()) {
                return false; // 沿用当前未闭合轮(roundId 已存在,不重生成)
            }
            long index = last == null ? 1 : last.index() + 1;
            String roundId = ShortIds.next("round");
            store.appendRound(taskId, new RoundIndex.Round(roundId, index, startSeq, null, user, "",
                    List.of(), 0L, null, userMessage));
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("开轮落盘失败 task={}(不影响任务运行)", taskId, e);
            return false;
        }
    }

    /**
     * 增量闭合「已闭合轮」(RoundIndexAdvisor 每轮最终回复后调用):
     * 窗口 = 磁盘持久事件 ∪ 内存尾部按 seq 归并(同 seq 以内存为准),<b>下界含锚点本身</b>
     * (锚点 = rounds.jsonl 最后一行 startSeq;该行未闭合时其开轮 user.message 事件须进窗口,
     * 续跑补出最终回复后开着的轮才能被重扫并闭合——原位改写为闭合行,见 {@link #applyRounds})。
     * 只把开轮路径已落盘的未闭合行改写为闭合行;缺失行不补写(不做自愈/对账)。
     * rounds.jsonl 缺失时另由 {@code task.rounds} 首次惰性全量生成兜底。异常全部吞掉
     * (仅记日志),绝不阻断模型流的 doOnComplete。
     *
     * @param store 落盘组件(rounds.jsonl 读写)
     * @param log   任务内存事件日志(当前 run 的全部事件;冷启动后 EventLog 只含本次运行)
     * @param taskId 任务 id(定位任务目录)
     * @param mainAgentId 主 agent id
     * @param fileChangesLight 本轮文件变更轻量摘要数组(随闭合行内联进 rounds.jsonl;无变更 null)
     * @param fileChangesFull  本轮文件变更全文({changes:[...]};非 null 时对每个新闭合轮写
     *                         {@code file-changes/<roundId>.json},失败仅记日志不阻断)
     * @param durationMs       本轮端到端耗时(RoundIndexAdvisor 从计时槽算得;随闭合行<b>同一次
     *                         落盘内联</b>,保证 round.closed 推送时耗时已在磁盘;≤0 视为未知不写)
     * @return 本次实际「新闭合」的轮(幂等跳过与未闭合沿用不计入;失败为空列表)
     */
    public List<RoundIndex.Round> persistClosedRounds(TaskStore store, EventLog log,
            String taskId, String mainAgentId, JsonNode fileChangesLight, JsonNode fileChangesFull,
            long durationMs) {
        try {
            Path dir = store.dirOf(taskId);
            long anchor = store.lastRoundStartSeq(dir);
            List<EventRecord> window = mergedWindow(store, log, dir, mainAgentId, anchor);
            if (window.isEmpty()) {
                return List.of();
            }
            List<RoundIndex.Round> found = scan(window, mainAgentId);
            if (found.isEmpty()) {
                return List.of();
            }
            List<RoundIndex.Round> newlyClosed =
                    applyRounds(store, taskId, store.readRounds(dir), found, fileChangesLight,
                            durationMs);
            if (fileChangesFull != null) {
                for (RoundIndex.Round r : newlyClosed) {
                    if (r.roundId() != null && !r.roundId().isBlank()) {
                        store.writeRoundFileChanges(taskId, r.roundId(), fileChangesFull);
                    }
                }
            }
            return newlyClosed;
        } catch (IOException | RuntimeException e) {
            LOG.warn("轮次索引增量补写失败 task={}(不影响任务运行)", taskId, e);
            return List.of();
        }
    }

    /**
     * 回填本轮耗时(幂等兜底;主路径已由 {@link #persistClosedRounds} 随闭合行内联):
     * 把 durationMs 写入 rounds.jsonl「最后一条已闭合轮」行——仅当该行尚无耗时(≤0)时生效,
     * 覆盖非流式 call 等不经闭合行内联路径的旁路,以及内联失效(计时槽未打点等)的防御。
     *
     * <p>幂等/防御:文件不存在、无已闭合轮、耗时 ≤ 0、或该轮已有耗时(>0)时均跳过;
     * 每轮只由自身 run 的 advisor 回填一次,不覆盖历史。未闭合轮(endSeq 空、含本轮
     * 正常收口前的中断尾行)不写耗时。写失败只记日志,不阻断 agent 流。
     *
     * @param store      落盘组件(position 目录定位 + rounds 读写)
     * @param taskId     任务 id
     * @param durationMs 本轮端到端耗时(毫秒)
     */
    public void recordDuration(TaskStore store, String taskId, long durationMs) {
        if (durationMs <= 0) {
            return;
        }
        try {
            Path dir = store.dirOf(taskId);
            List<RoundIndex.Round> rounds = store.readRounds(dir);
            if (rounds.isEmpty()) {
                return;
            }
            // 最后一条已闭合轮 = 刚由本 run 收口落盘的行(增量路径只写闭合轮;续跑改判闭合
            // 也保持为末行)。若最后一行是未闭合轮(中断尾行),则往前找最近一条闭合轮。
            RoundIndex.Round target = null;
            for (int i = rounds.size() - 1; i >= 0; i--) {
                RoundIndex.Round r = rounds.get(i);
                if (r.closed()) {
                    target = r;
                    break;
                }
            }
            if (target == null || target.durationMs() > 0) {
                return; // 无闭合轮或已记录过耗时:幂等跳过
            }
            RoundIndex.Round updated = new RoundIndex.Round(target.roundId(), target.index(),
                    target.startSeq(), target.endSeq(), target.user(), target.finalReply(),
                    target.subs(), durationMs, target.fileChanges(),
                    target.userMessage());
            store.rewriteRound(taskId, updated);
        } catch (IOException | RuntimeException e) {
            LOG.warn("轮次耗时回填失败 task={}(不影响任务运行)", taskId, e);
        }
    }

    /**
     * 扫描出的轮与磁盘已有行对账(仅闭合路径使用):
     * <ul>
     * <li>磁盘已存在且已闭合 → 跳过(幂等);</li>
     * <li>磁盘已存在且未闭合(开轮路径已落盘)→ 扫描为闭合轮时<b>原地改写该行</b>为闭合
     *     (endSeq/finalReply/subs 更新,index 沿用原行;续跑补出最终回复即闭合),
     *     扫描仍未闭合则跳过(行已在,不重复);</li>
     * <li>磁盘不存在 → 跳过,不补写(开轮路径负责落盘,不做自愈/对账)。</li>
     * </ul>
     *
     * @return 本次实际<b>新闭合</b>的轮(含 startSeq/endSeq/finalReply,供 round.closed 事件发射;
     *         幂等跳过与双双未闭合不计入)
     */
    private static List<RoundIndex.Round> applyRounds(TaskStore store, String taskId,
            List<RoundIndex.Round> existing, List<RoundIndex.Round> found,
            JsonNode fileChangesLight, long durationMs) throws IOException {
        Map<Long, RoundIndex.Round> byStart = new LinkedHashMap<>();
        for (RoundIndex.Round r : existing) {
            byStart.putIfAbsent(r.startSeq(), r); // 撕行已由 readRounds 过滤,不参与对账
        }
        List<RoundIndex.Round> newlyClosed = new ArrayList<>();
        for (RoundIndex.Round r : found) {
            RoundIndex.Round prior = byStart.get(r.startSeq());
            if (prior == null) {
                continue; // 开轮路径未落盘:不补写
            }
            if (prior.closed()) {
                continue; // 已闭合:幂等跳过
            }
            if (r.closed()) {
                // 未闭合尾行 → 闭合行:原地改写(index 沿用磁盘行;roundId 沿用 prior 的稳定主键,
                // 不新生成;耗时随行内联——prior 已有耗时(>0)不覆盖,未知(≤0)且本轮计时有效
                // 时用本轮 elapsed;fileChanges 写入本轮轻量摘要)
                long dur = prior.durationMs() > 0 ? prior.durationMs() : Math.max(0L, durationMs);
                RoundIndex.Round closed = new RoundIndex.Round(prior.roundId(), prior.index(),
                        r.startSeq(), r.endSeq(), r.user(), r.finalReply(),
                        r.subs(), dur, fileChangesLight,
                        r.userMessage() != null ? r.userMessage() : prior.userMessage());
                if (store.rewriteRound(taskId, closed)) {
                    newlyClosed.add(closed); // 磁盘闭合成功才算「本轮新闭合」(带正确 roundId,供全文落盘)
                }
                byStart.put(r.startSeq(), closed);
            }
            // 双双未闭合:行已存在,不重复写
        }
        return newlyClosed;
    }

    /**
     * 增量窗口:磁盘 readSince ∪ 内存 readAfterSeq 按 seq 归并(同 seq 以内存为准)。
     * 两者均为「seq &gt; 下界」开区间 → 传 anchor-1 得「seq ≥ anchor」<b>含下界</b>窗口
     * (同 rpcTaskPoll 的 readSince(start-1) 惯例):未闭合尾行 startSeq 的开轮 user.message
     * 事件本身要进窗口,开着的轮才能被重扫并闭合。
     */
    private List<EventRecord> mergedWindow(TaskStore store, EventLog log, Path dir,
            String mainAgentId, long anchor) throws IOException {
        Map<Long, EventRecord> merged = new TreeMap<>();
        for (EventRecord r : store.readSince(dir, mainAgentId, anchor - 1, PERSIST_WINDOW_MAX)) {
            merged.put(r.seq(), r);
        }
        for (EventRecord r : log.readAfterSeq(anchor - 1, PERSIST_WINDOW_MAX)) {
            merged.put(r.seq(), r); // 同 seq 以内存为准(未落盘段)
        }
        List<EventRecord> window = new ArrayList<>(merged.values());
        // 同 seq 组按追加序稳定排序后整体按 seq 升序(内存 readAfterSeq 已升序,归并后再稳定排一次)
        window.sort(Comparator.comparingLong(EventRecord::seq));
        return window;
    }
    /** 最终回复判定:主 agent message 且 toolCalls 缺失/空数组 且 text 非空。 */
    private static boolean isFinalReply(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return false;
        }
        JsonNode tc = payload.path("toolCalls");
        boolean noToolCalls = tc.isMissingNode() || tc.isNull() || (tc.isArray() && tc.isEmpty());
        if (!noToolCalls) {
            return false;
        }
        // 与前端 fold 口径一致:content.trim() 非空才算有正文(纯空白不算)。
        JsonNode text = payload.path("text");
        return text.isTextual() && !text.asString().isBlank();
    }

    /** payload.text(缺省空串)。 */
    private static String textOf(JsonNode payload) {
        if (payload == null) {
            return "";
        }
        JsonNode t = payload.path("text");
        return t.isTextual() ? t.asString() : "";
    }

    /** 子 agent id:优先 payload.agentId,缺省取 record.agentId。 */
    private static String subAgentId(EventRecord r) {
        JsonNode p = r.payload();
        if (p != null && p.path("agentId").isTextual()) {
            String fromPayload = p.path("agentId").asString();
            if (!fromPayload.isBlank()) {
                return fromPayload;
            }
        }
        return r.agentId() == null ? "" : r.agentId();
    }

    /** 子 agent 标题:payload.title(缺省空串)。 */
    private static String subTitle(EventRecord r) {
        JsonNode p = r.payload();
        if (p != null && p.path("title").isTextual()) {
            return p.path("title").asString();
        }
        return "";
    }
}
