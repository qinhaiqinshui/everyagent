package dev.everyagent.worker.task;

import dev.everyagent.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * 单模型请求限流器(docs/design-model-rate-limit.md §6):按 configId 各一个实例,
 * 跨任务/跨 agent 共享(厂商 rpm/tpm 是账户级,不按任务隔离)。
 *
 * <p>四层机制:<ol>
 *   <li><b>rpm 滑动窗口</b>(精确):60s 内发起请求数封顶;</li>
 *   <li><b>maxConcurrency 信号量</b>(精确):同时 in-flight 上限,长思考流重叠的核心闸门;</li>
 *   <li><b>输出侧流中估算</b>(近似):活跃流按自算文本 token 累计,接近 tpm 时延迟新起步;</li>
 *   <li><b>事后 usage 精确记账</b>(精确):请求完成后用真实 outputTokens 记入 60s tpm 窗口,
 *       并反向校准估算系数(EMA,持久化)。</li>
 * </ol>
 *
 * <p>超限不报错:请求进入有界等待队列(monitor wait + waiters 计数),等 rpm 窗口滚出 /
 * 并发释放 / tpm 压力下降后自动放行;仅<b>队列满 + 等待超时</b>转
 * {@link ModelRateLimitException}(非重试,错误信息给足操作建议)。
 * 等待可被线程中断(任务取消)即时打断,不悬挂。
 *
 * <p>token 估算为粗估(无 tokenizer):CJK≈1 token、其余≈4 字符 1 token,与
 * {@link ModelLengthGuardAdvisor} 同口径;系数经真实 usage 在线校准逐步收敛,误差长期可控。
 */
