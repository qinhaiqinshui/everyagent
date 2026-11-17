package dev.everyagent.worker;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code WorkerApplication#preferVirtualThreadBoundedElastic()} 的属性落点单测
 * (2026-11 t_145g 根因修复:全局 boundedElastic 切 thread-per-task 虚拟线程实现)。
 *
 * <p><b>行为断言(任务跑在虚拟线程上)无法在本测试可靠执行</b>:开关是 Schedulers 类
 * 初始化时一次性固化的静态字段,单 JVM 测试共享进程里若任何先跑的测试先触碰了
 * Schedulers(如 Flux.interval → parallel()),开关已按默认 false 固化,本测试再设属性
 * 不会改变行为——会 flaky。故这里只验证属性默认值落点;真实行为经本地 main 手测
 * (设置属性后 schedule 的任务 Thread#isVirtual()==true)。
 */
class WorkerApplicationBoundedElasticTest {

    @Test
    void setsVirtualThreadDefaultsWhenUnset() {
        String onVt = "reactor.schedulers.defaultBoundedElasticOnVirtualThreads";
        String size = "reactor.schedulers.defaultBoundedElasticSize";
        String oldVt = System.clearProperty(onVt);
        String oldSize = System.clearProperty(size);
        try {
            WorkerApplication.preferVirtualThreadBoundedElastic();
            assertThat(System.getProperty(onVt)).isEqualTo("true");
            assertThat(Integer.parseInt(System.getProperty(size)))
                    .isGreaterThanOrEqualTo(64);
        } finally {
            if (oldVt != null) { System.setProperty(onVt, oldVt); }
            if (oldSize != null) { System.setProperty(size, oldSize); }
        }
    }

    @Test
    void respectsExplicitOperatorOverride() {
        String onVt = "reactor.schedulers.defaultBoundedElasticOnVirtualThreads";
        String oldVt = System.setProperty(onVt, "false");
        try {
            WorkerApplication.preferVirtualThreadBoundedElastic();
            assertThat(System.getProperty(onVt)).isEqualTo("false"); // 不覆盖显式配置
        } finally {
            if (oldVt == null) { System.clearProperty(onVt); }
            else { System.setProperty(onVt, oldVt); }
        }
    }

    /** 触碰 Flux 确认无静态初始化冲突(仅编译期依赖冒烟,无行为断言)。 */
    @Test
    void fluxSmoke() throws Exception {
        AtomicBoolean ok = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        Flux.just(1).doOnNext(i -> { ok.set(true); latch.countDown(); }).subscribe();
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(ok.get()).isTrue();
        assertThat(Schedulers.class.getName()).isNotEmpty();
    }
}
