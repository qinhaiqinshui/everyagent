package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.exception.AuthRequiredException;
import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.plugin.api.exception.NotFoundException;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.worker.modules.search.SearchEngineException;
import dev.everyagent.worker.rpc.SandboxViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SearchProvider 调用护栏(架构 §8.5):统一 {@code search} 增补聚合与 mention 建议聚合共用的
 * 单 provider 调用包装。
 *
 * <p><b>跳过语义</b>:单个 provider 抛「非客户端可见」异常(受检/非受检)或超出超时预算,仅
 * WARN 跳过(返回 null),不影响其余 provider 结果与最终应答。
 *
 * <p><b>客户端可见错误上抛</b>:{@link BadParamsException}(参数非法,如非法正则)、
 * {@link NotFoundException}(路径不存在)、{@link SandboxViolationException}(沙箱越界)、
 * {@link AuthRequiredException}(需授权)、{@link SearchEngineException}(引擎不可用,如 rg 缺失)
 * 属「用户可读错误」,直接上抛由 {@link dev.everyagent.worker.rpc.RpcDispatcher} 回灌 rpc.err,
 * 不被静默成空结果。
 *
 * <p><b>超时预算</b>:{@code timeoutMs} ≤ 0(默认 0)时不限时——同步直调 + 仅异常护栏;
 * {@code timeoutMs} &gt; 0 时每次调用放到虚拟线程执行(Java 25)并用
 * {@link FutureTask#get(long, TimeUnit)} 施加预算,超时 {@code cancel(true)} 中断该调用。
 */
public final class SearchProviderInvoker {

    private static final Logger log = LoggerFactory.getLogger(SearchProviderInvoker.class);

    /** 日志标签([search] / [mention.query]),区分聚合来源。 */
    private final String tag;

    /** 单 provider 超时预算(ms);≤ 0 = 不限时(同步直调,仅异常护栏)。 */
    private final long timeoutMs;

    public SearchProviderInvoker(String tag, long timeoutMs) {
        this.tag = tag;
        this.timeoutMs = timeoutMs;
    }

    /** 一次 provider 调用(统一 search 的 {@code search} 或 mention 的 {@code suggest})。 */
    @FunctionalInterface
    public interface ProviderCall<T> {

        T call() throws Exception;
    }

    /**
     * 调用单个 provider 并施加护栏:抛非客户端异常的 provider 或超时预算耗尽仅 WARN 跳过、返回 null;
     * 客户端可见错误直接上抛;调用方线程被中断(如 rpc.cancel)时取消 provider 调用、恢复中断标记并
     * 抛 IllegalStateException 中止聚合。
     */
    public <T> T invoke(SearchProvider provider, ProviderCall<T> call) {
        if (timeoutMs <= 0) {
            // 不限时(默认):同步直调,仅异常护栏——与既有行为一致(零行为变化)
            try {
                return call.call();
            } catch (RuntimeException e) {
                rethrowIfClientError(e);
                log.warn("[{}] SearchProvider {} 执行失败,跳过", tag, provider.id(), e);
                return null;
            } catch (Exception e) {
                log.warn("[{}] SearchProvider {} 执行失败,跳过", tag, provider.id(), e);
                return null;
            }
        }
        FutureTask<T> task = new FutureTask<>(call::call);
        Thread.ofVirtual().name("search-provider-" + provider.id()).start(task);
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true); // 中断虚拟线程上的调用,尽快释放 provider 侧资源
            log.warn("[{}] SearchProvider {} 超出超时预算({}ms),跳过", tag, provider.id(), timeoutMs);
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                rethrowIfClientError(re);
            }
            log.warn("[{}] SearchProvider {} 执行失败,跳过", tag, provider.id(), cause);
            return null;
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 SearchProvider 结果时调用方被中断", e);
        }
    }

    /** 客户端可见错误(BAD_PARAMS/NOT_FOUND/SANDBOX_DENIED/AUTH_REQUIRED/引擎不可用)原样上抛。 */
    private static void rethrowIfClientError(RuntimeException e) {
        if (e instanceof BadParamsException
                || e instanceof NotFoundException
                || e instanceof SandboxViolationException
                || e instanceof AuthRequiredException
                || e instanceof SearchEngineException) {
            throw e;
        }
    }
}