package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.AtomicFiles;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.proto.Events;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 任务落盘(架构 §5.3 持久化 sink,与 Shipper 同构,fire-and-forget):
 * 单虚拟线程把各任务日志按 record.agentId 路由追加到
 * &lt;data&gt;/tasks/&lt;taskId&gt;/&lt;agentId&gt;.jsonl
 * (行 = {seq,ts,event,agentId,payload[,ext]};ext 为 null 不写字段)。
 * 瞬态流式事件(delta/thinking,主/子同名)不落盘也不写占位行;含瞬态的最高 seq 水位经
 * meta.json 的 seqLast 字段持久化,重启续号从该水位起步;磁盘 lastSeq 可能落后于内存 lastSeq,
 * 读侧按 seq 归并 + 前端 SeqRegressionError 自愈兜底。
 * 任务永久保留:retention 不存在,delete(用户主动)是唯一删除路径。
 * 不做 fsync:进程崩溃至多丢缓冲尾部,meta 仍非终态 → 下次启动标 failed 自愈。
 * 读侧容忍撕行(末行无换行/解析失败即弃)。任务统一存 data/tasks/&lt;taskId&gt;/，不做旧布局迁移。
 */
@Component
public class TaskStore {

    /** 磁盘上的一个任务目录(scan/恢复/索引的单位)。 */
    public record StoredTask(String taskId, Path dir, ObjectNode summary) {
    }

    /** flush(taskId) 最长等待(超时放行,meta 仍非终态 → 重启自愈)。 */
    private static final long FLUSH_TIMEOUT_MS = 30_000;
    private static final int DRAIN_BATCH = 1000;
    /** 瞬态事件(只发前端不落盘;占 seq → 磁盘 seq 有洞;主/子同名,按名跳过即可)。 */
    private static final Set<String> PERSIST_SKIP = Set.of(
            Events.DELTA, Events.THINKING);
    /** 事件帧 ext 标记:task.trace 的瞬态实例(如每秒倒计时的重试进度)由该标记识别,不落盘。 */
    private static final String EXT_PERSIST = "persist";
    /** agentId 缺失时的兜底文件名(旧运行时数据防御;新数据恒非空)。 */
    private static final String FALLBACK_AGENT_FILE = "main";

    private static final Logger log = LoggerFactory.getLogger(TaskStore.class);

    private static final class Tracked {
        final String taskId;
        final EventLog log;
        final Supplier<ObjectNode> meta;
        final Path dir;
        /** 按 agent 懒开的追加 writer;agentId 缺失兜底 main。 */
        final Map<String, BufferedWriter> writers = new ConcurrentHashMap<>();
        /**
         * 已落盘处理的<b>记录数</b>(追加位置,非 seq):同轮 delta/thinking/message 共享同一
         * seq,seq 游标会把同组后续帧(如 message,seq 与 delta 相同)过滤掉,须按记录数推进;
         * sink 线程写,flush 跨线程轮询,须 volatile。
         */
        volatile long cursor;

        Tracked(String taskId, EventLog log, Supplier<ObjectNode> meta, Path dir) {
            this.taskId = taskId;
            this.log = log;
            this.meta = meta;
            this.dir = dir;
        }
    }

    private final WorkerProperties props;
    private final Map<String, Tracked> tracked = new ConcurrentHashMap<>();
    private final Semaphore wake = new Semaphore(0);
    private volatile boolean running = true;
    private Thread sinkThread;
    /** rounds.jsonl 全部写入路径(追加/改写)共用的进程内锁:advisor 线程与 finish 线程可能并发。 */
    private final Object roundsLock = new Object();

    public TaskStore(WorkerProperties props) {
        this.props = props;
    }

    @PostConstruct
    void start() {
        sinkThread = Thread.ofVirtual().name("task-store").start(this::sinkLoop);
    }

    @PreDestroy
    void stop() throws InterruptedException {
        running = false;
        wake.release();
        if (sinkThread != null) {
            sinkThread.join(5_000);
        }
        for (Tracked t : tracked.values()) {
            closeQuietly(t);
        }
    }

    // ---- 写路径 ----

    /** 开始落盘一个任务:建目录、写初始 meta、挂日志监听(writer 按 agent 懒开)。 */
    public synchronized void track(String taskId, EventLog log,
            Supplier<ObjectNode> meta) throws IOException {
        if (tracked.containsKey(taskId)) {
            return;
        }
        Path dir = dirOf(taskId);
        Files.createDirectories(dir);
        writeMeta(dir, meta.get());
        Tracked t = new Tracked(taskId, log, meta, dir);
        tracked.put(taskId, t);
        log.addListener(() -> wake.release());
        wake.release();
    }

    /** 终态时刷新 meta(status/error/endedAt 以内存为准;调用前须先 flush)。 */
    public void updateMeta(String taskId) {
        Tracked t = tracked.get(taskId);
        if (t == null) {
            return;
        }
        try {
            // 落盘前把含瞬态事件的最高水位写入 meta,重启续号以此为起点(t.log.lastSeq()
            // 含瞬态水位;调用方保证 flush 后调用,此时内存水位已覆盖全部已发事件)。
            ObjectNode summary = t.meta.get();
            summary.put("seqLast", t.log.lastSeq());
            writeMeta(t.dir, summary);
        } catch (IOException e) {
            log.warn("meta 写入失败 task={}", taskId, e);
        }
    }

