package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.AtomicFiles;
import dev.everyagent.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型限流运行态持久化(docs/design-model-rate-limit.md §5.3):
 * 每模型独立维护的估算系数 {@code tokenEstFactor} 经请求完成后的真实 usage 在线校准(EMA),
 * 落盘到 {@code <homeDir>/model-rate-state.json},重启后接续,避免冷启动估算系数回退默认。
 *
 * <p>位置:系统目录(不经工作区、不进事件日志);文件与 application-worker.yaml 同层。
 * 写策略:内存 {@code ConcurrentHashMap} 为真相,落盘为异步合并(单线程 writer + dirty 标记),
 * 高频校准不会每次都触发磁盘 IO;临时文件 + {@link AtomicFiles#replace} 原子替换;
 * 读写失败一律静默降级(log warn),绝不阻塞模型调用。
 */
public final class ModelRateStateStore {

    private static final Logger log = LoggerFactory.getLogger(ModelRateStateStore.class);
    private static final String FILE_NAME = "model-rate-state.json";

    /** 内存单条状态。 */
    public record State(double tokenEstFactor, long sampleCount, long lastUpdated) {
    }

    private final Path file;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final ExecutorService writer;

    public ModelRateStateStore(WorkerProperties props) {
        this.file = props.resolveHomeDir().resolve(FILE_NAME);
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "model-rate-state-writer");
            t.setDaemon(true);
            return t;
        });
        load();
    }

    /** 关闭 writer(worker 停机收尾调用;幂等)。 */
    public void close() {
        writer.shutdown();
    }

    private void load() {
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            JsonNode root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode models = root == null ? null : root.path("models");
            if (models == null || !models.isObject()) {
                return;
            }
            models.forEachEntry((k, n) -> {
                if (n == null || !n.isObject()) {
                    return;
                }
                double factor = n.path("tokenEstFactor").asDouble(0);
                long sample = n.path("sampleCount").asLong(0);
                long updated = n.path("lastUpdated").asLong(0);
                if (factor > 0) {
                    states.put(k, new State(factor, sample, updated));
                }
            });
            log.info("[model-rate] 已加载 {} 个模型的估算系数状态: {}", states.size(), file);
        } catch (Exception e) {
            // 读失败/坏文件:按默认系数继续(静默降级,不阻塞)。
            log.warn("[model-rate] 状态文件读取失败,使用默认估算系数: {}", e.toString());
        }
    }

    /** 读取持久化的估算系数(configId 无记录返回 -1 表示未校准)。 */
    public double factorOf(String configId) {
        State s = states.get(configId);
        return s == null ? -1 : s.tokenEstFactor;
    }

    /** 更新内存状态并异步合并落盘。 */
    public void record(String configId, double factor, long sampleCount) {
        states.put(configId, new State(factor, sampleCount, System.currentTimeMillis()));
        if (dirty.compareAndSet(false, true)) {
            writer.submit(this::flush);
        }
    }

    /** 落盘快照(writer 线程执行;失败静默,下个周期重试)。 */
    private void flush() {
        // 先复位 dirty:写盘期间新到的 record 会重新置位并 submit 下一次 flush,不丢变更。
        dirty.set(false);
        ObjectNode root = Json.obj();
        ObjectNode models = root.putObject("models");
        states.forEach((id, s) -> {
            ObjectNode o = models.putObject(id);
            o.put("tokenEstFactor", Math.round(s.tokenEstFactor() * 1000.0) / 1000.0);
            o.put("sampleCount", s.sampleCount());
            o.put("lastUpdated", s.lastUpdated());
        });
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
            AtomicFiles.replace(tmp, file);
        } catch (IOException e) {
            // 写失败静默降级:内存状态仍在,下一校准周期会再触发。
            log.warn("[model-rate] 状态落盘失败(不影响运行): {}", e.toString());
        }
    }
}