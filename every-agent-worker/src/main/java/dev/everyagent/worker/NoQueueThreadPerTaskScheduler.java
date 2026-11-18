package dev.everyagent.worker;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.Disposable;
import reactor.core.Scannable;
import reactor.core.scheduler.Scheduler;

/**
 * 无队列 thread-per-task 调度器:替换 {@code Schedulers.boundedElastic()} 的默认实现,
 * 消除「工具批调度任务被其他链的长阻塞工具堵在串行执行器队列里」的结构性缺陷。
 *
 * <p><b>根因背景</b>(2026-11 生产三次复现 + 本地可复现实验定案,Spring AI 2.0.1 +
 * reactor 3.8.x):Spring AI {@code ToolCallingAdvisor} 把「轮次聚合完成后的工具批
 * 调度任务」{@code subscribeOn(Schedulers.boundedElastic())}。默认实现(含官方 VT 模式
 * {@code SequentialThreadPerTaskExecutor})的 Worker 是<b>串行</b>执行器(单线程+任务
 * 队列);池满后 {@code choseOneBusy} 复用会把新任务排进<b>已被其他 agent 的长阻塞
 * 工具</b>(wait_agents 等待子 agent / ask_user 等用户输入 / 长命令)<b>占住的队列</b>,
 * 表现为「轮次聚合完成已打日志、工具批永不开始、任务静默 running 数十分钟」,取消
 * 占线任务后 0.5s 内恢复。2 核小机(池小/复用路径易触达)必现,多核开发机难复现。
 *
 * <p><b>本实现语义</b>:每次 {@code schedule} 提交 = 独立虚拟线程,无队列、无复用、
 * 无互堵;delay&gt;0 经共享单线程定时器<b>仅做延时触发</b>(触发后仍起独立虚拟线程,
 * 不在定时线程上跑任务本体);{@code Worker.dispose()} 中断该 worker 尚在运行的全部
 * 任务线程——保持订阅取消语义(阻塞工具经 interrupt 提前退出,与旧实现的
 * shutdownNow 同效)。
 *
 * <p>经 {@link WorkerApplication#installNoQueueBoundedElastic()} 于 main 最早期
 * {@code Schedulers.setFactory} 全局接入;系统属性 {@code everyagent.worker.noQueueScheduler=false}
 * 可回退默认实现。
 */
final class NoQueueThreadPerTaskScheduler implements Scheduler, Scannable {

    private static final Logger log = LoggerFactory.getLogger(NoQueueThreadPerTaskScheduler.class);

    /** 线程名前缀(诊断日志/线程转储可辨认归属)。 */
    static final String NAME = "eaElastic";

    private final AtomicBoolean disposed = new AtomicBoolean();
    private final AtomicInteger threadCounter = new AtomicInteger();
    private final AtomicInteger liveThreads = new AtomicInteger();

    /** 共享单线程定时器:仅承担 delay&gt;0 的延时触发(触发后任务仍在独立虚拟线程执行)。 */
    private final ScheduledExecutorService delayer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, NAME + "-delayer");
        t.setDaemon(true);
        return t;
    });

    @Override
    public Disposable schedule(Runnable task) {
        return submit(null, task, 0, TimeUnit.MILLISECONDS);
    }

    @Override
    public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
        return submit(null, task, delay, unit);
    }

    @Override
    public Worker createWorker() {
        return new NoQueueWorker();
    }

    @Override
    public boolean isDisposed() {
        return disposed.get();
    }

    @Override
    public void dispose() {
        if (disposed.compareAndSet(false, true)) {
            delayer.shutdownNow();
        }
    }

    /**
     * 统一提交:立即(delay&lt;=0)起虚拟线程;延时经 delayer 触发后起虚拟线程。
     *
     * @param owner 归属 worker(null = 顶层直接提交);非空时任务登记进 worker,
     *              worker.dispose() 时统一中断/取消
     */
    private Disposable submit(NoQueueWorker owner, Runnable task, long delay, TimeUnit unit) {
        Objects.requireNonNull(task, "task");
        if (disposed.get()) {
            throw new RejectedExecutionException(NAME + " 已 dispose,拒绝新任务");
        }
        if (owner != null && owner.disposed) {
            throw new RejectedExecutionException(NAME + " worker 已 dispose,拒绝新任务");
        }
        if (delay <= 0) {
            return spawn(owner, task);
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        ScheduledFuture<?> trigger = delayer.schedule(() -> {
            if (!cancelled.get()) {
                spawn(owner, task);
            }
        }, delay, unit);
        Disposable handle = new Disposable() {
            @Override
            public void dispose() {
                cancelled.set(true);
                trigger.cancel(false);
            }

            @Override
            public boolean isDisposed() {
                return cancelled.get() || trigger.isDone();
            }
        };
        if (owner != null) {
            owner.register(handle);
        }
        return handle;
    }

    /** 起独立虚拟线程执行任务;返回可中断句柄。 */
    private Disposable spawn(NoQueueWorker owner, Runnable task) {
        // 双重检查:提交到启动间隙 worker 可能已 dispose
        if (owner != null && owner.disposed) {
            throw new RejectedExecutionException(NAME + " worker 已 dispose,拒绝新任务");
        }
        Thread thread = Thread.ofVirtual()
                .name(NAME + "-" + threadCounter.incrementAndGet())
                .unstarted(() -> {
                    liveThreads.incrementAndGet();
                    try {
                        task.run();
                    }
                    catch (Throwable t) {
                        // reactor 任务的异常处理在操作符内部;兜底记录防静默吞没
                        log.warn("[{}] 任务异常退出: {}", NAME, t.toString());
                    }
                    finally {
                        liveThreads.decrementAndGet();
                    }
                });
        Disposable handle = thread::interrupt;
        if (owner != null) {
            owner.register(handle);
        }
        thread.start();
        return handle;
    }

    /** 诊断统计(无活动看门狗池转储用):当前在跑任务线程数。 */
    int liveThreads() {
        return liveThreads.get();
    }

    @Override
    public Object scanUnsafe(Attr key) {
        if (key == Attr.NAME) {
            return NAME;
        }
        return null;
    }

    /** 无队列 worker:仅登记已提交句柄,dispose 时统一中断/取消(无排队语义)。 */
    private final class NoQueueWorker implements Worker {

        private final ConcurrentLinkedQueue<Disposable> handles = new ConcurrentLinkedQueue<>();
        volatile boolean disposed;

        void register(Disposable d) {
            if (disposed) {
                d.dispose();
                throw new RejectedExecutionException(NAME + " worker 已 dispose");
            }
            handles.add(d);
        }

        @Override
        public Disposable schedule(Runnable task) {
            return submit(this, task, 0, TimeUnit.MILLISECONDS);
        }

        @Override
        public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
            return submit(this, task, delay, unit);
        }

        @Override
        public void dispose() {
            disposed = true;
            Disposable d;
            while ((d = handles.poll()) != null) {
                d.dispose();
            }
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }
    }
}
