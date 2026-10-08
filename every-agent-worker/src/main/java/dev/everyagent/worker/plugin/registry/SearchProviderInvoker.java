package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.spi.SearchProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SearchProvider 调用护栏(架构 §8.5):fs.search / task.search 增补聚合共用的
 * 单 provider 调用包装——单个 provider 抛异常(受检/非受检)或超出超时预算仅
 * WARN 跳过(返回 null),不影响其余 provider 结果与最终应答。
 *
 * <p><b>超时预算</b>:{@code timeoutMs} ≤ 0(默认 0)时不限时——同步直调 + 仅异常
 * 护栏,与机制引入前的行为一致(零行为变化);{@code timeoutMs} &gt; 0 时每次调用放到
 * 虚拟线程执行(Java 25)并用 {@link FutureTask#get(long, TimeUnit)} 施加预算,超时
 * {@code cancel(true)} 中断该调用(不响应中断的 provider 实现自行收尾,虚拟线程为
 * daemon,不阻塞调用方/进程退出)。预算值未来由配置键
 * {@code worker.search.provider-timeout-ms} 接入 yml,本步由服务侧内部缺省 0 承载。
 *
 * <p><b>返回 null 双语义</b>:护栏跳过(异常/超时)或 provider 合法返回 null,调用方
 * 一律按「无结果、继续下一个 provider」处理(与既有 null 契约一致)。
 */
public final class SearchProviderInvoker {

    private static final Logger log = LoggerFactory.getLogger(SearchProviderInvoker.class);

    /** 日志标签([fs.search] / [task.search]),区分聚合来源。 */
    private final String tag;

    /** 单 provider 超时预算(ms);≤ 0 = 不限时(同步直调,仅异常护栏)。 */
    private final long timeoutMs;

    public SearchProviderInvoker(String tag, long timeoutMs) {
        this.tag = tag;
        this.timeoutMs = timeoutMs;
    }

    /** 一次 provider 调用(SPI 两入口 searchFiles/searchTasks 由调用方以 lambda 适配)。 */
    @FunctionalInterface
    public interface ProviderCall<T> {

        List<T> call() throws Exception;
    }

    /**
     * 调用单个 provider 并施加护栏:抛异常(受检/非受检)或超时预算耗尽仅 WARN 跳过、
     * 返回 null;调用方线程被中断(如 rpc.cancel)时取消 provider 调用、恢复中断标记并
     * 抛 IllegalStateException 中止聚合——仅 {@code timeoutMs} &gt; 0 的虚拟线程路径存在
     * 此中间层,同步路径与既有行为一致。
     */
    public <T> List<T> invoke(SearchProvider provider, ProviderCall<T> call) {
        if (timeoutMs <= 0) {
            // 不限时(默认):同步直调,仅异常护栏——与既有行为一致(零行为变化)
            try {
                return call.call();
            } catch (Exception e) {
                log.warn("[{}] SearchProvider {} 执行失败,跳过", tag, provider.id(), e);
                return null;
            }
        }
        FutureTask<List<T>> task = new FutureTask<>(call::call);
        Thread.ofVirtual().name("search-provider-" + provider.id()).start(task);
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true); // 中断虚拟线程上的调用,尽快释放 provider 侧资源
            log.warn("[{}] SearchProvider {} 超出超时预算({}ms),跳过", tag, provider.id(), timeoutMs);
            return null;
        } catch (ExecutionException e) {
            log.warn("[{}] SearchProvider {} 执行失败,跳过", tag, provider.id(), e.getCause());
            return null;
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 SearchProvider 结果时调用方被中断", e);
        }
    }
}