public final class ModelRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ModelRateLimiter.class);

    /** 等待重评估 tick(ms):时间流逝导致的 rpm/tpm 窗口滚动也能及时感知。 */
    private static final long REEVAL_TICK_MS = 500;

    private final String configId;
    private final ModelRateLimitConfig cfg;
    private final WorkerProperties.ModelRate defaults;
    private final long windowMs;
    /** 校准回调(factor, sampleCount):由 Registry 接持久化。 */
    private final BiConsumer<Double, Long> onCalibrated;

    /** 并发与窗口的统一监视器(串行化 acquire/complete/cancel)。 */
    private final Object monitor = new Object();
    /** rpm 窗口:发起时间戳(毫秒)。 */
    private final ArrayDeque<Long> rpmWindow = new ArrayDeque<>();
    /** tpm 窗口:完成请求 (ts, outputTokens)。 */
    private final ArrayDeque<TokenSample> tpmWindow = new ArrayDeque<>();
    /** 当前 in-flight 请求数。 */
    private int inFlight = 0;
    /** 当前等待中的请求数(有界队列)。 */
    private int waiters = 0;
    /** 活跃流估算输出 token 累计(③;所有 in-flight 流的贡献之和)。 */
    private long estActiveOutput = 0;
    /** 估算系数(在线校准,EMA)。 */
    private volatile double factor;
    private final AtomicLong sampleCount = new AtomicLong();

    private record TokenSample(long ts, long outputTokens) {
    }

    public ModelRateLimiter(String configId, ModelRateLimitConfig cfg,
            WorkerProperties.ModelRate defaults, double persistedFactor,
            BiConsumer<Double, Long> onCalibrated) {
        this.configId = configId;
        this.cfg = cfg;
        this.defaults = defaults;
        this.windowMs = Math.max(1_000, defaults.getEstWindowSec() * 1000);
        this.onCalibrated = onCalibrated;
        // 系数优先级:持久化校准结果 > 配置初始值 > 1.0。
        this.factor = persistedFactor > 0 ? persistedFactor : cfg.tokenEstFactor();
        if (persistedFactor > 0) {
            log.info("[model-rate] {} 估算系数从持久化恢复: {}", configId, factor);
        }
    }

    public String configId() {
        return configId;
    }

    /** 当前估算系数(观测用)。 */
    public double currentFactor() {
        return factor;
    }

    /** 运行时状态快照(config.get 透出 P2)。 */
    public Snapshot snapshot() {
        synchronized (monitor) {
            return new Snapshot(configId, cfg.enabled(), cfg.rpm(), cfg.maxConcurrency(), cfg.tpm(),
                    inFlight, waiters, factor, sampleCount.get());
        }
    }

    /** 运行时状态(供前端 config.get 展示;P2)。 */
    public record Snapshot(String configId, boolean enabled, int rpm, int maxConcurrency, long tpm,
            int inFlight, int waiters, double factor, long sampleCount) {
    }

    /** 排队等待信息(每次等待轮询回调;P2 trace 内容源)。 */
    public record WaitInfo(String configId, int waiters, int inFlight, long tpmPressure) {
    }

    /**
     * 阻塞等待放行(虚拟线程内可阻塞;可被中断)。放行后返回一个 {@link Permit} 生命周期句柄,
     * 调用方须在完成/取消时归还(否则并发额度泄漏)。
     *
     * @param onWait 排队观察者(可选,每次进入等待轮询时回调,供 P2 发 model_rate_wait trace;
     *               回调在锁内执行,须轻量、不可重入本 limiter)
     */
    public Permit acquire(BiConsumer<WaitInfo, Long> onWait)
            throws InterruptedException, ModelRateLimitException {
        synchronized (monitor) {
            long deadline = System.currentTimeMillis() + Math.max(1, defaults.getWaitTimeoutMs());
            if (waiters >= Math.max(1, defaults.getQueueCapacity())) {
                throw queueFull();
            }
            waiters++;
            try {
                while (true) {
                    long now = System.currentTimeMillis();
                    evictRpm(now);
                    evictTpm(now);

                    // ① rpm 滑动窗口
                    if (cfg.rpm() > 0 && rpmWindow.size() >= cfg.rpm()) {
                        long earliest = rpmWindow.peekFirst();
                        long rollMs = earliest + windowMs - now;
                        if (rollMs <= 0) {
                            continue; // 已可滚出,立即重估
                        }
                        waitAndNotify(rollMs, deadline, onWait);
                        continue;
                    }
                    // ② 并发上限
                    if (cfg.maxConcurrency() > 0 && inFlight >= cfg.maxConcurrency()) {
                        waitAndNotify(REEVAL_TICK_MS, deadline, onWait);
                        continue;
                    }
                    // ③ tpm 压力(估算 + 已完成窗口)
                    if (tpmPressureExceeded()) {
                        waitAndNotify(REEVAL_TICK_MS, deadline, onWait);
                        continue;
                    }

                    // 放行
                    rpmWindow.addLast(now);
                    inFlight++;
                    return new Permit();
                }
            } finally {
                waiters--;
            }
        }
    }

    public Permit acquire() throws InterruptedException, ModelRateLimitException {
        return acquire(null);
    }

    /** 等待至多 min(ms, 剩余 deadline);超时抛错。 */
    private void waitUpTo(long ms, long deadline) throws InterruptedException {
        long now = System.currentTimeMillis();
        long remaining = deadline - now;
        if (remaining <= 0) {
            throw timeout();
        }
        long wait = Math.min(ms, remaining);
        monitor.wait(wait);
        if (System.currentTimeMillis() >= deadline) {
            throw timeout();
        }
    }

    /** 等待并通知排队观察者(计算当前 tpm 压力供 trace 展示)。 */
    private void waitAndNotify(long ms, long deadline, BiConsumer<WaitInfo, Long> onWait)
            throws InterruptedException {
        if (onWait != null) {
            long pressure = currentTpmPressure();
            onWait.accept(new WaitInfo(configId, waiters, inFlight, pressure),
                    Math.min(ms, Math.max(0, deadline - System.currentTimeMillis())));
        }
        waitUpTo(ms, deadline);
    }

    /** 当前 tpm 压力(已完成窗口 token + 活跃流估算,未留安全余量)。 */
    private long currentTpmPressure() {
        long completed = 0;
        for (TokenSample s : tpmWindow) {
            completed += s.outputTokens();
        }
        return completed + estActiveOutput;
    }

    /** ③:已完成的 60s token 用量 + 活跃流估算(留安全余量) 是否已达 tpm。 */
    private boolean tpmPressureExceeded() {
        if (cfg.tpm() <= 0) {
            return false;
        }
        long completed = 0;
        for (TokenSample s : tpmWindow) {
            completed += s.outputTokens();
        }
        long projected = completed + (long) (estActiveOutput * defaults.getEstSafetyRatio());
        return projected >= cfg.tpm();
    }

    private void evictRpm(long now) {
        while (!rpmWindow.isEmpty() && now - rpmWindow.peekFirst() >= windowMs) {
            rpmWindow.pollFirst();
        }
    }

    private void evictTpm(long now) {
        while (!tpmWindow.isEmpty() && now - tpmWindow.peekFirst().ts() >= windowMs) {
            tpmWindow.pollFirst();
        }
    }

    /** 用一次完成的真实 outputTokens 校准估算系数(EMA)。 */
    private void calibrate(long actualOutputTokens, long estimatedTokens) {
        if (actualOutputTokens <= 0 || estimatedTokens <= 0) {
            return;
        }
        double ratio = (double) actualOutputTokens / (double) estimatedTokens;
        double alpha = defaults.getEstEmaAlpha();
        double next = (1 - alpha) * factor + alpha * ratio;
        next = Math.max(defaults.getEstFactorMin(), Math.min(defaults.getEstFactorMax(), next));
        if (Math.abs(next - factor) > 1e-4) {
            factor = next;
            long samples = sampleCount.incrementAndGet();
            if (onCalibrated != null) {
                try {
                    onCalibrated.accept(factor, samples);
                } catch (RuntimeException e) {
                    log.debug("[model-rate] {} 校准持久化回调失败: {}", configId, e.toString());
                }
            }
        }
    }

    private ModelRateLimitException timeout() {
        return new ModelRateLimitException(
                "模型「" + configId + "」请求拥堵:等待 " + defaults.getWaitTimeoutMs()
                        + "ms 仍未获得放行(并发=" + (cfg.maxConcurrency() > 0 ? cfg.maxConcurrency() : "不限")
                        + " / rpm=" + (cfg.rpm() > 0 ? cfg.rpm() : "不限")
                        + (cfg.tpm() > 0 ? " / tpm=" + cfg.tpm() : "") + ")。"
                        + "建议: 1) 减少同时派发的子 agent/并行任务数量; 2) 调大该模型 rpm/max-concurrency"
                        + "(按厂商套餐); 3) 稍后重试。");
    }

    private ModelRateLimitException queueFull() {
        return new ModelRateLimitException(
                "模型「" + configId + "」请求排队已满(队列上限 " + defaults.getQueueCapacity()
                        + ")。建议减少同时派发的子 agent 数量,或稍后重试。");
    }

    /** 估算一段文本的 token 数(CJK≈1、其余≈4 字符 1 token;与 ModelLengthGuardAdvisor 同口径)。 */
    static long estimateTokens(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        long cjk = 0;
        long other = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            boolean isCjk = sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA
                    || sc == Character.UnicodeScript.KATAKANA || sc == Character.UnicodeScript.HANGUL;
            if (isCjk) {
                cjk++;
            } else if (!Character.isWhitespace(cp) && !Character.isISOControl(cp)) {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }

    /** 请求生命周期句柄:流中累计估算、完成记账/校准/释放、取消释放。 */
    public final class Permit {
        private boolean done = false;
        private long estAcc = 0;

        private Permit() {
        }

        /** 流中逐 chunk 累计估算输出(仅 stream 路径调用;call 路径由 usage 精确记账)。 */
        public void onChunk(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            long e = estimateTokens(text);
            if (e <= 0) {
                return;
            }
            synchronized (monitor) {
                if (done) {
                    return;
                }
                estAcc += e;
                estActiveOutput += e;
            }
        }

        /** 完成:记账 tpm、校准系数、释放并发与估算。 */
        public void complete(long actualOutputTokens) {
            synchronized (monitor) {
                if (done) {
                    return;
                }
                done = true;
                long est = estAcc;
                estActiveOutput -= est;
                estAcc = 0;
                inFlight = Math.max(0, inFlight - 1);
                if (cfg.tpm() > 0) {
                    tpmWindow.addLast(new TokenSample(System.currentTimeMillis(), actualOutputTokens));
                    calibrate(actualOutputTokens, est);
                }
                monitor.notifyAll();
            }
        }

        /** 取消/异常:仅释放并发与估算,不记账(无真实 usage)。 */
        public void cancel() {
            synchronized (monitor) {
                if (done) {
                    return;
                }
                done = true;
                estActiveOutput -= estAcc;
                estAcc = 0;
                inFlight = Math.max(0, inFlight - 1);
                monitor.notifyAll();
            }
        }
    }
}