    /** 阻塞直到该任务当前记录数已全部处理(finish 在驱逐前调用);未 track/超时即返回。 */
    public void flush(String taskId) throws InterruptedException {
        Tracked t = tracked.get(taskId);
        if (t == null) {
            return;
        }
        long target = t.log.size(); // 游标 = 已处理记录数(共享 seq 轮组按位置无歧义)
        long deadline = System.currentTimeMillis() + FLUSH_TIMEOUT_MS;
        while (t.cursor < target && running) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("flush 超时 task={} cursor={} target={}", taskId, t.cursor, target);
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    /** 停止跟踪(关全部 writer;目录保留——任务永久)。 */
    public void untrack(String taskId) {
        Tracked t = tracked.remove(taskId);
        if (t != null) {
            closeQuietly(t);
        }
    }

    /** 用户主动删除任务(task.delete RPC;运行中先由调用方拒绝)。 */
    public void delete(Path dir) {
        try {
            deleteRecursively(dir);
        } catch (IOException e) {
            log.warn("任务目录删除失败 {}", dir, e);
        }
    }

    // ---- 读路径(冷数据)----

    /** 扫描 data/tasks/ 下全部任务目录(meta.json 存在即算)。 */
    public List<StoredTask> scan() {
        List<StoredTask> out = new ArrayList<>();
        Path tasksRoot = props.resolveDataDir().resolve("tasks");
        if (!Files.isDirectory(tasksRoot)) {
            return out;
        }
        try (DirectoryStream<Path> taskDirs = Files.newDirectoryStream(tasksRoot)) {
            for (Path taskDir : taskDirs) {
                if (!Files.isDirectory(taskDir)) {
                    continue;
                }
                Path meta = taskDir.resolve("meta.json");
                if (!Files.isRegularFile(meta)) {
                    continue;
                }
                try {
                    JsonNode s = Json.parse(Files.readString(meta));
                    if (s.isObject()) {
                        out.add(new StoredTask(taskDir.getFileName().toString(),
                                taskDir, (ObjectNode) s));
                    }
                } catch (IOException | RuntimeException e) {
                    log.warn("meta 读取失败 {}", meta, e);
                }
            }
        } catch (IOException e) {
            log.warn("任务目录扫描失败 {}", tasksRoot, e);
        }
        return out;
    }

    /** 读 meta.json(重启改写后回读)。 */
    public ObjectNode readMeta(Path dir) {
        try {
            JsonNode n = Json.parse(Files.readString(dir.resolve("meta.json")));
            return n.isObject() ? (ObjectNode) n : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 从磁盘读 seq &gt; afterSeq 的事件(wire 形,与 task.sync 批次一致):
     * 全部 *.jsonl 按 seq 归并(瞬态占 seq → 文件内有洞;agent 数 ≤ 个位数,线性归并够用)。
     * 旧 events.jsonl 天然命中同一 glob(旧行无 agentId → 主线程)。撕行静默跳过。
     */
    public List<ObjectNode> readEvents(Path dir, String mainAgentId, long afterSeq, int max)
            throws IOException {
        record Entry(long seq, ObjectNode wire) {
        }
        List<Entry> all = new ArrayList<>();
        for (Path f : agentFiles(dir)) {
            try (BufferedReader br = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.length() < 30) {
                        continue; // 快速跳过明显残行
                    }
                    long seq = seqOf(line);
                    if (seq <= 0) {
                        continue; // 无 seq 前缀:不整行解析直接弃(撕行/异构行)
                    }
                    if (seq <= afterSeq) {
                        continue;
                    }
                    EventRecord r = parseLine(line);
                    if (r == null) {
                        continue; // 撕行
                    }
                    all.add(new Entry(r.seq(), TaskEvents.wireEvent(r, mainAgentId)));
                }
            }
        }
        all.sort(Comparator.comparingLong(Entry::seq));
        List<ObjectNode> out = new ArrayList<>(Math.min(all.size(), max));
        for (Entry e : all) {
            if (out.size() >= max) {
                break;
            }
            out.add(e.wire);
        }
        return out;
    }

    // ---- 高效读路径(反向随机访问分块扫描;只加能力,不动既有 readEvents/推流链路)----

    /**
     * 增量读:seq &gt; afterSeq 的持久事件(升序,最多 limit 条)。每个 *.jsonl 从文件尾反向,
     * 遇 seq &lt;= afterSeq(文件内 seq 单调)立即停止、够 limit 也停;各文件窗口归并后
     * 按 seq 升序取前 limit(增量 = afterSeq 之后最早的一条,轮询/续播语义)。
     */
    public List<EventRecord> readSince(Path dir, String mainAgentId, long afterSeq, int limit)
            throws IOException {
        List<List<EventRecord>> windows = new ArrayList<>();
        for (Path f : agentFiles(dir)) {
            List<EventRecord> window = new ArrayList<>();
            try (ReverseLineReader r = new ReverseLineReader(f)) {
                String line;
                while (window.size() < limit && (line = r.nextLine()) != null) {
                    long s = seqOf(line);
                    if (s <= 0) {
                        continue; // 撕行/残行:跳过继续
                    }
                    if (s <= afterSeq) {
                        break; // 文件内 seq 单调递减,再往前只会更小 → 整体停止
                    }
                    EventRecord rec = parseLine(line);
                    if (rec != null) {
                        window.add(rec);
                    }
                }
            }
            Collections.reverse(window); // 反向收集(降序)→ 文件内升序
            windows.add(window);
        }
        return mergeWindows(windows, limit, false);
    }

    /**
     * 向前翻页:seq &lt; beforeSeq 的持久事件(升序,最多 limit 条)。反向扫描收集到 limit
     * 或到文件头即停(碰到 seq &gt;= beforeSeq 的行跳过继续,不做谓词停止);各文件窗口归并后
     * 按 seq 升序取末尾 limit 条(beforeSeq 之前最近的一批,上滚分页语义)。
     */
    public List<EventRecord> readBefore(Path dir, String mainAgentId, long beforeSeq, int limit)
            throws IOException {
        List<List<EventRecord>> windows = new ArrayList<>();
        for (Path f : agentFiles(dir)) {
            List<EventRecord> window = new ArrayList<>();
            try (ReverseLineReader r = new ReverseLineReader(f)) {
                String line;
                while (window.size() < limit && (line = r.nextLine()) != null) {
                    long s = seqOf(line);
                    if (s <= 0) {
                        continue; // 撕行跳过(不计数)
                    }
                    if (s >= beforeSeq) {
                        continue; // 更靠尾的 seq 更大:继续向前
                    }
                    EventRecord rec = parseLine(line);
                    if (rec != null) {
                        window.add(rec);
                    }
                }
            }
            Collections.reverse(window);
            windows.add(window);
        }
        return mergeWindows(windows, limit, true);
    }

    /**
     * 尾段:每个 *.jsonl 末尾 limit 条持久事件(按<b>物理行数</b>计 limit——撕行也算一次
     * "遇到行",避免某文件撕行过多导致无限读);各文件窗口归并后按 seq 升序取末尾 limit 条。
     */
    public List<EventRecord> readTail(Path dir, String mainAgentId, int limit) throws IOException {
        List<List<EventRecord>> windows = new ArrayList<>();
        for (Path f : agentFiles(dir)) {
            List<EventRecord> window = new ArrayList<>();
            int seen = 0;
            try (ReverseLineReader r = new ReverseLineReader(f)) {
                String line;
                while (seen < limit && (line = r.nextLine()) != null) {
                    seen++; // 物理行计数(含撕行)
                    EventRecord rec = parseLine(line);
                    if (rec != null) {
                        window.add(rec);
                    }
                }
            }
            Collections.reverse(window);
            windows.add(window);
        }
        return mergeWindows(windows, limit, true);
    }

    /**
     * 轮次定位:主 agent 文件反向找第 n 个 event=="user.message" 的 seq;不足 n 返回 1
     * (整文件即"最近 1 轮以内"或空任务)。主文件缺失时回退旧 events.jsonl(旧行无 agentId → 主线程)。
     */
    public long roundStartSeq(Path dir, String mainAgentId, int n) {
        if (n <= 0) {
            return 1;
        }
        String name = (mainAgentId == null || mainAgentId.isEmpty()) ? "events" : mainAgentId;
        Path f = dir.resolve(name + ".jsonl");
        if (!Files.isRegularFile(f)) {
            f = dir.resolve("events.jsonl");
        }
        int count = 0;
        try (ReverseLineReader r = new ReverseLineReader(f)) {
            String line;
            while ((line = r.nextLine()) != null) {
                EventRecord rec = parseLine(line);
                if (rec == null) {
                    continue; // 撕行
                }
                if (!"user.message".equals(rec.event())) {
                    continue;
                }
                if (++count == n) {
                    return rec.seq();
                }
            }
        } catch (IOException e) {
            log.warn("轮次定位读取失败 {}", f, e);
        }
        return 1;
    }

    /** 各文件窗口按 seq 升序归并并截断:takeTail=false 取前 limit(增量),true 取末 limit(翻页/尾段)。 */
    private static List<EventRecord> mergeWindows(List<List<EventRecord>> windows, int limit,
            boolean takeTail) {
        List<EventRecord> all = new ArrayList<>();
        for (List<EventRecord> w : windows) {
            all.addAll(w);
        }
        all.sort(Comparator.comparingLong(EventRecord::seq));
        if (all.size() <= limit) {
            return all;
        }
        return takeTail
                ? new ArrayList<>(all.subList(all.size() - limit, all.size()))
                : new ArrayList<>(all.subList(0, limit));
    }

    /** 单 agent 的原始事件(ConversationLoader 重建上下文用;按 seq 升序)。 */
    public List<EventRecord> readAgentEvents(Path dir, String agentId, long afterSeq, int max)
            throws IOException {
        List<EventRecord> out = new ArrayList<>();
        Path f = dir.resolve(agentId + ".jsonl");
        if (!Files.isRegularFile(f)) {
            return out;
        }
        try (BufferedReader br = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String line;
            while (out.size() < max && (line = br.readLine()) != null) {
                EventRecord r = parseLine(line);
                if (r == null || r.seq() <= afterSeq) {
                    continue;
                }
                out.add(r);
            }
        }
        return out;
    }

    /** 目录下全部 jsonl 文件(按文件名排序保证稳定序;含旧 events.jsonl)。 */
    private static List<Path> agentFiles(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jsonl")) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) {
                    files.add(p);
                }
            }
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return files;
    }

    /**
     * 磁盘最后一条事件 seq(全部 jsonl 取最大;无文件/全撕行 = 0)。
     * 撕行只可能出现在末行(追加写半行即崩溃):前缀行走 seq 前缀快速提取,
     * 各文件末行整行解析校验,未过校验不计入——否则续号会产生 seq 洞。
     */
    public long diskLastSeq(Path dir) {
        long last = 0;
        try {
            for (Path f : agentFiles(dir)) {
                last = Math.max(last, diskLastSeqOfFile(f));
            }
        } catch (IOException e) {
            log.warn("任务目录读取失败 {}", dir, e);
        }
        return last;
    }

    /**
     * 重启续号水位:meta.json 的 seqLast(含瞬态事件的最高水位,由 updateMeta 在终态写入)
     * 与磁盘 lastSeq 取 max。meta 缺失/损坏/旧 meta 无 seqLast 字段时退化为 diskLastSeq
     * (兼容旧数据;取 max 也防 meta 落后于磁盘的竞态)。
     */
    public long seqLastOf(Path dir) {
        ObjectNode meta = readMeta(dir);
        if (meta != null && meta.path("seqLast").isNumber()) {
            return Math.max(diskLastSeq(dir), meta.path("seqLast").asLong());
        }
        return diskLastSeq(dir);
    }

    private static long diskLastSeqOfFile(Path f) {
        long last = 0;
        try (BufferedReader br = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String line;
            String prev = null;
            while ((line = br.readLine()) != null) {
                if (prev != null) {
                    long s = seqOf(prev);
                    if (s > 0) {
                        last = s;
                    }
                }
                prev = line;
            }
            if (prev != null) {
                EventRecord r = parseLine(prev);
                if (r != null && r.seq() > last) {
                    last = r.seq();
                }
            }
        } catch (IOException e) {
            log.warn("jsonl 读取失败 {}", f, e);
        }
        return last;
    }

    /**
     * 重启恢复:非终态任务追加 error 事件(seq=磁盘 last+1)并把 meta 改写为 failed。
     * 追加目标:meta.mainAgentId 对应文件(旧格式无该字段 → 旧 events.jsonl)。
     */
    public ObjectNode markRestartFailed(StoredTask st, String message) throws IOException {
        String mainAgentId = st.summary().path("mainAgentId").asString(null);
        Path target = st.dir().resolve(
                (mainAgentId == null || mainAgentId.isEmpty() ? "events" : mainAgentId) + ".jsonl");
        long next = seqLastOf(st.dir()) + 1;
        ObjectNode line = Json.obj()
                .put("seq", next)
                .put("ts", System.currentTimeMillis())
                .put("event", "error");
        if (mainAgentId != null && !mainAgentId.isEmpty()) {
            line.put("agentId", mainAgentId);
        }
        line.set("payload", Json.obj().put("message", message));
        try (BufferedWriter w = Files.newBufferedWriter(target,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(Json.write(line));
            w.write('\n');
        }
        ObjectNode summary = st.summary().deepCopy();
        summary.put("status", "failed");
        summary.put("error", message);
        summary.put("endedAt", System.currentTimeMillis());
        summary.put("seqLast", next);
        writeMeta(st.dir(), summary);
        return summary;
    }

    public Path dirOf(String taskId) {
        return props.resolveDataDir().resolve("tasks").resolve(taskId);
    }

    // ---- 轮次索引 rounds.jsonl(与 meta.json、<agentId>.jsonl 同级;seq 一律字符串防 JS 精度)----
    // 每行一轮:{index,startSeq,endSeq,user,finalReply,subs:[{agentId,title,startSeq,endSeq}],userMessage?};
    // endSeq 为 "" 表示未闭合;
    // userMessage = 完整 user.message payload(懒加载骨架起点;旧行缺失不写);
    // append 单行(无 fsync,崩溃丢尾部由重启标 failed 自愈);读侧容忍撕行(末行半行/解析失败即弃);
    // 「闭合磁盘上已有的未闭合轮」(续跑改判闭合)走 rewriteRound 原位替换单行(append-only 改不了行)。

    /** 原子追加一轮:在任务目录 rounds.jsonl 尾部 append 一行(文件/目录不存在时创建)。 */
    public void appendRound(String taskId, RoundIndex.Round round) throws IOException {
        Path dir = dirOf(taskId);
        Files.createDirectories(dir);
        Path f = dir.resolve("rounds.jsonl");
        synchronized (roundsLock) { // 与 rewriteRound 同锁串行:advisor 线程与 finish 线程可能并发写
            try (BufferedWriter w = Files.newBufferedWriter(f, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(roundLine(round));
                w.write('\n');
                w.flush();
            }
        }
    }

    /** 读 rounds.jsonl 全部行;文件不存在返回空列表;撕行/坏行静默跳过。 */
    public List<RoundIndex.Round> readRounds(Path dir) {
        Path f = dir.resolve("rounds.jsonl");
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        List<RoundIndex.Round> out = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue; // 空行快速跳过
                }
                RoundIndex.Round round = parseRoundLine(line);
                if (round != null) {
                    out.add(round);
                }
            }
        } catch (IOException e) {
            log.warn("rounds 读取失败 {}", f, e);
        }
        return out;
    }

    /**
     * 原地改写一轮(未闭合行 → 闭合行,续跑改判闭合):读全部行 → 替换 startSeq 匹配的行 →
     * 写临时文件 → {@link AtomicFiles#replace 原子替换}(优先 ATOMIC_MOVE,文件系统不支持降级
     * 为直接替换;Windows 短暂锁(如防御扫描)走短退避重试,最终失败清理残留 tmp 后抛出)。
     * 与 {@link #appendRound} 共用同一把进程内锁 {@code roundsLock} 串行
     * (advisor 线程与 finish 线程可能并发);撕行原样保留(不可解析的行不参与匹配)。
     *
     * @param round 改写后的轮(index 建议沿用磁盘原行,由调用方对账保证)
     * @return 是否找到并改写了目标行(false = 无 startSeq 匹配行,文件未动)
     */
    public boolean rewriteRound(String taskId, RoundIndex.Round round) throws IOException {
        Path dir = dirOf(taskId);
        Files.createDirectories(dir);
        Path f = dir.resolve("rounds.jsonl");
        synchronized (roundsLock) {
            List<String> lines = Files.isRegularFile(f)
                    ? Files.readAllLines(f, StandardCharsets.UTF_8)
                    : List.of();
            StringBuilder sb = new StringBuilder();
            boolean replaced = false;
            for (String line : lines) {
                if (!replaced && !line.isBlank()) {
                    RoundIndex.Round cur = parseRoundLine(line);
                    if (cur != null && cur.startSeq() == round.startSeq()) {
                        sb.append(roundLine(round)).append('\n');
                        replaced = true;
                        continue;
                    }
                }
                sb.append(line).append('\n'); // 未涉及行(含撕行)原样保留
            }
            if (!replaced) {
                return false;
            }
            Path tmp = dir.resolve("rounds.jsonl.tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            AtomicFiles.replace(tmp, f); // 原子替换(失败已清理 tmp 后抛出,不残留垃圾)
            return true;
        }
    }

    /**
     * rounds.jsonl 最后一行 startSeq(用于增量补写/续号的定位)。
     * 文件不存在/空/最后一行解析失败返回 0。
     */
    public long lastRoundStartSeq(Path dir) {
        Path f = dir.resolve("rounds.jsonl");
        if (!Files.isRegularFile(f)) {
            return 0;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("rounds 尾行读取失败 {}", f, e);
            return 0;
        }
        if (lines.isEmpty()) {
            return 0;
        }
        String last = lines.get(lines.size() - 1);
        if (last == null || last.isBlank()) {
            return 0;
        }
        try {
            JsonNode n = Json.parse(last);
            if (!n.isObject()) {
                return 0;
            }
            long v = parseSeqField(n, "startSeq");
            return v > 0 ? v : 0;
        } catch (RuntimeException e) {
            return 0; // 撕行:静默跳过(与 readRounds 同口径)
        }
    }

    /**
     * 写本轮文件变更全文:<任务目录>/file-changes/<roundId>.json(内容即 fullContent,UTF-8)。
     * 失败只记日志不抛(与 rounds 写盘同风格),绝不阻塞任务流。
     */
    public void writeRoundFileChanges(String taskId, String roundId, JsonNode fullContent) {
        if (roundId == null || roundId.isBlank() || fullContent == null) {
            return;
        }
        try {
            Path dir = dirOf(taskId);
            Path sub = dir.resolve("file-changes");
            Files.createDirectories(sub);
            Path f = sub.resolve(roundId + ".json");
            Files.writeString(f, Json.write(fullContent), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("轮次文件变更全文写盘失败 task={} round={}(不影响任务运行)", taskId, roundId, e);
        }
    }

    /**
     * 读本轮文件变更全文(file-changes/<roundId>.json):文件不存在返回 null;解析失败返回 null 并 warn。
     */
    public JsonNode readRoundFileChanges(String taskId, String roundId) {
        if (roundId == null || roundId.isBlank()) {
            return null;
        }
        Path f = dirOf(taskId).resolve("file-changes").resolve(roundId + ".json");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            return Json.parse(Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            log.warn("轮次文件变更全文读取失败 task={} round={}", taskId, roundId, e);
            return null;
        }
    }

    /** 一行 Round → jsonl 行(seq 全字符串;endSeq null → "";subs 恒为数组)。 */
    private static String roundLine(RoundIndex.Round round) {
        ObjectNode line = Json.obj()
                .put("index", round.index())
                .put("startSeq", String.valueOf(round.startSeq()));
        line.put("endSeq", round.endSeq() == null ? "" : String.valueOf(round.endSeq()));
        line.put("user", safeText(round.user()));
        line.put("finalReply", safeText(round.finalReply()));
        line.put("durationMs", round.durationMs());
        if (round.roundId() != null && !round.roundId().isBlank()) {
            line.put("roundId", round.roundId()); // roundId 稳定主键:缺失(旧行)不写
        }
        if (round.fileChanges() != null) {
            line.set("fileChanges", round.fileChanges()); // 本轮文件变更轻量摘要:无变更不写
        }
        if (round.userMessage() != null) {
            line.set("userMessage", round.userMessage()); // 完整 user.message payload(懒加载骨架起点;旧行缺失=null)
        }
        ArrayNode subs = Json.arr();
        for (RoundIndex.SubRange sub : round.subs()) {
            ObjectNode s = Json.obj()
                    .put("agentId", safeText(sub.agentId()))
                    .put("title", safeText(sub.title()))
                    .put("startSeq", sub.startSeq() == null ? "" : String.valueOf(sub.startSeq()));
            s.put("endSeq", sub.endSeq() == null ? "" : String.valueOf(sub.endSeq()));
            subs.add(s);
        }
        line.set("subs", subs);
        return Json.write(line);
    }

    /** 一行 jsonl → Round;解析失败返回 null(撕行/坏行)。 */
    private static RoundIndex.Round parseRoundLine(String line) {
        try {
            JsonNode n = Json.parse(line);
            if (!n.isObject()) {
                return null;
            }
            long index = n.path("index").asLong(Long.MIN_VALUE);
            Long startSeq = parseSeqFieldOrNull(n, "startSeq");
            if (index == Long.MIN_VALUE || startSeq == null) {
                return null;
            }
            Long endSeq = parseSeqFieldOrNull(n, "endSeq"); // ""/缺失 → null(未闭合)
            String user = n.path("user").asString("");
            String finalReply = n.path("finalReply").asString("");
            long durationMs = n.path("durationMs").asLong(0); // 旧行缺失 → 0(未记录耗时)
            String roundId = n.path("roundId").asString(null); // 旧行缺失 → null
            JsonNode fileChanges = n.path("fileChanges"); // 缺失/null → null;存在则按 JsonNode 原样读入
            if (fileChanges.isMissingNode() || fileChanges.isNull()) {
                fileChanges = null;
            }
            JsonNode userMessage = n.path("userMessage"); // 缺失/null → null(旧行);存在则按 JsonNode 原样读入
            if (userMessage.isMissingNode() || userMessage.isNull()) {
                userMessage = null;
            }
            List<RoundIndex.SubRange> subs = new ArrayList<>();
            JsonNode subsNode = n.path("subs");
            if (subsNode.isArray()) {
                for (JsonNode sn : subsNode) {
                    Long subStart = parseSeqFieldOrNull(sn, "startSeq");
                    if (subStart == null) {
                        return null; // 子区间关键字段缺失:整行弃
                    }
                    Long subEnd = parseSeqFieldOrNull(sn, "endSeq");
                    subs.add(new RoundIndex.SubRange(sn.path("agentId").asString(""),
                            sn.path("title").asString(""), subStart, subEnd));
                }
            }
            return new RoundIndex.Round(roundId, index, startSeq, endSeq, user, finalReply,
                    subs, durationMs, fileChanges, userMessage);
        } catch (RuntimeException e) {
            return null; // 撕行
        }
    }

    /** 字段缺失/非文本/空串/解析失败 → null;否则返回其 long 值。 */
    private static Long parseSeqFieldOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.isTextual() ? v.asString() : v.asText();
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** parseSeqFieldOrNull 的长整型便捷版(失败 → 0)。 */
    private static long parseSeqField(JsonNode node, String field) {
        Long v = parseSeqFieldOrNull(node, field);
        return v == null ? 0L : v;
    }

    private static String safeText(String s) {
        return s == null ? "" : s;
    }

    // ---- 悬空队列落盘(queue.jsonl:每行一个 JSON 对象 {"text": "..."})----
    // 供后续步骤在任务终态时把未消费输入落盘、重启/续跑时恢复、删除/移动时整读整写。
    // 行内文本可能含任意用户内容,统一经 JSON 转义;读侧容忍撕行(末行半行/解析失败即弃)。

    /** 整读 queue.jsonl(按行序返回 UserInput 列表);文件不存在/读取失败返回空列表。 */
    public List<UserInput> readQueue(Path dir) {
        Path f = dir.resolve("queue.jsonl");
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        List<UserInput> out = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue; // 空行/残行快速跳过
                }
                try {
                    JsonNode n = Json.parse(line);
                    if (n.isObject() && n.path("text").isTextual()) {
                        String text = n.path("text").asString();
                        String rawContent = n.path("rawContent").isTextual()
                                ? n.path("rawContent").asString()
                                : null;
                        out.add(UserInput.of(text, rawContent));
                    }
                } catch (RuntimeException e) {
                    // 撕行:静默跳过
                }
            }
        } catch (IOException e) {
            log.warn("队列读取失败 {}", f, e);
        }
        return out;
    }

    /** 整写 queue.jsonl(临时文件 + ATOMIC_MOVE,同 writeMeta 惯例);空列表也覆盖写空文件。 */
    public void writeQueue(Path dir, List<UserInput> items) throws IOException {
        Path f = dir.resolve("queue.jsonl");
        Path tmp = dir.resolve("queue.jsonl.tmp");
        StringBuilder sb = new StringBuilder();
        for (UserInput item : items) {
            ObjectNode line = Json.obj().put("text", item.text());
            if (item.rawContent() != null && !item.rawContent().isEmpty()) {
                line.put("rawContent", item.rawContent());
            }
            sb.append(Json.write(line)).append('\n');
        }
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        AtomicFiles.replace(tmp, f); // 原子替换(失败已清理 tmp 后抛出,不残留垃圾)
    }

    /** 删除 queue.jsonl(不存在则忽略;其他 IO 异常记日志)。 */
    public void deleteQueue(Path dir) {
        try {
            Files.deleteIfExists(dir.resolve("queue.jsonl"));
        } catch (IOException e) {
            log.warn("队列删除失败 {}", dir.resolve("queue.jsonl"), e);
        }
    }

    // ---- 内部:sink ----

    private void sinkLoop() {
        while (running) {
            try {
                wake.tryAcquire(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running) {
                break;
            }
            try {
                drainAll();
            } catch (RuntimeException e) {
                log.error("任务落盘异常", e);
            }
        }
        // 停机收尾:尽力把余量刷下去(stop 侧关 writer)
        try {
            drainAll();
        } catch (RuntimeException e) {
            log.warn("停机落盘收尾异常", e);
        }
    }

    private void drainAll() {
        for (Tracked t : tracked.values()) {
            try {
                drain(t);
            } catch (IOException e) {
                log.error("任务写盘失败 task={} dir={}", t.taskId, t.dir, e);
            }
        }
    }

    private void drain(Tracked t) throws IOException {
        boolean wrote = false;
        while (true) {
            List<EventRecord> batch = t.log.readFrom((int) t.cursor, DRAIN_BATCH);
            if (batch.isEmpty()) {
                break;
            }
            for (EventRecord r : batch) {
                if (PERSIST_SKIP.contains(r.event()) || isTransientExt(r)) {
                    // 瞬态事件不落盘也不写占位行,只推进游标:含瞬态的最高 seq 水位由
                    // updateMeta 持久化到 meta.json 的 seqLast,重启续号从该水位起步;
                    // 磁盘 lastSeq 可能落后于内存 lastSeq,读侧按 seq 归并 + 前端
                    // SeqRegressionError 自愈兜底。
                    t.cursor++;
                    continue;
                }
                BufferedWriter w = writerFor(t, r.agentId());
                w.write(persistLine(r));
                w.write('\n');
                t.cursor++;
                wrote = true;
            }
            if (batch.size() < DRAIN_BATCH) {
                break;
            }
        }
        if (wrote) {
            for (BufferedWriter w : t.writers.values()) {
                w.flush(); // 无 fsync:崩溃丢尾部由重启标 failed 自愈
            }
        }
    }

    private BufferedWriter writerFor(Tracked t, String agentId) throws IOException {
        String key = agentId == null || agentId.isEmpty() ? FALLBACK_AGENT_FILE : agentId;
        BufferedWriter w = t.writers.get(key);
        if (w != null) {
            return w;
        }
        synchronized (t) {
            w = t.writers.get(key);
            if (w == null) {
                w = Files.newBufferedWriter(t.dir.resolve(key + ".jsonl"), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                t.writers.put(key, w);
            }
            return w;
        }
    }

    /** ext 携带 persist=false 的事件为瞬态(task.trace 的倒计时等),不落盘。 */
    private static boolean isTransientExt(EventRecord r) {
        JsonNode ext = r.ext();
        return ext != null && ext.isObject()
                && ext.path(EXT_PERSIST).isBoolean() && !ext.path(EXT_PERSIST).asBoolean();
    }

    /** 手写行:ext 仅非 null 时写字段(EventRecord 原形序列化会带 null)。 */
    private static String persistLine(EventRecord r) {
        ObjectNode line = Json.obj()
                .put("seq", r.seq())
                .put("ts", r.ts())
                .put("event", r.event())
                .put("agentId", r.agentId() == null ? FALLBACK_AGENT_FILE : r.agentId());
        line.set("payload", r.payload() == null ? Json.obj() : r.payload());
        if (r.ext() != null) {
            line.set("ext", r.ext());
        }
        return Json.write(line);
    }

    private void closeQuietly(Tracked t) {
        for (BufferedWriter w : t.writers.values()) {
            try {
                w.flush();
                w.close();
            } catch (IOException e) {
                log.warn("jsonl 关闭失败 task={}", t.taskId, e);
            }
        }
        t.writers.clear();
    }

    /** 原子写 meta(临时文件 + ATOMIC_MOVE)。公开:slash 层在终态任务(未运行)路径改写磁盘 meta.json。 */
    public static void writeMeta(Path dir, ObjectNode summary) throws IOException {
        Path f = dir.resolve("meta.json");
        Path tmp = dir.resolve("meta.json.tmp");
        Files.writeString(tmp, Json.write(summary));
        AtomicFiles.replace(tmp, f); // 原子替换(失败已清理 tmp 后抛出,不残留垃圾)
    }

    private static EventRecord parseLine(String line) {
        try {
            JsonNode n = Json.parse(line);
            if (!n.isObject() || n.path("seq").asLong(0) <= 0) {
                return null;
            }
            JsonNode payload = n.path("payload");
            return new EventRecord(n.path("seq").asLong(), n.path("ts").asLong(0),
                    n.path("event").asString(""), n.path("agentId").asString(null),
                    payload.isObject() || payload.isValueNode() ? payload : Json.obj(),
                    n.has("ext") && !n.path("ext").isNull() ? n.path("ext") : null);
        } catch (RuntimeException e) {
            return null; // 撕行
        }
    }

    /** 行内 seq 前缀提取(免整行 JSON 解析的快速路径)。 */
    private static long seqOf(String line) {
        int i = line.indexOf("\"seq\":");
        if (i < 0) {
            return 0;
        }
        i += 6;
        long v = 0;
        boolean any = false;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            v = v * 10 + (c - '0');
            any = true;
            i++;
        }
        return any ? v : 0;
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) {
                    deleteRecursively(c);
                }
            }
        }
        Files.deleteIfExists(p);
    }

    /**
     * 反向行扫描器:从文件尾向文件头一次产出一行(按文件内物理倒序)。
     * 随机访问分块(默认 64KB)反向读、只保留当前块 + 跨块残片(carry),不载入全文;
     * 块内按 '\n' 切行;末行无换行、文件头无前导换行均有特判;撕行由调用方在 parseLine 失败
     * 或 seqOf 归零后静默跳过。
     */
    private static final class ReverseLineReader implements AutoCloseable {
        private static final int DEFAULT_BLOCK = 64 * 1024;

        private final Path file;
        private final int blockBytes;
        private final SeekableByteChannel channel;
        private long nextPos; // 下一个要读的块末尾偏移(开区间)
        private boolean firstChunk = true;
        /** 未完成行:已读部分按"靠文件头一侧在前、靠文件尾一侧在后"接续(跨块长行拼接)。 */
        private byte[] carry;
        /** 已切出、尚未吐出的行(物理倒序;pollFirst 即最靠文件尾的一行)。 */
        private final ArrayDeque<String> lines = new ArrayDeque<>();
        private boolean done;

        ReverseLineReader(Path file) throws IOException {
            this(file, DEFAULT_BLOCK);
        }

        ReverseLineReader(Path file, int blockBytes) throws IOException {
            this.file = file;
            this.blockBytes = blockBytes;
            this.channel = Files.newByteChannel(file, StandardOpenOption.READ);
            this.nextPos = channel.size();
        }

        boolean hasNext() throws IOException {
            while (lines.isEmpty() && !done) {
                readChunk();
            }
            return !lines.isEmpty();
        }

        String nextLine() throws IOException {
            return hasNext() ? lines.pollFirst() : null;
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }

        private void readChunk() throws IOException {
            if (nextPos <= 0) {
                // 已到文件头(防御:有内容即首行且恰无前导换行,正常路径 carry 已空)
                if (carry != null) {
                    lines.addLast(new String(carry, StandardCharsets.UTF_8));
                    carry = null;
                }
                done = true;
                return;
            }
            long pos = Math.max(0, nextPos - blockBytes);
            int len = (int) (nextPos - pos);
            byte[] buf = new byte[len];
            channel.position(pos);
            ByteBuffer bb = ByteBuffer.wrap(buf);
            while (bb.hasRemaining()) {
                int r = channel.read(bb);
                if (r < 0) {
                    throw new EOFException("jsonl 反向读取截断: " + file);
                }
            }
            boolean atHead = pos == 0;
            nextPos = pos;
            process(buf, firstChunk, atHead);
            firstChunk = false;
        }

        private void process(byte[] buf, boolean first, boolean atHead) {
            // 块内所有 '\n' 位置(升序)
            int[] nl = new int[buf.length];
            int m = 0;
            for (int i = 0; i < buf.length; i++) {
                if (buf[i] == '\n') {
                    nl[m++] = i;
                }
            }
            if (m == 0) {
                // 整块是同一行(或其一部分):接到 carry 靠文件头一侧
                carry = concat(buf, carry);
                if (atHead) { // 文件头无前导换行,该行完整
                    lines.addLast(new String(carry, StandardCharsets.UTF_8));
                    carry = null;
                }
                return;
            }
            // 块内最靠文件尾的一段(最后一个 '\n' 之后):下一块之前的行
            byte[] tailSeg = slice(buf, nl[m - 1] + 1, buf.length);
            if (first) {
                // 文件尾块:末行无换行(崩溃残片)特判——tailSeg 非空即一行(torn 由调用方兜弃)
                if (tailSeg.length > 0) {
                    lines.addLast(new String(tailSeg, StandardCharsets.UTF_8));
                }
            } else {
                // 跨块拼接:tailSeg 是行头侧残片、carry 是高块侧残片,拼成完整行
                if (tailSeg.length > 0 || carry != null) {
                    byte[] joined = concat(tailSeg, carry);
                    if (joined.length > 0) {
                        lines.addLast(new String(joined, StandardCharsets.UTF_8));
                    }
                }
                carry = null;
            }
            // 块内相邻两个 '\n' 之间的完整行,按物理倒序入队(从最靠文件尾的开始)
            for (int i = m - 2; i >= 0; i--) {
                int from = nl[i] + 1;
                int to = nl[i + 1];
                if (from < to) { // 空行(连续 '\n')跳过
                    lines.addLast(new String(buf, from, to - from, StandardCharsets.UTF_8));
                }
            }
            // 块首段(第一个 '\n' 之前):非文件头时为跨块残片留作 carry;到文件头即首行直接产出
            if (buf[0] != '\n') {
                byte[] headSeg = slice(buf, 0, nl[0]);
                if (atHead) {
                    lines.addLast(new String(headSeg, StandardCharsets.UTF_8));
                } else {
                    carry = headSeg;
                }
            }
        }

        /** 拼接:左段在前(靠文件头一侧)、右段在后(靠文件尾一侧);null 视为空。 */
        private static byte[] concat(byte[] a, byte[] b) {
            int al = a == null ? 0 : a.length;
            int bl = b == null ? 0 : b.length;
            byte[] out = new byte[al + bl];
            if (al > 0) {
                System.arraycopy(a, 0, out, 0, al);
            }
            if (bl > 0) {
                System.arraycopy(b, 0, out, al, bl);
            }
            return out;
        }

        private static byte[] slice(byte[] buf, int from, int to) {
            byte[] out = new byte[to - from];
            System.arraycopy(buf, from, out, 0, to - from);
            return out;
        }
    }
}
