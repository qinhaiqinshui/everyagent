package dev.everyagent.plugin.modelratelimit;

import dev.everyagent.plugin.api.config.WorkerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link BuiltinTokenEstimator} 单测:校准收敛行为。
 *
 * <p>用最小 {@link WorkerConfig} 桩(home 指向 {@link TempDir})驱动;真值配置:
 * 收敛阈值 2% / 连续 3 次 / 漂移阈值 5% / EMA α=0.1 / 系数界 [0.3, 3.0]。
 */
class BuiltinTokenEstimatorTest {

    // ---- WorkerConfig 最小桩 ----

    static WorkerConfig stubConfig(Path home) {
        WorkerConfig.Limits.ModelRate modelRate = new WorkerConfig.Limits.ModelRate() {
            @Override public int queueCapacity() { return 10; }
            @Override public long waitTimeoutMs() { return 30_000; }
            @Override public long estWindowSec() { return 60; }
            @Override public double estSafetyRatio() { return 1.1; }
            @Override public double estEmaAlpha() { return 0.1; }
            @Override public double estFactorMin() { return 0.3; }
            @Override public double estFactorMax() { return 3.0; }
            @Override public int defaultRpm() { return 0; }
            @Override public int defaultMaxConcurrency() { return 0; }
            @Override public long defaultTpm() { return 0; }
        };
        WorkerConfig.Limits limits = new WorkerConfig.Limits() {
            @Override public AdaptiveMaxTokens adaptiveMaxTokens() { throw new UnsupportedOperationException(); }
            @Override public int maxConcurrentTasks() { throw new UnsupportedOperationException(); }
            @Override public long askTimeoutMs() { throw new UnsupportedOperationException(); }
            @Override public long modelLengthStallMs() { throw new UnsupportedOperationException(); }
            @Override public long lengthDisconnectMinTokens() { throw new UnsupportedOperationException(); }
            @Override public ModelRate modelRate() { return modelRate; }
            @Override public boolean contextCompressionEnabled() { throw new UnsupportedOperationException(); }
            @Override public double contextTriggerRatio() { throw new UnsupportedOperationException(); }
            @Override public double contextTargetRatio() { throw new UnsupportedOperationException(); }
            @Override public double contextSafetyRatio() { throw new UnsupportedOperationException(); }
            @Override public long contextToolReserveTokens() { throw new UnsupportedOperationException(); }
            @Override public int contextMaxToolResultChars() { throw new UnsupportedOperationException(); }
            @Override public boolean contextOffsetEnabled() { throw new UnsupportedOperationException(); }
            @Override public boolean contextSummaryEnabled() { throw new UnsupportedOperationException(); }
            @Override public int contextSummaryMaxTokens() { throw new UnsupportedOperationException(); }
            @Override public double tokenEstimatorConvergenceThreshold() { return 0.02; }
            @Override public int tokenEstimatorConvergenceSamples() { return 3; }
            @Override public double tokenEstimatorDriftThreshold() { return 0.05; }
        };
        return new WorkerConfig() {
            @Override public Limits limits() { return limits; }
            @Override public Retry retry() { throw new UnsupportedOperationException(); }
            @Override public Sandbox sandbox() { throw new UnsupportedOperationException(); }
            @Override public Permissions permissions() { throw new UnsupportedOperationException(); }
            @Override public Git git() { throw new UnsupportedOperationException(); }
            @Override public Path resolveHomeDir() { return home; }
            @Override public Path resolveSandboxPersistentRoot() { throw new UnsupportedOperationException(); }
            @Override public Path resolveSkillsDir() { throw new UnsupportedOperationException(); }
            @Override public Path resolveRuntimeDir() { throw new UnsupportedOperationException(); }
        };
    }

    /**
     * 消除自反馈后,连续同分布样本应能收敛并停止校准。
     *
     * <p>样本:raw≈1000、actual=1050(真实比值恒 1.05)。生产侧 estimate() 返回 raw×factor,
     * 故每次喂入 {@code estimated = round(1000 × 当前factor)}。修复前 ratio 的不动点为
     * √1.05≈1.0247、误差 2.4%&gt;2%,永不收敛;修复后 ratio=actual/raw→1.05,factor 收敛。
     */
    @Test
    void convergesOnSameDistributionAndStopsSampling(@TempDir Path home) {
        BuiltinTokenEstimator estimator = new BuiltinTokenEstimator(stubConfig(home));
        String id = "cfg";
        final long raw = 1000;
        final long actual = 1050; // 真实比值 1.05

        for (int i = 0; i < 20; i++) {
            long estimated = Math.round(raw * estimator.factorOf(id));
            estimator.calibrate(id, estimated, actual);
        }

        double factor = estimator.factorOf(id);
        // 收敛阈值 2% ⇒ 残留偏差可到 ~2%;故容差取 0.02(绝对 1e-2 与 2% 阈值数学上不相容)。
        assertEquals(1.05, factor, 0.02, "同分布样本应收敛到真实比值 1.05,实际 " + factor);

        long samples = estimator.sampleCountOf(id);
        // 已 converged → 停止校准:即使喂入非重复样本(绕过样本指纹去重)样本数也不增长。
        estimator.calibrate(id, 1040, actual); // 不同样本键,误差 ~0.95% < 漂移阈值 5%
        assertEquals(samples, estimator.sampleCountOf(id), "已 converged,应停止校准(样本数冻结)");
    }

    /** 旧口径状态文件(无 version,系数 2.5)必须被丢弃,不得载入污染系数。 */
    @Test
    void discardsLegacyStateWithoutVersion(@TempDir Path home) throws IOException {
        Files.writeString(home.resolve("model-rate-state.json"),
                "{\"models\":{\"cfg\":{\"tokenEstFactor\":2.5,\"sampleCount\":100,"
                        + "\"lastUpdated\":1,\"converged\":false,\"consecutiveGood\":0}}}",
                StandardCharsets.UTF_8);

        BuiltinTokenEstimator estimator = new BuiltinTokenEstimator(stubConfig(home));

        assertEquals(1.0, estimator.factorOf("cfg"), 1e-9, "旧口径(无 version)系数应被丢弃");
        assertEquals(0L, estimator.sampleCountOf("cfg"), "旧口径样本数应清零");
    }

    /** 当前口径版本(version=2)的状态文件应正常载入。 */
    @Test
    void loadsCurrentVersionState(@TempDir Path home) throws IOException {
        Files.writeString(home.resolve("model-rate-state.json"),
                "{\"version\":2,\"models\":{\"cfg\":{\"tokenEstFactor\":1.5,\"sampleCount\":7,"
                        + "\"lastUpdated\":1,\"converged\":true,\"consecutiveGood\":3}}}",
                StandardCharsets.UTF_8);

        BuiltinTokenEstimator estimator = new BuiltinTokenEstimator(stubConfig(home));

        assertEquals(1.5, estimator.factorOf("cfg"), 1e-9, "当前版本系数应载入");
        assertEquals(7L, estimator.sampleCountOf("cfg"), "当前版本样本数应载入");
    }
}