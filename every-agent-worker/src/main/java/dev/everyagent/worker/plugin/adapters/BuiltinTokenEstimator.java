package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.spi.TokenEstimator;
import dev.everyagent.worker.AtomicFiles;
import dev.everyagent.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import jakarta.annotation.PreDestroy;
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
 * 内置 Token 估算器实现（{@link TokenEstimator} SPI）。
 *
 * <p>基础估算：CJK 字符 ≈ 1 token、其余 ≈ 4 字符 1 token（无 tokenizer 粗估，
 * 与原 {@code ModelRateLimiter.estimateTokens} / {@code ModelLengthGuardAdvisor} 同口径）。
 *
 * <p>在线校准（EMA）：每次模型调用完成后，用真实 {@code completionTokens} 与估算值
 * 的比值更新 {@code factor}（每 configId 独立）。误差持续 &lt; 收敛阈值（默认 2%）
 * 连续 N 次（默认 3）后停止校准；converged 后误差 &gt; 漂移阈值（默认 5%）时重置继续。
 * 系数上下界 [0.3, 3.0]，防止异常样本拉飞。
 *
 * <p>持久化：落盘 {@code <homeDir>/model-rate-state.json}，异步合并（单线程 writer +
 * dirty 标记 + 原子替换）；重启后接续，避免冷启动系数回退默认。读写失败静默降级，
 * 绝不阻塞模型调用。
 *
 * <p>去重：同一轮模型调用可能被 {@code RateLimitedChatModel}（限流路径）和
 * {@code TokenCalibrationAdvisor}（advisor 路径）同时观察到 usage，{@link #calibrate}
 * 内部用最近样本指纹去重，同一 (configId, estimated, actual) 三元组短时间不重复校准。
 */
@Component
public class BuiltinTokenEstimator implements TokenEstimator {

    private static final Logger log = LoggerFactory.getLogger(BuiltinTokenEstimator.class);
    private static final String FILE_NAME = "model-rate-state.json";

    /** 内存单条状态。 */
    private record EstState(double factor, long sampleCount, boolean converged,
            int consecutiveGood, long lastUpdated) {
    }

    private final Path file;
    private final Map<String, EstState> states = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final ExecutorService writer;
    private final WorkerProperties props;

    /** 去重：最近一次校准样本指纹（configId → "estimated:actual"）。 */
    private final Map<String, String> lastSampleKey = new ConcurrentHashMap<>();

    public BuiltinTokenEstimator(WorkerProperties props) {
        this.props = props;
        this.file = props.resolveHomeDir().resolve(FILE_NAME);
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "token-estimator-writer");
            t.setDaemon(true);
            return t;
        });
        load();
    }

    @PreDestroy
    void shutdown() {
        writer.shutdown();
    }

    // ---- TokenEstimator SPI ----

    @Override
    public long estimate(String text, String configId) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long raw = rawTokens(text);
        EstState s = states.get(configId);
        double f = (s == null) ? 1.0 : s.factor;
        return Math.max(0, Math.round(raw * f));
    }

    @Override
    public void calibrate(String configId, long estimatedTokens, long actualTokens) {
        if (actualTokens <= 0 || estimatedTokens <= 0) {
            return;
        }

        // 去重：同一 (configId, estimated, actual) 短时间不重复校准。
        String sampleKey = estimatedTokens + ":" + actualTokens;
        String prev = lastSampleKey.put(configId, sampleKey);
        if (prev != null && prev.equals(sampleKey)) {
            return;
        }

        WorkerProperties.ModelRate rateDefaults = props.getLimits().getModelRate();
        double alpha = rateDefaults.getEstEmaAlpha();
        double factorMin = rateDefaults.getEstFactorMin();
        double factorMax = rateDefaults.getEstFactorMax();
        double convergenceThreshold = props.getLimits().getTokenEstimatorConvergenceThreshold();
        int convergenceSamples = props.getLimits().getTokenEstimatorConvergenceSamples();
        double driftThreshold = props.getLimits().getTokenEstimatorDriftThreshold();

        states.compute(configId, (id, cur) -> {
            double oldFactor = (cur == null) ? 1.0 : cur.factor;
            boolean converged = (cur == null) ? false : cur.converged;
            int consecutiveGood = (cur == null) ? 0 : cur.consecutiveGood;
            long samples = (cur == null) ? 0 : cur.sampleCount;

            double error = Math.abs((double) estimatedTokens - (double) actualTokens)
                    / (double) actualTokens;

            // 漂移重置：converged 后误差超漂移阈值 → 重置继续校准。
            if (converged && error > driftThreshold) {
                log.info("[token-estimator] {} 漂移检测:误差 {}% > {}%,重置校准",
                        configId, Math.round(error * 10000) / 100.0, Math.round(driftThreshold * 10000) / 100.0);
                converged = false;
                consecutiveGood = 0;
            }

            // 收敛后停止校准。
            if (converged) {
                return cur;
            }

            // EMA 更新 factor。
            double ratio = (double) actualTokens / (double) estimatedTokens;
            double nextFactor = (1 - alpha) * oldFactor + alpha * ratio;
            nextFactor = Math.max(factorMin, Math.min(factorMax, nextFactor));

            // 收敛检测。
            if (error < convergenceThreshold) {
                consecutiveGood++;
                if (consecutiveGood >= convergenceSamples) {
                    converged = true;
                    log.info("[token-estimator] {} 收敛:连续 {} 次误差 < {}%,factor={}",
                            configId, consecutiveGood, convergenceThreshold * 100,
                            Math.round(nextFactor * 1000.0) / 1000.0);
                }
            } else {
                consecutiveGood = 0;
            }

            samples++;

            EstState newState = new EstState(nextFactor, samples, converged,
                    consecutiveGood, System.currentTimeMillis());

            if (Math.abs(nextFactor - oldFactor) > 1e-4 || !converged) {
                if (dirty.compareAndSet(false, true)) {
                    writer.submit(this::flush);
                }
            }

            return newState;
        });
    }

    @Override
    public double factorOf(String configId) {
        EstState s = states.get(configId);
        return s == null ? 1.0 : s.factor;
    }

    @Override
    public long sampleCountOf(String configId) {
        EstState s = states.get(configId);
        return s == null ? 0 : s.sampleCount;
    }

    // ---- 基础估算（CJK≈1 token、其余≈4 字符 1 token）----

    static long rawTokens(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        long cjk = 0;
        long other = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            boolean isCjk = sc == Character.UnicodeScript.HAN
                    || sc == Character.UnicodeScript.HIRAGANA
                    || sc == Character.UnicodeScript.KATAKANA
                    || sc == Character.UnicodeScript.HANGUL;
            if (isCjk) {
                cjk++;
            } else if (!Character.isWhitespace(cp) && !Character.isISOControl(cp)) {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }

    // ---- 持久化 ----

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
                boolean converged = n.path("converged").asBoolean(false);
                int consecutiveGood = n.path("consecutiveGood").asInt(0);
                if (factor > 0) {
                    states.put(k, new EstState(factor, sample, converged,
                            consecutiveGood, updated));
                }
            });
            log.info("[token-estimator] 已加载 {} 个模型的估算系数状态: {}", states.size(), file);
        } catch (Exception e) {
            log.warn("[token-estimator] 状态文件读取失败,使用默认估算系数: {}", e.toString());
        }
    }

    private void flush() {
        dirty.set(false);
        ObjectNode root = Json.obj();
        ObjectNode models = root.putObject("models");
        states.forEach((id, s) -> {
            ObjectNode o = models.putObject(id);
            o.put("tokenEstFactor", Math.round(s.factor() * 1000.0) / 1000.0);
            o.put("sampleCount", s.sampleCount());
            o.put("lastUpdated", s.lastUpdated());
            o.put("converged", s.converged());
            o.put("consecutiveGood", s.consecutiveGood());
        });
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
            AtomicFiles.replace(tmp, file);
        } catch (IOException e) {
            log.warn("[token-estimator] 状态落盘失败(不影响运行): {}", e.toString());
        }
    }
}
