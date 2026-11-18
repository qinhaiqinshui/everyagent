package dev.everyagent.worker;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Scheduler.Worker;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WorkerApplication#installNoQueueBoundedElastic()} 的行为单测:全局
 * boundedElastic 替换为无队列 thread-per-task 调度器(2026-11 生产「工具批被其他链
 * 的长阻塞工具堵在串行执行器队列」缺陷的根治,详见 NoQueueThreadPerTaskScheduler)。
 */
class WorkerApplicationBoundedElasticTest {

    // 注意:不做 @AfterEach resetFactory——会 shutdown 其他测试类可能持有的调度器实例
    // (全局单例可变状态,恢复比替换更危险);install 后保持全局为无队列实现,schedule
    // 语义完整,对后续测试类行为兼容。

    /** 接入后:任务跑在独立 eaElastic-N 虚拟线程上(不再是池化串行 worker)。 */
    @Test
    void installsNoQueueSchedulerRunningTasksOnVirtualThreads() throws Exception {
        WorkerApplication.installNoQueueBoundedElastic();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        Schedulers.boundedElastic().schedule(() -> {
            threadName.set(Thread.currentThread().getName()
                    + "@vt=" + Thread.currentThread().isVirtual());
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName.get()).contains("eaElastic-").contains("@vt=true");
    }

    /** 核心断言:同一 worker 上,长阻塞任务之后提交的任务不被其堵住(无队列)。 */
    @Test
    void secondSubmissionOnSameWorkerNotBlockedByLongTask() throws Exception {
        WorkerApplication.installNoQueueBoundedElastic();
        Scheduler scheduler = Schedulers.boundedElastic();
        Worker worker = scheduler.createWorker();

        CountDownLatch secondDone = new CountDownLatch(1);
        worker.schedule(() -> {
            try {
                Thread.sleep(8_000); // 长阻塞(模拟 wait_agents/ask_user)
            }
            catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        long t0 = System.nanoTime();
        worker.schedule(secondDone::countDown); // 同 worker 第二个提交
        assertThat(secondDone.await(5, TimeUnit.SECONDS))
                .as("同一 worker 的后续提交必须立即执行,不被前一个长阻塞任务排队阻塞")
                .isTrue();
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertThat(ms).isLessThan(3_000);
        worker.dispose();
    }

    /** worker.dispose() 中断在跑的长任务(订阅取消语义保持)。 */
    @Test
    void workerDisposeInterruptsRunningTask() throws Exception {
        WorkerApplication.installNoQueueBoundedElastic();
        Worker worker = Schedulers.boundedElastic().createWorker();
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        worker.schedule(() -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted.countDown();
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        worker.dispose();
        assertThat(interrupted.await(5, TimeUnit.SECONDS))
                .as("worker.dispose() 应中断其尚在运行的任务线程")
                .isTrue();
    }

    /** 触碰 Flux 确认与 reactor 静态初始化无冲突(冒烟)。 */
    @Test
    void fluxSmoke() throws Exception {
        WorkerApplication.installNoQueueBoundedElastic();
        CountDownLatch latch = new CountDownLatch(1);
        Flux.just(1).doOnNext(i -> latch.countDown()).subscribe();
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
    }
}
